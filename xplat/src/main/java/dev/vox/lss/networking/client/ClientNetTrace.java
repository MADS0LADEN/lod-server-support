package dev.vox.lss.networking.client;

import dev.vox.lss.mixin.AccessorClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.util.Util;

/**
 * The transport instrument: one {@code net} trace event per second carrying VANILLA's own
 * view of the connection alongside LSS's, so the elytra chunk-wall's three candidate causes
 * can be told apart from inside the client. See
 * docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8 — H1 (LSS's payloads
 * head-of-line-blocking vanilla's chunk packets in the shared netty outbound buffer),
 * H2 (real path congestion), H3 (the client cannot drain the socket). All three look
 * identical from the server, which is why this lives on the client.
 *
 * <p>Fields and what each one settles:
 * <ul>
 *   <li>{@code ping} — application-level RTT in ms <b>on the same TCP connection</b> the LOD
 *       stream uses. Inflation here is the queueing-delay signature shared by H1/H2/H3, and
 *       it is what makes vanilla's chunk delivery late. Vanilla only SENDS its own ping while
 *       the F3 network charts are open, so this class sends its own
 *       {@link ServerboundPingRequestPacket} once per second; vanilla's pong handler logs the
 *       sample unconditionally, so no F3 and no extra mixin is needed.</li>
 *   <li>{@code dcpt} — vanilla's {@code ChunkBatchSizeCalculator.getDesiredChunksPerTick()}:
 *       the client's self-measured chunk-apply capacity. Collapses under H3, stays high under
 *       H1/H2. <b>The decisive cell in the §8.1 table.</b></li>
 *   <li>{@code runway} / {@code runway_s} — loaded vanilla chunks ahead along the velocity
 *       vector before the first hole, and how many seconds that is at current speed. Turns
 *       "I hit the wall" into a continuous number with a timestamp, so onset can be aligned
 *       against the others instead of depending on the player noticing.</li>
 *   <li>{@code miss_view} / {@code loaded} — missing vanilla chunks inside render distance,
 *       and vanilla's loaded-chunk count. Context for the runway number.</li>
 *   <li>{@code wire_bps} / {@code raw_bps} — LSS column bytes as SHIPPED (post-zstd, what the
 *       socket carries) vs as RAW (what the client must decode). Their ratio is the live
 *       version of the counted-vs-wire confusion that cost the first investigation a round,
 *       and §8.8's cap-ladder needs the knee measured in both units.</li>
 *   <li>{@code fps}, {@code q}/{@code qb}/{@code ingest}/{@code inflight}/{@code spd} —
 *       client render load, LSS decode-queue depth and bytes, consumer ingest backlog,
 *       awaiting-answer set, player speed.</li>
 * </ul>
 *
 * <p>Everything degrades to {@code -1} rather than throwing: a diagnostic must never be able
 * to take the client down, and the vanilla surfaces it reads are private fields reached
 * through a mixin accessor that a future MC version could move.
 */
final class ClientNetTrace {

    private static final long INTERVAL_MS = 1000;
    /** Below this speed (blocks/tick) there is no meaningful heading, so no runway. */
    private static final double MIN_SPEED_FOR_RUNWAY = 0.05;

    private static long nextEmitMs;
    private static long lastWireBytes;
    private static long lastRawBytes;
    private static long lastSampleMs;

    private ClientNetTrace() {}

    /** Forget the rate baselines — a new trace must not report a rate spanning the gap. */
    static void reset() {
        nextEmitMs = 0;
        lastSampleMs = 0;
    }

    /**
     * Emit at most one {@code net} event per {@link #INTERVAL_MS}. Caller must have checked
     * {@link ClientTraceLog#enabled()}. Main client thread only.
     */
    static void maybeEmit(Minecraft mc, LocalPlayer player, ClientLevel level, int viewDistance,
                          int queueSize, long queueBytes, int ingestBacklog, int inFlight,
                          long governedBps, int governedCols, double rttP50, double rttP95) {
        long now = System.currentTimeMillis();
        if (now < nextEmitMs) return;
        nextEmitMs = now + INTERVAL_MS;

        // Both counters live on the session gate and RESET on a new session — a negative
        // delta means a reconnect crossed this window, not a rate.
        long wire = LSSClientNetworking.getWireBytesReceived();
        long raw = LSSClientNetworking.getBytesReceived();
        long elapsed = lastSampleMs == 0 ? 0 : now - lastSampleMs;
        long wireBps = elapsed > 0 && wire >= lastWireBytes
                ? (wire - lastWireBytes) * 1000L / elapsed : -1;
        long rawBps = elapsed > 0 && raw >= lastRawBytes
                ? (raw - lastRawBytes) * 1000L / elapsed : -1;
        lastWireBytes = wire;
        lastRawBytes = raw;
        lastSampleMs = now;

        var movement = player.getDeltaMovement();
        double speedPerTick = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        int runway = runwayChunks(level, player, speedPerTick, viewDistance);
        // Seconds of clear terrain ahead: chunks * 16 blocks, over blocks/second.
        String runwaySeconds = runway < 0 ? "-1"
                : String.format(java.util.Locale.ROOT, "%.2f", runway * 16.0 / (speedPerTick * 20.0));

        ClientTraceLog.event("net", "\"ping\":" + pingMillis(mc)
                + ",\"dcpt\":" + desiredChunksPerTick(mc)
                + ",\"runway\":" + runway
                + ",\"runway_s\":" + runwaySeconds
                + ",\"miss_view\":" + missingInView(level, player, viewDistance)
                + ",\"loaded\":" + loadedChunks(level)
                + ",\"wire_bps\":" + wireBps
                + ",\"raw_bps\":" + rawBps
                + ",\"fps\":" + mc.getFps()
                + ",\"q\":" + queueSize
                + ",\"qb\":" + queueBytes
                + ",\"ingest\":" + ingestBacklog
                + ",\"inflight\":" + inFlight
                + ",\"spd\":" + String.format(java.util.Locale.ROOT, "%.2f", speedPerTick * 20.0)
                // The transfer governor's state in the same timeline (live round 3's
                // instrument): gov=0 means capless (OPEN/DISABLED); nonzero is the
                // governed rate in EITHER RAMP or ENGAGED since join slow start —
                // read the diag label for the phase; rtt_* is LSS's own
                // declaration->first-answer RTT (1-4 Hz sampled — the highest-frequency
                // latency probe on a warm store, where server processing is ~20 us).
                + ",\"gov\":" + governedBps
                + ",\"gov_r\":" + governedCols
                + ",\"rtt_p50\":" + String.format(java.util.Locale.ROOT, "%.1f", rttP50)
                + ",\"rtt_p95\":" + String.format(java.util.Locale.ROOT, "%.1f", rttP95));

        sendPingProbe(mc);
    }

    /**
     * Vanilla's own ping/pong on the shared connection. The pong handler
     * ({@code ClientPacketListener.handlePongResponse}) logs into the debug overlay's ping
     * logger unconditionally — only the SENDING side is gated on the F3 network charts — so
     * driving the request here gives a 1 Hz RTT series with the charts closed.
     */
    private static void sendPingProbe(Minecraft mc) {
        try {
            var connection = mc.getConnection();
            if (connection != null) {
                connection.send(new ServerboundPingRequestPacket(Util.getMillis()));
            }
        } catch (Exception e) {
            // A diagnostic probe is never worth a client-side failure.
        }
    }

    /** Newest sample from vanilla's ping logger, in ms; -1 when no pong has landed yet. */
    private static long pingMillis(Minecraft mc) {
        try {
            var logger = mc.getDebugOverlay().getPingLogger();
            int size = logger.size();
            return size == 0 ? -1 : logger.get(size - 1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Vanilla's self-measured chunk-apply capacity, chunks/tick; -1 if unreachable. */
    private static String desiredChunksPerTick(Minecraft mc) {
        try {
            var connection = mc.getConnection();
            if (connection == null) return "-1";
            var calculator = ((AccessorClientPacketListener) connection)
                    .lss$getChunkBatchSizeCalculator();
            return String.format(java.util.Locale.ROOT, "%.3f", calculator.getDesiredChunksPerTick());
        } catch (Exception | LinkageError e) {
            return "-1";
        }
    }

    /**
     * Loaded vanilla chunks ahead along the heading before the first hole, capped at the
     * render distance. -1 when the player is too slow for the heading to mean anything.
     * Marched in half-chunk steps so no chunk on the path is skipped.
     */
    private static int runwayChunks(ClientLevel level, LocalPlayer player,
                                    double speedPerTick, int viewDistance) {
        if (speedPerTick < MIN_SPEED_FOR_RUNWAY) return -1;
        try {
            var movement = player.getDeltaMovement();
            double dirX = movement.x / speedPerTick;
            double dirZ = movement.z / speedPerTick;
            var chunkSource = level.getChunkSource();
            int lastCx = SectionPos.blockToSectionCoord(player.getBlockX());
            int lastCz = SectionPos.blockToSectionCoord(player.getBlockZ());
            int runway = 0;
            for (int step = 1; step <= viewDistance * 2; step++) {
                double bx = player.getX() + dirX * (step * 8.0);
                double bz = player.getZ() + dirZ * (step * 8.0);
                int cx = SectionPos.blockToSectionCoord((int) Math.floor(bx));
                int cz = SectionPos.blockToSectionCoord((int) Math.floor(bz));
                if (cx == lastCx && cz == lastCz) continue;
                if (!chunkSource.hasChunk(cx, cz)) break;
                lastCx = cx;
                lastCz = cz;
                runway++;
            }
            return runway;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int missingInView(ClientLevel level, LocalPlayer player, int viewDistance) {
        try {
            var chunkSource = level.getChunkSource();
            int playerCx = SectionPos.blockToSectionCoord(player.getBlockX());
            int playerCz = SectionPos.blockToSectionCoord(player.getBlockZ());
            int missing = 0;
            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                    if (!chunkSource.hasChunk(playerCx + dx, playerCz + dz)) missing++;
                }
            }
            return missing;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int loadedChunks(ClientLevel level) {
        try {
            return level.getChunkSource().getLoadedChunksCount();
        } catch (Exception e) {
            return -1;
        }
    }
}
