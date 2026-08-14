package dev.vox.lss.networking.client;

import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * NeoForge client-side networking — the fabric {@code LSSClientNetworking}
 * twin (same FQN, per-loader tree). <b>N-2 state: the compile-surface twin
 * only</b> — the static surface xplat compiles against (the benchmark exporter,
 * LodRequestManager's counter suppliers, LSSApi's ingest-failure sink) with
 * inert values, and no-op payload handlers so the registrar binds. N-3 wires
 * the real client session (the fabric class's session state is the template).
 * A NeoForge SERVER build never executes any of this; on a NeoForge client the
 * inert surface means "no LOD session" — exactly the plan §0.1
 * compiled-and-inert posture until the client half lands.
 */
public class LSSClientNetworking {

    public static boolean isServerEnabled() {
        return false;
    }

    public static int getSessionVersion() {
        return 0;
    }

    public static boolean hasReceivedSessionConfig() {
        return false;
    }

    public static int getServerLodDistance() {
        return 0;
    }

    public static long getColumnsReceived() {
        return 0L;
    }

    public static long getBytesReceived() {
        return 0L;
    }

    public static long getWireBytesReceived() {
        return 0L;
    }

    public static long getColumnsDropped() {
        return 0L;
    }

    public static long getConnectionStartMs() {
        return 0L;
    }

    public static LodRequestManager getRequestManager() {
        return null;
    }

    public static int getQueuedColumnCount() {
        return 0;
    }

    public static long getQueuedColumnBytes() {
        return 0L;
    }

    /** LSSApi's ingest-failure sink — inert until N-3 (no session to forget stamps in). */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    }

    /** ClientColumnProcessor's silent-clear report path — inert until N-3. */
    static void reportUndispatchedColumns(LodRequestManager manager) {
    }

    /** The fabric LAN path's host handshake — no LAN hook on NeoForge v1. */
    public static void triggerHostHandshake() {
    }

    // ---- Payload handlers (bound by the registrar; no-ops until N-3) ----

    public static void handleSessionConfigPayload(SessionConfigS2CPayload payload,
                                                  IPayloadContext context) {
    }

    public static void handleBatchResponsePayload(BatchResponseS2CPayload payload,
                                                  IPayloadContext context) {
    }

    public static void handleDirtyColumnsPayload(DirtyColumnsS2CPayload payload,
                                                 IPayloadContext context) {
    }

    public static void handleVoxelColumnPayload(VoxelColumnS2CPayload payload,
                                                IPayloadContext context) {
    }

    public static void handleFarPlayerRosterPayload(FarPlayerRosterS2CPayload payload,
                                                    IPayloadContext context) {
    }

    public static void handleFarPlayerUpdatesPayload(FarPlayerUpdatesS2CPayload payload,
                                                     IPayloadContext context) {
    }
}
