package dev.vox.lss.trace;

import dev.vox.lss.mixin.trace.AccessorServerGamePacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.AABB;

/**
 * Static hook bodies for {@code MovementRejectHook} (move-desync-tracer-plan.md §1.2) —
 * the MC-typed capture layer over the {@link MoveEventMath}/{@link MoveRow} pure core.
 * Every body is wrapped in a catch-all: diagnostic code must never take the server down
 * (§0 constraint 4). A {@code ClassCastException} from the accessor cast (the non-required
 * mixin config did not apply) lands in the same catch-all and the row simply never forms.
 *
 * <p>All entry points run on the server thread: the mixin injects AFTER
 * {@code ensureRunningOnSameThread} (review A-1 — a HEAD inject would run on the netty
 * event loop first), so per-player state needs no synchronization beyond the registry map.
 */
public final class MoveDesyncHooks {

    /** Beyond this residual, the entity-collision sweep and stop-block sample are skipped
     *  (keys absent): a hostile claim's swept AABB would otherwise walk millions of
     *  entity-section columns on the server thread (review A-7). */
    static final double COLLISION_SAMPLE_MAX_RESIDUAL = 512.0;

    private MoveDesyncHooks() {}

    /** Post-ensure entry of every {@code handleMovePlayer}: the per-player gap clock
     *  (review U-5 — the only server-side client-stall measurement, live in the LOD-off
     *  control arms too). */
    public static void onMoveHead(ServerGamePacketListenerImpl listener) {
        try {
            MoveTraceTelemetry.onMovePacket(listener.player);
        } catch (Throwable t) {
            MoveTraceTelemetry.swallowed(t);
        }
    }

    /** The "moved too quickly!" warn site — BEFORE {@code move()} runs (review U-14: no
     *  simulated stop exists here). Reconstructs the check's exact inputs, including both
     *  the raw and the post-clamp packet counts (review F-8: a >5 burst is penalized to 1,
     *  and an analyst given only the raw count computes the wrong threshold). */
    public static void onMovedTooQuickly(ServerGamePacketListenerImpl listener,
                                         ServerboundMovePlayerPacket packet,
                                         double startX, double startY, double startZ,
                                         boolean startCaptured) {
        try {
            var player = listener.player;
            var acc = (AccessorServerGamePacketListener) listener;
            // The recompute default is the HEAD-captured pre-move position (exact for
            // Rot/StatusOnly packets — review F-5); if the HEAD inject never applied,
            // fall back to the current position (identical here: move() has not run).
            double defX = startCaptured ? startX : player.getX();
            double defY = startCaptured ? startY : player.getY();
            double defZ = startCaptured ? startZ : player.getZ();
            double claimedX = MoveEventMath.clampHorizontal(packet.getX(defX));
            double claimedY = MoveEventMath.clampVertical(packet.getY(defY));
            double claimedZ = MoveEventMath.clampHorizontal(packet.getZ(defZ));
            // origin for too_quickly is firstGood* — the cumulative check's own anchor
            // (review U-4: without it the swept segment is unreconstructable).
            double[] origin = {acc.lss$firstGoodX(), acc.lss$firstGoodY(), acc.lss$firstGoodZ()};
            int rawDelta = acc.lss$receivedMovePacketCount() - acc.lss$knownMovePacketCount();
            int usedDelta = MoveEventMath.usedDeltaPackets(rawDelta);
            double expectedDistSq = player.getDeltaMovement().lengthSqr();
            var tracer = MoveDesyncTracer.active();
            if (tracer == null) return;
            var state = MoveTraceTelemetry.stateFor(player);
            MoveTraceTelemetry.flushRingBeforeEvent(tracer, player, state);
            var claimedState = ChunkSendState.capture(player,
                    MoveEventMath.chunkCoord(claimedX), MoveEventMath.chunkCoord(claimedZ));
            long now = System.currentTimeMillis();
            tracer.countEvent(MoveRow.TYPE_TOO_QUICKLY, false);
            tracer.emit(MoveRow.tooQuickly(
                    MoveTraceTelemetry.envelope(tracer, player, now),
                    MoveTraceTelemetry.lssRegistered(player), MoveTraceTelemetry.lssBlock(player),
                    origin, new double[] {claimedX, claimedY, claimedZ},
                    player.isFallFlying(), MoveTraceTelemetry.speedBlocksPerSecond(player),
                    state.gapClock().lastGapMs(), state.gapClock().maxGapWindowMs(now),
                    rawDelta, usedDelta, expectedDistSq, claimedState));
            state.armFromEvent(now);
        } catch (Throwable t) {
            MoveTraceTelemetry.swallowed(t);
        }
    }

    /** The "moved wrongly!" warn site — {@code move()} has run, so the player's position
     *  IS the post-sweep simulated stop (bytecode-verified). */
    public static void onMovedWrongly(ServerGamePacketListenerImpl listener,
                                      ServerboundMovePlayerPacket packet,
                                      double startX, double startY, double startZ,
                                      boolean startCaptured) {
        try {
            emitCollisionEvent(MoveRow.TYPE_WRONGLY, listener, packet, null,
                    startX, startY, startZ, startCaptured);
        } catch (Throwable t) {
            MoveTraceTelemetry.swallowed(t);
        }
    }

    /** The rejection teleport — fires for BOTH the logged rejection and the silent
     *  {@code isEntityCollidingWithAnythingNew} rejection (zero observability today). */
    public static void onMoveRejected(ServerGamePacketListenerImpl listener,
                                      ServerboundMovePlayerPacket packet,
                                      boolean loggedWrongly,
                                      double startX, double startY, double startZ,
                                      boolean startCaptured) {
        try {
            emitCollisionEvent(MoveRow.TYPE_REJECTED, listener, packet, loggedWrongly,
                    startX, startY, startZ, startCaptured);
        } catch (Throwable t) {
            MoveTraceTelemetry.swallowed(t);
        }
    }

    private static void emitCollisionEvent(String type, ServerGamePacketListenerImpl listener,
                                           ServerboundMovePlayerPacket packet,
                                           Boolean loggedWrongly,
                                           double startX, double startY, double startZ,
                                           boolean startCaptured) {
        var tracer = MoveDesyncTracer.active();
        if (tracer == null) return;
        var player = listener.player;
        var acc = (AccessorServerGamePacketListener) listener;
        // At these sites the player position is the post-move() simulated stop; on the
        // rejected path nothing has snapped it back yet (the teleport is what does). The
        // claim recompute default therefore uses the HEAD capture when available — the
        // current position is NOT the pre-move position any more.
        double simX = player.getX();
        double simY = player.getY();
        double simZ = player.getZ();
        double defX = startCaptured ? startX : simX;
        double defY = startCaptured ? startY : simY;
        double defZ = startCaptured ? startZ : simZ;
        double claimedX = MoveEventMath.clampHorizontal(packet.getX(defX));
        double claimedY = MoveEventMath.clampVertical(packet.getY(defY));
        double claimedZ = MoveEventMath.clampHorizontal(packet.getZ(defZ));
        // origin for wrongly/rejected is lastGood* — the segment vanilla actually swept.
        double[] origin = {acc.lss$lastGoodX(), acc.lss$lastGoodY(), acc.lss$lastGoodZ()};
        double rx = claimedX - simX;
        double ry = claimedY - simY;
        double rz = claimedZ - simZ;
        double residual = MoveEventMath.residual(rx, ry, rz);

        var state = MoveTraceTelemetry.stateFor(player);
        MoveTraceTelemetry.flushRingBeforeEvent(tracer, player, state);

        Boolean entityCollide = null;
        String stopBlock = null;
        if (residual <= COLLISION_SAMPLE_MAX_RESIDUAL) {
            try {
                var level = player.level();
                AABB atSim = player.getBoundingBox();
                AABB swept = atSim.minmax(atSim.move(rx, ry, rz));
                entityCollide = !level.getEntityCollisions(player, swept).isEmpty();
                double halfWidth = (atSim.maxX - atSim.minX) / 2.0;
                double height = atSim.maxY - atSim.minY;
                double[] sample = MoveEventMath.stopBlockSamplePoint(simX, simY, simZ,
                        rx, ry, rz, halfWidth, height);
                var blockState = level.getBlockState(BlockPos.containing(sample[0], sample[1], sample[2]));
                stopBlock = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
            } catch (Throwable ignored) {
                // Absent, not zeroed (§0 constraint 5) — the row still carries the geometry.
            }
        }

        var simState = ChunkSendState.capture(player,
                MoveEventMath.chunkCoord(simX), MoveEventMath.chunkCoord(simZ));
        var claimedState = ChunkSendState.capture(player,
                MoveEventMath.chunkCoord(claimedX), MoveEventMath.chunkCoord(claimedZ));
        long now = System.currentTimeMillis();
        tracer.countEvent(type, loggedWrongly != null && loggedWrongly);
        tracer.emit(MoveRow.collisionEvent(type,
                MoveTraceTelemetry.envelope(tracer, player, now),
                MoveTraceTelemetry.lssRegistered(player), MoveTraceTelemetry.lssBlock(player),
                origin, new double[] {claimedX, claimedY, claimedZ},
                player.isFallFlying(), MoveTraceTelemetry.speedBlocksPerSecond(player),
                state.gapClock().lastGapMs(), state.gapClock().maxGapWindowMs(now),
                new double[] {simX, simY, simZ},
                residual, MoveEventMath.residualHorizontal(rx, rz),
                startCaptured ? new double[] {startX, startY, startZ} : null,
                loggedWrongly, entityCollide, stopBlock, simState, claimedState));
        state.armFromEvent(now);
    }
}
