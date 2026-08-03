package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ColumnBytes;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-specific off-thread processor. Produces encoded byte[] payloads
 * that will be sent via Plugin Messaging on the main thread.
 */
public class PaperOffThreadProcessor extends OffThreadProcessor<PaperPlayerRequestState> {
    private final PaperChunkDiskReader diskReader;

    // Maps a dimension id to its live ServerLevel for disk-read submission. Refreshed every
    // tick (put, not putIfAbsent): a Paper world unloaded and recreated under the same name
    // (Multiverse/arena resets) reuses the dimension id, so a stale putIfAbsent entry would
    // aim every disk read at the dead world's closed ChunkMap (mass not-found). Cleared on
    // shutdown. (Residual: a permanently-unloaded, never-recreated world's level stays until
    // shutdown — bounded, and vanilla dimensions never unload.)
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    boolean generationAvailable,
                                    Path dataDir, int perDimensionTimestampCacheSizeMB,
                                    int missMemoTtlSeconds) {
        super(players,
                diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds);
        this.diskReader = diskReader;
    }

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    boolean generationAvailable,
                                    Path dataDir, int perDimensionTimestampCacheSizeMB,
                                    int missMemoTtlSeconds, int diskReadDoneSweepRadiusChunks) {
        super(players,
                diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds, diskReadDoneSweepRadiusChunks);
        this.diskReader = diskReader;
    }

    public void updateDimensionContext(String dimension, ServerLevel level) {
        this.dimensionLevelMap.put(dimension, level);
    }

    @Override
    protected boolean submitDiskRead(UUID playerUuid, String dimension,
                                    int cx, int cz,
                                    long submissionOrder) {
        if (this.diskReader == null) return false;
        var level = this.dimensionLevelMap.get(dimension);
        if (level == null) {
            LSSLogger.debug("No dimension context for " + dimension + ", skipping disk read for " + cx + "," + cz);
            return false;
        }
        this.diskReader.submitReadDirect(playerUuid, dimension, level,
                cx, cz, submissionOrder);
        return true;
    }

    @Override
    protected boolean buildAndEnqueueColumnPayload(PaperPlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    ColumnBytes bytes, int estimatedBytes,
                                                    byte source) {
        // RAW-size guard (twin of the Fabric build; load-bearing for store-frame hits
        // whose rows can legally exceed the send cap — plan §3).
        if (bytes.rawSize() > LSSConstants.MAX_SEND_SECTIONS_SIZE) {
            LSSLogger.warn("Dropping oversized column [" + cx + ", " + cz + "] in " + dimension
                    + ": " + bytes.rawSize() + " bytes exceeds send limit "
                    + LSSConstants.MAX_SEND_SECTIONS_SIZE + " (netty frame cap would kill the connection)");
            return false;
        }
        if (dimension.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) {
            // Drop just this column (like an oversized one): without the guard
            // encodeVoxelColumnPreEncoded's writeUtf throws out of this method and aborts the
            // WHOLE processing cycle. No real dimension id is this long; the !sent path answers
            // the client up-to-date so it stops asking.
            LSSLogger.warn("Dropping column [" + cx + ", " + cz + "] with oversized dimension id ("
                    + dimension.length() + " chars > " + LSSConstants.MAX_DIMENSION_STRING_LENGTH + ")");
            return false;
        }
        // Per-recipient codec choice off the shared holder — twin of the Fabric build:
        // frame() only for capable sessions, memoized across the dedup fan-out; a v16
        // session's flag is derived false at registration, so its frames encode raw and
        // the egress splice stays two-byte-removable.
        byte[] frame = state.wantsCompressedColumns() ? bytes.frame() : null;
        byte codecTag = frame != null ? LSSConstants.COLUMN_CODEC_ZSTD
                : LSSConstants.COLUMN_CODEC_RAW;
        byte[] shipped = frame != null ? frame : bytes.raw();
        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                cx, cz, dimension, columnTimestamp, source, codecTag, shipped);
        int wireBytes = shipped.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        state.addReadyPayload(new QueuedPayload<>(encoded, estimatedBytes, wireBytes,
                submissionOrder, PositionUtil.packPosition(cx, cz)));
        getDiagnostics().incrementColumnCodec(frame != null);
        // Soak probe hashes (dev-only, no-op unless -Dlss.soak.probes): the RAW bytes —
        // pinned (plan §0.6); the armed() gate keeps the unarmed production path from
        // materializing raw for it. Twin of the Fabric hook.
        if (PaperSoakProbeBridge.armed()) PaperSoakProbeBridge.recordServed(cx, cz, bytes.raw());
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
