package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ColumnBytes;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-specific off-thread processor. Produces CustomPacketPayload objects
 * that will be sent via Fabric networking on the main thread.
 */
public class FabricOffThreadProcessor extends OffThreadProcessor<PlayerRequestState> {
    private final ChunkDiskReader diskReader;

    // Stored references for disk read submission. Grows but never prunes — acceptable because
    // vanilla only has 3 permanent dimensions, and the map is cleared on shutdown.
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    // Cache parsed ResourceKeys to avoid Identifier.parse per column payload (3 entries for vanilla)
    private final ConcurrentHashMap<String, ResourceKey<Level>> dimensionKeyCache = new ConcurrentHashMap<>();

    // The session-dialect source for the C2 legacy egress translation (XVER §4.2, placed
    // at the ENQUEUE choke point — see buildAndEnqueueColumnPayload). Attached by the
    // service after construction; null (test rigs) = every session CURRENT. Volatile:
    // written main-thread at service init, read on the processing thread.
    private volatile dev.vox.lss.common.compat.WireDialectTracker dialects;
    /** Warn-once latch for legacy egress translation failures (processing thread only). */
    private boolean legacyTranslateWarned;

    public void attachDialectTracker(dev.vox.lss.common.compat.WireDialectTracker dialects) {
        this.dialects = dialects;
    }

    public FabricOffThreadProcessor(Map<UUID, PlayerRequestState> players,
                                     ChunkDiskReader diskReader,
                                     boolean generationAvailable,
                                     Path dataDir, int perDimensionTimestampCacheSizeMB,
                                     int missMemoTtlSeconds) {
        super(players, diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds);
        this.diskReader = diskReader;
    }

    public FabricOffThreadProcessor(Map<UUID, PlayerRequestState> players,
                                     ChunkDiskReader diskReader,
                                     boolean generationAvailable,
                                     Path dataDir, int perDimensionTimestampCacheSizeMB,
                                     int missMemoTtlSeconds, int diskReadDoneSweepRadiusChunks) {
        super(players, diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds, diskReadDoneSweepRadiusChunks);
        this.diskReader = diskReader;
    }

    /** Register a dimension context for disk read submission (called from main thread). */
    public void updateDimensionContext(String dimension, ServerLevel level) {
        // put, not putIfAbsent: refresh to the current ServerLevel so a recreated dimension
        // (e.g. an integrated world re-published to LAN) doesn't leave a stale level cached.
        // Matches PaperOffThreadProcessor.updateDimensionContext so the twins can't drift.
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
    protected boolean buildAndEnqueueColumnPayload(PlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    ColumnBytes bytes, int estimatedBytes,
                                                    byte source) {
        // The guard checks RAW size (the client decode cap is raw-denominated; a codec-1
        // frame is strictly smaller than its raw or the holder refuses it — INCLUDING
        // pre-built store frames, whose non-shrinking degenerates ship raw) — load-bearing
        // for store-frame hits too, whose rows can legally exceed the send cap (plan §3).
        if (bytes.rawSize() > LSSConstants.MAX_SEND_SECTIONS_SIZE) {
            LSSLogger.warn("Dropping oversized column [" + cx + ", " + cz + "] in " + dimension
                    + ": " + bytes.rawSize() + " bytes exceeds send limit "
                    + LSSConstants.MAX_SEND_SECTIONS_SIZE + " (netty frame cap would kill the connection)");
            return false;
        }
        if (dimension.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) {
            // Drop just this column (like an oversized one) rather than letting writeUtf throw
            // at send time and nuke the whole send queue. No real dimension id is this long;
            // the !sent path answers the client up-to-date so it stops asking.
            LSSLogger.warn("Dropping column [" + cx + ", " + cz + "] with oversized dimension id ("
                    + dimension.length() + " chars > " + LSSConstants.MAX_DIMENSION_STRING_LENGTH + ")");
            return false;
        }
        var dimensionKey = this.dimensionKeyCache.computeIfAbsent(dimension,
                d -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(d)));

        // C2 legacy egress translation (XVER §4.2), placed at THIS per-recipient choke
        // point rather than the flush seam so every downstream size — queue gauges,
        // bandwidth budget, diag books, soak law A2 — derives from the bytes the legacy
        // client actually decodes (the first dialect-19 soak redded A2 by exactly the
        // v20-vs-native rawSize delta when translation ran at flush), and the CPU lands
        // on the processing thread instead of main. The flush ladder keeps only the
        // v18/v16 HEADER splices. A dialect flip between enqueue and flush ships one
        // queue of wrong-layout bodies — client decode fails, ingest-failure re-declares,
        // the retranslated serves heal it (the same bounded window as the codec-1
        // downgrade guard at the splices).
        var dialectTracker = this.dialects;
        boolean legacySession = dialectTracker != null && dialectTracker.dialectOf(
                state.getPlayerUUID()) != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT;
        if (legacySession) {
            var level = this.dimensionLevelMap.get(dimension);
            LegacyColumnBuild build;
            try {
                if (level == null) {
                    throw new IllegalStateException("no dimension context for " + dimension);
                }
                build = buildLegacyColumn(bytes.raw(), level.registryAccess(),
                        state.wantsCompressedColumns(), wireCodec());
            } catch (Exception e) {
                if (!this.legacyTranslateWarned) {
                    this.legacyTranslateWarned = true;
                    LSSLogger.error("legacy-compat: column body translation failed for "
                            + state.getPlayerName() + " — resolving up_to_date so the client "
                            + "keeps what it has (a persistent failure here is a registry-table "
                            + "bug; further failures are silent)", e);
                }
                // false = the oversized-column semantics: the caller answers up_to_date,
                // never a fabricated clear.
                return false;
            }
            var legacyPayload = new VoxelColumnS2CPayload(cx, cz, dimensionKey,
                    columnTimestamp, source, build.codecTag(), build.shipped(), build.rawSize());
            state.addReadyPayload(new QueuedPayload<>(legacyPayload,
                    build.rawSize() + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    build.shipped().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    submissionOrder, PositionUtil.packPosition(cx, cz)));
            getDiagnostics().incrementColumnCodec(build.codecTag() == LSSConstants.COLUMN_CODEC_ZSTD);
            if (SoakProbeBridge.armed()) SoakProbeBridge.recordServed(cx, cz, bytes.raw());
            return true;
        }

        // Per-recipient codec choice off the shared holder (plan §0.3/§0.4): frame() is
        // asked only for capable sessions and memoizes across the dedup fan-out; null
        // means "ship raw" (no codec, below threshold, or the frame didn't shrink).
        byte[] frame = state.wantsCompressedColumns() ? bytes.frame() : null;
        byte codecTag = frame != null ? LSSConstants.COLUMN_CODEC_ZSTD
                : LSSConstants.COLUMN_CODEC_RAW;
        byte[] shipped = frame != null ? frame : bytes.raw();
        var payload = new VoxelColumnS2CPayload(cx, cz, dimensionKey, columnTimestamp,
                source, codecTag, shipped, bytes.rawSize());
        int wireBytes = shipped.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        state.addReadyPayload(new QueuedPayload<>(payload, estimatedBytes, wireBytes,
                submissionOrder, PositionUtil.packPosition(cx, cz)));
        getDiagnostics().incrementColumnCodec(frame != null);
        // Soak probe hashes (dev-only, no-op unless -Dlss.soak.probes): the RAW bytes —
        // pinned (plan §0.6): probes compare CONTENT across serve legs, and the armed()
        // gate keeps the unarmed production path from ever materializing raw for it.
        if (SoakProbeBridge.armed()) SoakProbeBridge.recordServed(cx, cz, bytes.raw());
        return true;
    }

    /** The translated-body build for one legacy recipient: shipped bytes + codec tag +
     *  the re-derived rawSize (the legacy client's charge rule reads the bytes IT
     *  receives — booking the v20 sizes was the first dialect-19 soak's A2 red). */
    record LegacyColumnBuild(byte[] shipped, byte codecTag, int rawSize) {}

    /** Translate a v20 raw body for a legacy session and choose its codec: v19 sessions
     *  keep their compression capability (recompress, shrink-gated like the shared
     *  holder's frame()); v18/v16 sessions arrive forced-RAW. Throws on any
     *  malformed/unresolvable body — the caller contains it. */
    static LegacyColumnBuild buildLegacyColumn(byte[] v20Raw,
                                               net.minecraft.core.RegistryAccess registryAccess,
                                               boolean wantsCompressed,
                                               dev.vox.lss.common.store.StoreCodec zstd) {
        byte[] nativeBody = NbtSectionSerializer.fromV20(v20Raw, registryAccess);
        if (wantsCompressed && zstd != null) {
            byte[] frame = zstd.compress(nativeBody);
            if (frame.length < nativeBody.length) {
                return new LegacyColumnBuild(frame, LSSConstants.COLUMN_CODEC_ZSTD,
                        nativeBody.length);
            }
        }
        return new LegacyColumnBuild(nativeBody, LSSConstants.COLUMN_CODEC_RAW,
                nativeBody.length);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
