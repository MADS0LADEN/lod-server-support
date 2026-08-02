package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.XrayMaskPolicy;

import java.util.List;

/**
 * The server config shared verbatim by Fabric and Paper: same fields, same defaults,
 * same clamps, same file name. Platform subclasses add only platform-specific options
 * (Paper's updateEvents) and the config-directory resolution.
 */
public abstract class ServerConfigBase extends JsonConfig {
    protected static final String FILE_NAME = "lss-server-config.json";

    public boolean enabled = true;
    public int lodDistanceChunks = 256;
    public int bytesPerSecondLimitPerPlayer = 20_971_520;
    public int diskReaderThreads = 5;
    /**
     * Per-player send-queue cap. The default is the wire batch cap: under v17 replace
     * semantics a player's backlog is at most ONE wire batch, and a payload only enqueues
     * for an admitted backlog position, so enqueued payloads per player structurally
     * cannot exceed MAX_BATCH_CHUNK_REQUESTS — at this default the router's queue gate is
     * unreachable for any legal client (compliant v17 clients declare WANT_SET_BUDGET=800,
     * the v16 shim's synthetic want-set caps at the same 800) and the queue bounds
     * worst-case buffered-payload RAM at ~one batch per player. Deliberately still config:
     * ops can shrink it (the gated regime measured harmless — same throughput/CPU,
     * exactly-once reads; docs/planning/disk-read-profile-2026-07-29.md) or raise it for
     * future multi-batch shapes.
     */
    public int sendQueueLimitPerPlayer = LSSConstants.MAX_BATCH_CHUNK_REQUESTS;
    /**
     * Transport deference (docs/planning/flight-cadence-and-transport-backpressure-plan.md
     * §11.4): when a player's network outbound buffer holds more than this many KB, LSS
     * skips that tick's column flush and retains its queue, so LSS payloads stop deepening
     * the head-of-line delay ahead of vanilla's own chunk packets on the shared channel.
     *
     * <p><b>Default 0 = OFF, deliberately.</b> The elytra-wall investigation measured this
     * mechanism ABSENT — flat 20–26 ms ping across a full flight is a direct and sensitive
     * probe of shared-queue depth, and the server's send queue read empty throughout. The
     * gate ships correct and tested so it can be armed the moment {@code obuf_hw} in
     * {@code /lsslod diag} shows a real buffer building, and so that arming it does not
     * confound the adaptive-cadence A/B it was written alongside. Nonzero {@code deferred=}
     * on a healthy link is a red flag (the retired movement-cadence-debounce failure mode
     * was "LOD silently stops during fast travel"), not the gate doing its job.
     */
    public int outboundBufferCeilingKB = 0;
    public int bytesPerSecondLimitGlobal = 104_857_600;
    public boolean enableChunkGeneration = true;
    public int generationConcurrencyLimitGlobal = 32;
    public int generationTimeoutSeconds = 60;
    public int dirtyBroadcastIntervalSeconds = 10;
    // The per-player SYNC (disk-read) slot cap is NOT config anymore — see
    // LSSConstants.SYNC_ON_LOAD_SLOT_CAP (shadowed by the disk-pool headroom gate at the
    // default pool size; a fixed fairness ceiling above it). The generation caps stay
    // config: they are the real worldgen limiters, but they are server-internal (off the
    // wire since the server-owned-generation fold into v17).
    public int generationConcurrencyLimitPerPlayer = 16;
    public int perDimensionTimestampCacheSizeMB = 32;
    /**
     * Miss-memo TTL (docs/planning/miss-memo-design.md): an authoritative disk miss is
     * remembered for this many seconds, so a position waiting for a generation slot skips
     * the redundant not-found re-reads (arriving at the client's scan cadence — 1 Hz, up
     * to 4 Hz under its adaptive fast re-scan) and falls through to the generation
     * decision directly. 0 disables the memo (the kill switch — restores the pre-memo
     * re-read churn, which remains fully correct behavior).
     */
    public int missMemoTtlSeconds = 30;
    /**
     * When true (default), LSS disk reads yield to vanilla/gameplay chunk loading: Fabric
     * schedules them at IOWorker BACKGROUND priority; Paper/Folia route them through Moonrise
     * at Priority.LOW. Set false to restore FOREGROUND reads (the pre-0.7 behavior) as a
     * rollback. No clamp: a boolean has no out-of-range value.
     */
    public boolean useBackgroundReadPriority = true;
    /**
     * When true (default), disk-read column serving transcodes region NBT straight into
     * wire bytes — palette ids and bit-storage longs copied verbatim off the NBT — instead
     * of decoding every section into PalettedContainer objects and re-serializing them
     * (docs/planning/nbt-transcode-design.md). Byte-identical output (golden corpus +
     * live/disk parity gates); exotic shapes (>256-entry block palettes, >8-entry biome
     * palettes, malformed data, x-ray-mask-needing sections) fall back per section to the
     * object path automatically. Set false to force EVERY section through the object path
     * (the pre-round-2 behavior) as a rollback. No clamp: a boolean has no out-of-range
     * value.
     */
    public boolean useNbtTranscode = true;
    /**
     * When true (default), columns for capability-declaring protocol-19 clients ship as
     * zstd-1 frames end-to-end (docs/planning/compressed-columns-design.md): store hits
     * ship their stored frame verbatim, live/disk/generation serves compress once on the
     * processing thread — removing the netty deflate over raw bytes (~520 us/col on store
     * hits, Phase 0) at the cost of ~+12% wire bytes. Requires the server-side zstd
     * native (probed at service start; unavailable => raw for everyone, one warning).
     * Set false as the rollback lever: codec 0 for everyone, capability ignored. No
     * clamp: a boolean has no out-of-range value.
     */
    public boolean useCompressedColumns = true;
    /**
     * When true (default), clients running the legacy protocol-16 mod (v0.6.x) get a
     * translated LOD session through the v16 compat shim (docs/planning/v16-compat-design.md)
     * instead of the silent version-mismatch no-session. Inert for current-protocol clients;
     * set false as the kill switch to restore the strict version gate. No clamp: boolean.
     */
    public boolean enableV16Compat = true;
    /**
     * The LOD store switch (docs/planning/lod-store-implementation-plan.md):
     * "off" (default — no store; the kill switch every store gate A/Bs against),
     * "memory" (bounded in-memory tier only), "full" (memory + SQLite disk store).
     * Unknown values normalize to "off" — the SAFE value, deliberately unlike
     * xrayObfuscation's normalize-to-auto: a typo must never enable a storage engine.
     */
    public String lodStore = "off";
    /**
     * Memory-tier size cap for the LOD store (compressed resident bytes), used by both
     * the "memory" and "full" modes. The Phase 1 gate measures the hit-rate curve
     * against this cap; scan-workload hit rate ≈ cap / working-set (random eviction).
     */
    public int lodStoreMemoryMB = 64;
    /**
     * Periodic LOD-store freshness re-sweep (seconds; 0 = off). This is PAPER's stale
     * bound: its dirty detection is event-driven with documented unfired-event gaps
     * (e.g. walk-in generation without ChunkPopulateEvent opted in), so the store
     * re-checks region mtimes/header stamps on this cadence — staleness is bounded by
     * ≈ one autosave + one sweep. PaperConfig overrides the default to 300; the shared
     * default stays 0 for Fabric, whose content-hash dirty pipeline invalidates store
     * rows at runtime (a periodic resweep there would only churn-drop rows on
     * metadata-only re-saves) and whose startup sweep covers offline edits.
     */
    public int lodStoreResweepSeconds = 0;
    /**
     * Opt-in LOD-store background backfill (Phase 4, docs/planning/
     * lod-store-implementation-plan.md): when true AND lodStore=full, a MIN_PRIORITY
     * thread walks every region file nearest-spawn-first and warms the store for
     * terrain no client has asked for yet, yielding to player reads and tick health.
     * Also controllable at runtime via /lsslod store backfill start|stop. Default
     * false — the store warms organically from serves either way. No clamp: boolean.
     */
    public boolean lodStoreBackfill = false;
    /**
     * Backfill pace: visited columns per second (docs/planning/store-backfill-tuning-plan.md).
     * Every visited column counts against the window — deposits, hasRow skips, and errors
     * alike — so the value bounds the walk's total footprint, not just its read rate.
     * The restraint gates (reader headroom, tick health, MIN_PRIORITY, one read at a
     * time) are deliberately NOT tunable; this knob only trades walk duration against
     * idle-server IO pressure. Inert on Paper until it grows a backfill (same recorded
     * stance as lodStoreBackfill).
     */
    public int lodStoreBackfillColumnsPerSecond = 100;
    /**
     * Backfill tick-health ceiling (smoothed MSPT): the walk pauses while the server
     * tick is over this. Clamped to stay meaningfully below the 50 ms tick — a ceiling
     * >= 50 would never pause, one <= 20 would never run on a busy server.
     */
    public int lodStoreBackfillTickCeilingMillis = 45;
    /**
     * On-disk size cap for the SQLite LOD store (main DB, MB). <b>0 = UNCAPPED — the
     * default</b> (user decision, docs/planning/store-cap-behavior-plan.md: admins
     * should simply know the store roughly DOUBLES the world folder when fully warmed
     * — ~7.6 KB/col vs ~10.6 KB/chunk of region data; a silent partial-warmth cap
     * surprises more than disk growth does, and at the old 2048 default a
     * pregenerated world entered a backfill<->eviction treadmill forever). A nonzero
     * value (clamped 64..32768) opts into the bound for quota-limited hosts: above it
     * the batcher evicts oldest-timestamp rows in batches and returns pages via
     * incremental_vacuum. NOTE the eviction order is oldest FIRST-DEPOSITED (a row's
     * ts is set when it enters and is not refreshed by hits), so a capped store sheds
     * its longest-resident terrain, not its least-recently-served. Evicted columns
     * re-warm on their next serve, and the affected backfill regions are un-marked so
     * an enabled backfill revisits them; the backfill also hard-stops near an active
     * cap rather than churn it.
     */
    public int lodStoreMaxMB = 0;

    /** The store's byte cap for {@code Environment.maxDbBytes}: 0 (or a validated-away
     *  negative) means uncapped = Long.MAX_VALUE. Both platforms wire through this so
     *  the 0-semantics cannot drift. */
    public long lodStoreMaxBytes() {
        return lodStoreMaxMB <= 0 ? Long.MAX_VALUE : lodStoreMaxMB * 1024L * 1024L;
    }
    /**
     * LOD x-ray masking (docs/planning/antixray-compat-design.md §3). "auto" (default)
     * masks iff an anti-xray engine is detected — Paper's built-in anti-xray config, or the
     * AntiXray mod on Fabric — adopting its per-world hidden list + max-block-height
     * ("mask exactly what the packet engine masks"). "on" forces masking everywhere; "off"
     * is the explicit kill switch (re-opens the LOD ore leak knowingly — the AntiXray crash
     * shim stays active regardless). Unknown values normalize to "auto".
     */
    public String xrayObfuscation = "auto";
    /**
     * FALLBACK hidden-block list — used when no engine values are adoptable (mode "on"
     * without a detected engine, or a detection/reflection failure). Verbatim copy of
     * Paper's default engine-mode-1 {@code hidden-blocks}. All states of each block are
     * hidden; unknown ids warn and are skipped at resolve time. An explicit empty list
     * means "hide nothing"; a malformed null restores this default.
     */
    public List<String> xrayHiddenBlocks = defaultXrayHiddenBlocks();
    /**
     * FALLBACK mask cutoff: only blocks below this world Y are masked (Paper's default 64).
     * At/above it the data already ships unobfuscated in vanilla chunk packets, so masking
     * there would over-hide while protecting nothing.
     */
    public int xrayMaxBlockHeight = 64;

    /** Paper's default engine-mode-1 hidden-blocks, copied verbatim (2026-07-23 build). */
    public static List<String> defaultXrayHiddenBlocks() {
        return List.of(
                "copper_ore", "deepslate_copper_ore", "raw_copper_block",
                "gold_ore", "deepslate_gold_ore",
                "iron_ore", "deepslate_iron_ore", "raw_iron_block",
                "coal_ore", "deepslate_coal_ore",
                "lapis_ore", "deepslate_lapis_ore",
                "mossy_cobblestone", "obsidian", "chest",
                "diamond_ore", "deepslate_diamond_ore",
                "redstone_ore", "deepslate_redstone_ore",
                "clay",
                "emerald_ore", "deepslate_emerald_ore",
                "ender_chest");
    }

    @Override
    protected String getFileName() {
        // Deliberately brand-INVARIANT: both LSS and VSS servers use lss-server-config.json.
        // Unlike the client config (LSSClientConfig, which is brand-driven via
        // brandedConfigCandidates), the server config was never brand-specific, so keeping one
        // shared name is what makes an LSS<->VSS server jar swap trivially keep its config. Do
        // NOT route this through brandedConfigCandidates("server") without a migration story.
        return FILE_NAME;
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
        bytesPerSecondLimitPerPlayer = Math.clamp(bytesPerSecondLimitPerPlayer, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER);
        diskReaderThreads = Math.clamp(diskReaderThreads, LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer, LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        // 0 = disabled is a first-class value (the default); any nonzero opt-in clamps into
        // the supported band — same shape as lodStoreMaxMB.
        outboundBufferCeilingKB = outboundBufferCeilingKB <= 0 ? 0 : Math.clamp(
                outboundBufferCeilingKB, LSSConstants.MIN_OUTBOUND_BUFFER_CEILING_KB,
                LSSConstants.MAX_OUTBOUND_BUFFER_CEILING_KB);
        bytesPerSecondLimitGlobal = (int) Math.clamp((long) bytesPerSecondLimitGlobal, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT);
        generationConcurrencyLimitGlobal = Math.clamp(generationConcurrencyLimitGlobal, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);
        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        dirtyBroadcastIntervalSeconds = Math.clamp(dirtyBroadcastIntervalSeconds, LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        perDimensionTimestampCacheSizeMB = Math.clamp(perDimensionTimestampCacheSizeMB, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
        missMemoTtlSeconds = Math.clamp(missMemoTtlSeconds, LSSConstants.MIN_MISS_MEMO_TTL_SECONDS, LSSConstants.MAX_MISS_MEMO_TTL_SECONDS);
        lodStore = dev.vox.lss.common.store.LodStoreMode.normalize(lodStore).configValue();
        lodStoreMemoryMB = Math.clamp(lodStoreMemoryMB,
                LSSConstants.MIN_LOD_STORE_MEMORY_MB, LSSConstants.MAX_LOD_STORE_MEMORY_MB);
        lodStoreResweepSeconds = Math.clamp(lodStoreResweepSeconds,
                LSSConstants.MIN_LOD_STORE_RESWEEP_SECONDS, LSSConstants.MAX_LOD_STORE_RESWEEP_SECONDS);
        // 0 (and negative nonsense) = uncapped — the resweepSeconds 0-means-off
        // pattern; only a nonzero opt-in cap gets the 64..32768 floor/ceiling.
        lodStoreMaxMB = lodStoreMaxMB <= 0 ? 0 : Math.clamp(lodStoreMaxMB,
                LSSConstants.MIN_LOD_STORE_MAX_MB, LSSConstants.MAX_LOD_STORE_MAX_MB);
        lodStoreBackfillColumnsPerSecond = Math.clamp(lodStoreBackfillColumnsPerSecond,
                LSSConstants.MIN_LOD_STORE_BACKFILL_CPS, LSSConstants.MAX_LOD_STORE_BACKFILL_CPS);
        lodStoreBackfillTickCeilingMillis = Math.clamp(lodStoreBackfillTickCeilingMillis,
                LSSConstants.MIN_LOD_STORE_BACKFILL_TICK_CEILING_MS,
                LSSConstants.MAX_LOD_STORE_BACKFILL_TICK_CEILING_MS);
        xrayObfuscation = XrayMaskPolicy.normalizeMode(xrayObfuscation);
        if (xrayHiddenBlocks == null) xrayHiddenBlocks = defaultXrayHiddenBlocks();
        xrayMaxBlockHeight = Math.clamp(xrayMaxBlockHeight, LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT, LSSConstants.MAX_XRAY_MAX_BLOCK_HEIGHT);

        // Global Constraint #28 is GONE: no client budget derives from any server cap under
        // server-owned generation, so there is nothing to cross-clamp against the wire batch.
        // Its successor is a static inequality between constants, pinned by
        // WantSetBudgetInvariantTest: SYNC_ON_LOAD_SLOT_CAP + MAX_CONCURRENT_GENERATIONS
        //   + WANT_SET_FRONTIER_RESERVE <= WANT_SET_BUDGET <= MAX_BATCH_CHUNK_REQUESTS.
    }
}
