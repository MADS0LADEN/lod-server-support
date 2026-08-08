package dev.vox.lss.trace;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.server.FabricChannelPressure;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flight telemetry + row-context assembly for the move-desync tracer
 * (move-desync-tracer-plan.md §1.4/§1.5). All entry points run on the server thread; the
 * state registry is concurrent only for the disconnect sweep and diag reads.
 *
 * <p>The arm condition deliberately includes "LSS send queue non-empty" (review U-11):
 * walking players during a backfill flood are the actual hypothesis population and were
 * invisible to a speed-only trigger. Envelope {@code obuf} is a FRESH probe read at
 * capture time — the state-cached gauge is once-per-tick, and control-arm players have no
 * state at all (which is why {@code FabricChannelPressure.forPlayer} went public).
 */
final class MoveTraceTelemetry {

    /** Arm at >6 blocks/s (§1.5) — brisk sprint is ~5.6, elytra cruising is 20+. */
    private static final double ARM_SPEED_BLOCKS_PER_SECOND = 6.0;
    private static final int RING_SAMPLE_TICK_INTERVAL = 4;
    private static final int FLIGHT_ROW_TICK_INTERVAL = 20;

    private static final ConcurrentHashMap<UUID, PlayerTraceState> STATES = new ConcurrentHashMap<>();
    private static volatile boolean swallowWarned;

    private MoveTraceTelemetry() {}

    static PlayerTraceState stateFor(ServerPlayer player) {
        var state = STATES.computeIfAbsent(player.getUUID(), uuid -> new PlayerTraceState());
        // Rebind on identity change: PlayerList.respawn replaces the ServerPlayer with
        // no DISCONNECT event, and a probe bound to the dead instance would pin it for
        // the rest of the session (review A-10).
        if (state.probeOwner() != player) {
            state.setProbe(FabricChannelPressure.forPlayer(player), player);
        }
        return state;
    }

    static void onMovePacket(ServerPlayer player) {
        stateFor(player).gapClock().record(System.currentTimeMillis());
    }

    static void onDisconnect(UUID uuid) {
        STATES.remove(uuid);
    }

    static void clearAll() {
        STATES.clear();
    }

    /** One diagnostic-swallowed throwable: warn once per JVM, then stay silent — a
     *  diagnostic must never spam its way into being disabled by the operator. */
    static void swallowed(Throwable t) {
        if (!swallowWarned) {
            swallowWarned = true;
            LSSLogger.warn("Move trace capture failed once (" + t + ") — further failures"
                    + " are silent; affected rows/fields are absent");
        }
    }

    /** END_SERVER_TICK body — only ever called while the tracer is enabled. */
    static void tick(MinecraftServer server) {
        try {
            var tracer = MoveDesyncTracer.active();
            if (tracer == null) return;
            int tickCount = server.getTickCount();
            boolean ringTick = tickCount % RING_SAMPLE_TICK_INTERVAL == 0;
            boolean flightTick = tickCount % FLIGHT_ROW_TICK_INTERVAL == 0;
            if (!ringTick && !flightTick) return;
            long now = System.currentTimeMillis();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Containment is PER PLAYER: one player with a pathological state (a
                // NaN delta-movement, a half-dead connection) must not abort every
                // later player's telemetry this tick (review A-6).
                try {
                    var state = stateFor(player);
                    if (!isArmed(player, state, now)) continue;
                    double speed = speedBlocksPerSecond(player);
                    long obuf = probeRead(state);
                    int cx = MoveEventMath.chunkCoord(player.getX());
                    int cz = MoveEventMath.chunkCoord(player.getZ());
                    var sendState = ChunkSendState.capture(player, cx, cz);
                    if (ringTick) {
                        ringSample(state, now, player, speed, obuf, sendState);
                    }
                    if (flightTick) {
                        tracer.emit(MoveRow.flight(envelope(tracer, player, now),
                                lssRegistered(player), lssBlock(player),
                                player.getX(), player.getY(), player.getZ(), speed,
                                awaitingTeleportOrNull(player),
                                state.gapClock().lastGapMs(), state.gapClock().maxGapWindowMs(now),
                                sendState,
                                player.level().getChunkSource().getLoadedChunksCount()));
                    }
                } catch (Throwable t) {
                    swallowed(t);
                }
            }
        } catch (Throwable t) {
            swallowed(t);
        }
    }

    /** {@code awaiting_tp} belongs on flight rows, where it can vary — every event site
     *  is structurally not-awaiting (review A-2). Null = accessor unavailable (absent). */
    private static Boolean awaitingTeleportOrNull(ServerPlayer player) {
        try {
            var acc = (dev.vox.lss.mixin.trace.AccessorServerGamePacketListener) player.connection;
            return acc.lss$awaitingPositionFromClient() != null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ringSample(PlayerTraceState state, long now, ServerPlayer player,
                                   double speed, long obuf, MoveRow.SendState sendState) {
        long gap = state.gapClock().lastGapMs();
        if (MoveRow.RUNG_MOONRISE.equals(sendState.rung())
                && sendState.mask5x5() != null && sendState.loaderCx() != null) {
            state.ring().add(now, player.getX(), player.getY(), player.getZ(), speed, obuf,
                    gap, true, sendState.anchorCx(), sendState.anchorCz(),
                    sendState.mask5x5(), sendState.loaderCx(), sendState.loaderCz());
        } else {
            state.ring().addNoSendState(now, player.getX(), player.getY(), player.getZ(),
                    speed, obuf, gap);
        }
    }

    /** §1.5's arm ladder: elytra, speed, actively-streaming LOD, or a recent event. */
    private static boolean isArmed(ServerPlayer player, PlayerTraceState state, long now) {
        if (state.eventArmed(now)) return true;
        if (player.isFallFlying()) return true;
        if (speedBlocksPerSecond(player) > ARM_SPEED_BLOCKS_PER_SECOND) return true;
        var lss = lssState(player);
        return lss != null && lss.getSendQueueSize() > 0;
    }

    /** Flush the trailing 5 Hz ring ahead of an event row (§1.5, Fable F2-4), then clear
     *  it so consecutive events don't re-ship the same samples. */
    static void flushRingBeforeEvent(MoveDesyncTracer tracer, ServerPlayer player,
                                     PlayerTraceState state) {
        if (state.ring().size() == 0) return;
        long now = System.currentTimeMillis();
        tracer.emit(MoveRow.flightRing(envelope(tracer, player, now),
                lssRegistered(player), lssBlock(player), state.ring()));
        state.ring().clear();
    }

    // ---- row-context assembly ----

    static MoveRow.Envelope envelope(MoveDesyncTracer tracer, ServerPlayer player, long now) {
        var server = player.level().getServer();
        long obuf = probeRead(stateFor(player));
        int latency = -1;
        try {
            latency = player.connection.latency();
        } catch (Throwable ignored) {
        }
        return new MoveRow.Envelope(tracer.bootId(), now, server.getTickCount(),
                player.getUUID().toString(), player.getPlainTextName(),
                player.level().dimension().identifier().toString(), obuf, latency,
                server.getAverageTickTimeNanos() / 1_000_000.0, server.getPlayerCount(),
                tracer.droppedCount());
    }

    private static long probeRead(PlayerTraceState state) {
        var probe = state.probe();
        if (probe == null) return -1;
        try {
            return probe.pendingOutboundBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static dev.vox.lss.common.processing.AbstractPlayerRequestState<?> lssState(ServerPlayer player) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) return null;
        return service.getPlayers().get(player.getUUID());
    }

    static boolean lssRegistered(ServerPlayer player) {
        return lssState(player) != null;
    }

    static MoveRow.LssBlock lssBlock(ServerPlayer player) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) return null;
        var state = service.getPlayers().get(player.getUUID());
        if (state == null) return null;
        String dialect = null;
        int proto = LSSConstants.PROTOCOL_VERSION;
        try {
            switch (service.getDialectTracker().dialectOf(player.getUUID())) {
                case V16 -> {
                    dialect = "v16";
                    proto = LSSConstants.V16_COMPAT_PROTOCOL_VERSION;
                }
                case V18 -> {
                    dialect = "v18";
                    proto = LSSConstants.V18_COMPAT_PROTOCOL_VERSION;
                }
                case V19 -> {
                    dialect = "v19";
                    proto = LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
                }
                case CURRENT -> { }
            }
        } catch (Throwable ignored) {
            // Dialect stays absent; proto stays current — absent, not wrong (§0.5).
        }
        long sinceS = Math.max(0, (System.currentTimeMillis() - state.getCreatedAtMillis()) / 1000);
        return new MoveRow.LssBlock(sinceS, state.getCapabilities(), proto, dialect,
                state.getSendQueueSize(), state.getTotalBytesSent(), state.getYieldedTicks());
    }

    static double speedBlocksPerSecond(ServerPlayer player) {
        return player.getDeltaMovement().length() * 20.0;
    }
}
