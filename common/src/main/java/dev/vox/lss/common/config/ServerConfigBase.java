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
    /** LOD radius in chunks. 512 by user decision 2026-08-08 (config rework) — this
     *  REVERSES the 2026-08-02 same-day revert of 512: the D0 tile cache made the
     *  derived footprint cheap (AUTO ~45 MB/dim at 512 vs ~109 MB pre-tile). Note what
     *  scales with it — the timestamp cache (see effectiveTimestampCacheMB) and, with
     *  the store on (its 2026-08-08 default), the warmed disk footprint. */
    public int lodDistanceChunks = 512;
    /**
     * Per-player bandwidth cap. Went 20 -> 50 -> 25 MiB on 2026-08-02, 25 -> 15 MiB on
     * 2026-08-05 (v0.9.1), then 15 -> 25 MiB on 2026-08-08 (all user decisions; the
     * last in the config rework's bandwidth re-bump alongside global 60 -> 75).
     *
     * <p><b>This charges RAW bytes, not wire bytes</b> ({@code estimatedBytes} = raw sections +
     * envelope; wire compression is deliberately invisible to it), because what it really
     * bounds is CLIENT DECODE AND INGEST WORK — and the elytra chunk-wall investigation
     * confirmed the client, not the link, is the binding constraint. So the ~6.25:1 the
     * compressed-columns work bought did NOT loosen the thing this cap exists to limit: at
     * 25 MiB counted the wire cost is roughly 4 MB/s, but the client still decodes 25 MiB/s.
     *
     * <p>Context for anyone retuning it: the elytra chunk wall reproduced at ~25 MB/s
     * counted, so the 25 default sits AT the historic incident rate (the 2026-08-05
     * 15 MiB era sat below it). That is a deliberate return: the thing that actually
     * caused the wall (the client's scan-cadence gate) is fixed, and the #71 ingest
     * taper and the decode-queue halt sit underneath as client-side guards. The
     * falsifiable check is the cap sweep in the investigation's section 11.7 — sweep
     * upward until {@code runway} collapses in the client trace.
     *
     * <p><b>Key spelling since the 2026-08-08 config rework:</b> {@code
     * mbPerSecondLimitPerPlayer}, a decimal in MiB/s (12.5 works). −1 = "not in the
     * file" sentinel; {@link #validate} resolves it — the new key wins when both are
     * present, else the legacy {@code bytesPerSecondLimitPerPlayer} converts, else the
     * 25.0 default. Consumers never read these fields directly: {@link
     * #bytesPerSecondPerPlayer()} is the resolved byte value.
     */
    public double mbPerSecondLimitPerPlayer = -1;
    /** RETIRED key spelling — honored on read, never written (see the rework note
     *  above; −1 = absent). */
    @HiddenFromFile
    public int bytesPerSecondLimitPerPlayer = -1;
    /**
     * LSS disk-read pool size. <b>0 = AUTO (the default)</b>, derived from the resolved read
     * path — see {@link #effectiveDiskReaderThreads(boolean)}.
     *
     * <p><b>This is not disk parallelism</b> (read-scheduler-design.md section 0), and the old
     * fixed default of 5 encouraged exactly the wrong instinct. On vanilla's single-threaded
     * IOWorker it is the number of LSS reads that can sit in the shared IO queue AHEAD of a
     * vanilla read: more threads do not speed up cold reads (the worker serializes them
     * regardless) but linearly increase how long a vanilla chunk load waits behind LSS. Where a
     * real priority mechanism exists instead — Moonrise's {@code Priority.LOW}, which is every
     * Paper/Folia server and any Fabric server with Moonrise — that tradeoff does not apply and
     * more threads are simply more parse/serialize CPU. One fixed number cannot be right for
     * both, which is why the default derives.
     */
    public int diskReaderThreads = 0;
    /**
     * Per-player send-queue cap. The default is the wire batch cap: under v17 replace
     * semantics a player's backlog is at most ONE wire batch, and a payload only enqueues
     * for an admitted backlog position, so enqueued payloads per player structurally
     * cannot exceed MAX_BATCH_CHUNK_REQUESTS — at this default the router's queue gate is
     * unreachable for any legal client, and the queue bounds worst-case buffered-payload
     * RAM at ~one batch per player.
     *
     * <p>The 2026-08-02 config review proposed retiring this to a constant on exactly that
     * "unreachable at its default" argument, and <b>implementation reversed the call</b>:
     * lowering it is the ONLY lever that exercises {@code service.queue_full}, the send-queue
     * breaker — which is a real production loss signal, unlike the transient-drop counters
     * beside it. The {@code bandwidth-throttle} soak scenario sets 64 here and gates on
     * queue_full firing; retiring the key would have left that production path with no
     * end-to-end coverage. A knob with no production use but a genuine harness role is test
     * infrastructure, not dead weight.
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
    /**
     * Transport yield (vanilla-first-lod-yield-plan.md v2.1, v0.10.0 stage A2): while the
     * player's netty channel reports NOT WRITABLE, the per-tick column flush is skipped
     * and the queue retained — LSS never piles more LOD bytes into a channel that is
     * already backed up ahead of vanilla's chunk packets. A starvation floor sends one
     * payload per 5 s so a hard-yielding player is distinguishable from a dead one, and a
     * once-a-minute relevance prune drops queue entries the player has long left behind.
     * <b>Default FALSE</b> — the mechanism ships unarmed (the project's recorded evidence
     * discipline: the default flips only in a later release citing the live E3 A/B, whose
     * metric is moved-wrongly/rejection EVENT RATES). Behind a buffering proxy
     * (Velocity/Bungee) the gate sees the server→proxy hop and is best-effort: it can
     * under-yield, never over-yield. While armed, expect flying players to ride the floor
     * during sustained vanilla chunk bursts — that IS "vanilla first". No clamp (boolean).
     */
    public boolean lodYieldsToVanillaTransport = false;
    /** Fleet-wide bandwidth ceiling. Raised 100 -> 256 MiB 2026-08-02 (config review
     *  section 3.2 — at 20 MiB/player the old value bound at FIVE concurrent LOD players),
     *  lowered 256 -> 60 MiB on 2026-08-05 (user decision, v0.9.1, alongside the 15 MiB
     *  per-player cut), then 60 -> 75 on 2026-08-08 with the per-player 15 -> 25 re-bump:
     *  a deliberate total-egress bound sized for typical hosts. At the 25 MiB per-player
     *  default it binds at THREE concurrent full-rate LOD players and manifests as
     *  everyone slowing together — operators with more simultaneous LOD traffic should
     *  raise this first. Key spelling since the 2026-08-08 rework:
     *  {@code mbPerSecondLimitGlobal}, decimal MiB/s; same sentinel/priority scheme as
     *  the per-player pair; consumers read {@link #bytesPerSecondGlobal()}. */
    public double mbPerSecondLimitGlobal = -1;
    /** RETIRED key spelling — honored on read, never written (−1 = absent). */
    @HiddenFromFile
    public int bytesPerSecondLimitGlobal = -1;
    public boolean enableChunkGeneration = true;
    /** 32 -> 40 by user decision 2026-08-08 (config rework), matching the per-player cap. */
    public int generationConcurrencyLimitGlobal = 40;
    public int generationTimeoutSeconds = 60;
    public int dirtyBroadcastIntervalSeconds = 10;
    // The per-player SYNC (disk-read) slot cap is NOT config anymore — see
    // LSSConstants.SYNC_ON_LOAD_SLOT_CAP (shadowed by the disk-pool headroom gate at the
    // default pool size; a fixed fairness ceiling above it). The generation caps stay
    // config: they are the real worldgen limiters, but they are server-internal (off the
    // wire since the server-owned-generation fold into v17).
    /** 16 -> 40 by user decision 2026-08-08 (config rework). validate() still clamps
     *  this to the global cap. */
    public int generationConcurrencyLimitPerPlayer = 40;
    /**
     * Per-dimension up-to-date timestamp cache size (live heap, not disk). <b>0 = AUTO (the
     * default)</b> — derived from {@link #lodDistanceChunks}, see
     * {@link #effectiveTimestampCacheMB()}. A fixed value silently under-provisions the moment
     * an admin raises the LOD distance, which is precisely when the cache matters most; the
     * derived value tracks it. Multiply by dimension count for the real heap budget.
     */
    public int perDimensionTimestampCacheSizeMB = 0;
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
     * schedules them at IOWorker BACKGROUND priority (or Moonrise Priority.LOW when Moonrise is
     * present); Paper/Folia route through Moonrise at Priority.LOW. Set false to restore
     * FOREGROUND reads (the pre-0.7 behavior) as a rollback. No clamp: a boolean has no
     * out-of-range value.
     *
     * <p>The 2026-08-02 config review proposed retiring this (its real failure modes are
     * handled by automatic one-way latches, not by config), and <b>implementation reversed the
     * call</b> for the same reason as {@code sendQueueLimitPerPlayer}: it is the arm selector
     * for {@code benchmark_compare.sh}'s {@code v17-fg} foreground-vs-background CPU
     * comparison. Retiring it would not have broken that harness — it would have made its two
     * arms silently identical, which is worse than breaking.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useBackgroundReadPriority = true;
    /**
     * When true (default), the Fabric vanilla-IOWorker background read is SPLIT (perf
     * round Phase 3 / R1): the single-threaded IOWorker executor only fetches the
     * chunk's raw compressed record; zlib inflate + NBT parse run on the LSS reader
     * pool (or the backfill thread for backfill reads). The IOWorker was the busiest
     * serving thread by ~7x per-thread — pread + inflate + full parse for every LOD
     * read — and is the mechanism of the documented A7 read-timeout storms. Set false
     * to restore the pre-split full-read-on-executor closure as a rollback NARROWER
     * than {@code useBackgroundReadPriority=false} (which also drops the Moonrise rung
     * and all read protection). Fabric-only in effect: Paper/Folia reads route through
     * Moonrise, which never had the executor-parse problem; the Moonrise rung on
     * Fabric is likewise untouched. No clamp: a boolean has no out-of-range value.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useBackgroundReadSplit = true;
    /**
     * When true (default), the Phase 3 split's pool-side NBT parse is SELECTIVE (perf
     * round Phase 4 / R2): only the root keys the serializer actually reads ({@code
     * Status}, {@code sections} — the whitelist is build-pinned against the serializer
     * source) are materialized; every other root subtree is skipped without building
     * Strings/HashMaps/tags. The win is allocation churn, not inflate (skipped bytes
     * still move through the inflater). One documented leniency divergence: a corrupt
     * NON-section root subtree that full parse would throw on can now skip cleanly —
     * more lenient, and any selective-parse throw falls back to a full parse of the
     * same buffer. Fabric-only in effect (it lives at the split's parse site; the
     * {@code lodStoreBackfillColumnsPerSecond} shared-key idiom). Set false to
     * restore the full root parse as a rollback. No clamp: a boolean has no
     * out-of-range value.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useSelectiveNbtParse = true;
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
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
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
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useCompressedColumns = true;
    /**
     * When true (default), clients running the legacy protocol-16 mod (v0.6.x) get a
     * translated LOD session through the v16 compat shim (docs/planning/v16-compat-design.md)
     * instead of the silent version-mismatch no-session. Inert for current-protocol clients;
     * set false as the kill switch to restore the strict version gate. No clamp: boolean.
     */
    public boolean enableV16Compat = true;
    /**
     * When true (default), clients running the protocol-18 mod (v0.7.x–v0.8.x) get a native
     * LOD session through the v18 compat rung (docs/planning/v18-compat-design.md): the
     * current session shape with the SessionConfig echoing 18, columns forced codec-RAW, and
     * the codec byte stripped at egress. Without the rung those clients degrade to the v16
     * fallback session after their 5 s discovery timeout. Inert for current-protocol
     * clients; set false as the kill switch to restore the strict version gate for 18.
     * No clamp: boolean.
     */
    public boolean enableV18Compat = true;
    /**
     * When true (default), clients running the protocol-19 mod (v0.9.x–v0.10.x-pre) get a
     * native LOD session through the v19 compat rung
     * (docs/planning/cross-version-identity-encoding-plan.md §4.2): the current session
     * shape with the SessionConfig echoing 19 and the column BODY translated per serve
     * from the v20 identity-dictionary layout back to native global-id palettes. Without
     * the rung those clients degrade to the v16 fallback session after their 5 s
     * discovery timeout. Inert for current-protocol clients; set false as the kill
     * switch to restore the strict version gate for 19. No clamp: boolean.
     */
    public boolean enableV19Compat = true;
    /**
     * When true (default), a legacy-protocol handshake (19/18/16) from a client that
     * ViaVersion/ViaFabric POSITIVELY reports as running a different MC version is
     * answered with silence instead of a compat session
     * (docs/planning/cross-version-identity-encoding-plan.md §7): legacy column bytes
     * are native-id formats for THIS MC version's registries, so a cross-MC legacy
     * client would receive garbage it cannot decode. One INFO line names the versions.
     * Fail-open — without Via, or when Via has no signal for the player, behavior is
     * unchanged. This is the operator override: the guard denies registration off a
     * third-party reflective API, and a Via API drift that misreported protocols would
     * otherwise lock out legitimate same-MC legacy clients with no recourse — set
     * false to disarm. Current-protocol (v20) clients are never affected (their
     * handshake carries the MC data version). No clamp: boolean.
     */
    public boolean enableViaMismatchGuard = true;
    /**
     * The LOD store switch (docs/planning/lod-store-implementation-plan.md):
     * "off" (no store — the kill switch every store gate A/Bs against) and
     * <b>"full" (the SQLite disk store — the DEFAULT since 2026-08-02)</b>, alone since the
     * Phase 2 delete-the-tier verdict.
     *
     * <p><b>Default-on costs disk, and that is a deliberate trade.</b> Live measurement:
     * 98.8% of column serves are store hits (280,273 hits / 2,808 real disk reads), and a hit
     * serves in ~100 us against ~2.4 ms for the NBT path — plus, since compressed columns, it
     * ships its stored zstd frame verbatim with no compression at all. The price is that a
     * fully warmed store roughly DOUBLES the world folder (~5.3-7.6 KB/col against
     * ~10.6 KB/chunk of region data). It is derived data: deleting the {@code lss-lod/} folder
     * is always safe, and the service logs the expected growth once at startup.
     *
     * <p>SPLIT DEFAULT (user decision, 2026-08-08, second round — refines the same-day
     * on-by-default decision, which superseded the 2026-08-03 opt-in): the COMPILED
     * default here is "off", so a key ABSENT from an existing config file keeps the
     * store off — an upgrade must never silently double a world folder (the 2026-08-03
     * concern, back as the governing rule for upgrades). A BRAND-NEW install — no
     * config file at all — generates "on" via {@link #onFreshCreate}, so new servers
     * get the feature from day one with the disk cost stated in README/release notes.
     * "on" ≡ "full" (the old spelling stays a read alias; the file writes back "on").
     * {@code lodStoreBackfill} deliberately stays ON so an armed store gets the
     * background warm-up — one switch.
     *
     * <p>Unknown values still normalize to "off" (a typo silently DISABLES the feature
     * instead of enabling a storage engine — predictable either way, and the config
     * echo names the effective mode). "memory" remains one of those unknowns; the
     * in-memory tier survives only as the SQLite-init degrade (see LodStores).
     *
     * <p><b>Harness note:</b> the soak/benchmark stagings and gametest run dirs pin
     * this OFF explicitly (store scenarios excepted) — their law baselines and source
     * pins were calibrated store-off, and re-baselining them buys nothing.
     */
    public String lodStore = "off";
    // NOTE: lodStoreMemoryMB is RETIRED (2026-08-02) along with the "memory" mode — the
    // in-memory tier survives only as the boot-time degrade when SQLite cannot init, at
    // a fixed budget (LodStores.DEGRADE_MAX_BYTES). GSON ignores the key on load and
    // validate()'s next save drops it from the file, same as the retired
    // syncOnLoadConcurrencyLimitPerPlayer.
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
     * Also controllable at runtime via /lsslod store backfill start|stop.
     *
     * <p><b>Default TRUE since 2026-08-02.</b> Organic warming only ever covers terrain a
     * client already asked for — which is terrain that was already served once. The store's
     * value proposition is that the SECOND visitor, and the same player after a restart,
     * arrive warm; that requires walking terrain nobody has requested yet. Safe as a default
     * because the restraint architecture is not tunable: MIN_PRIORITY thread, one read at a
     * time, the reader-headroom gate, the MSPT ceiling, 500 ms pause polling. The walk is
     * resumable across restarts via per-region done-marks and logs a size estimate up front.
     *
     * <p>Stays TRUE even though {@code lodStore} now defaults to "off" (2026-08-03): the key
     * is INERT while the store is off, so the only thing this default decides is what an
     * operator gets when they DO opt in — and they should get the whole feature from one
     * switch rather than discovering a second one later. No clamp: boolean.
     */
    public boolean lodStoreBackfill = true;
    /**
     * Backfill pace: visited columns per second (docs/planning/store-backfill-tuning-plan.md).
     * Every visited column counts against the window — deposits, hasRow skips, and errors
     * alike — so the value bounds the walk's total footprint, not just its read rate.
     * The restraint gates (reader headroom, tick health, MIN_PRIORITY, one read at a
     * time) are deliberately NOT tunable; this knob only trades walk duration against
     * idle-server IO pressure. Inert on Paper until it grows a backfill (same recorded
     * stance as lodStoreBackfill).
     *
     * <p><b>Raised 100 -> 500 on 2026-08-02</b>, which is what makes the backfill tolerable as
     * a default: at 100 col/s a 700k-column world is ~2 hours of continuous background walking;
     * at 500 it is ~23 minutes. For a task with a definite end, finishing sooner is strictly
     * better — the restraint gates are unchanged, so the walk is no more intrusive per unit of
     * work, it simply stops being present sooner. 500 is live-proven on the Modrinth server and
     * sits inside the honest band (a single-threaded synchronous read is ~1-2 ms healthy, so
     * ~500-1000/s is the natural ceiling). The pace is a CEILING, never a floor: a constrained
     * box is simply gated down (measured 32 col/s under load at the old 100 cap).
     */
    public int lodStoreBackfillColumnsPerSecond = 500;
    // NOTE: lodStoreBackfillTickCeilingMillis is RETIRED (2026-08-02, config review section 6)
    // — now LSSConstants.LOD_STORE_BACKFILL_TICK_CEILING_MS. Its clamp band was 20..50 and both
    // ends were degenerate by its own documentation (>= 50 never pauses, <= 20 never runs on a
    // busy server), which is not a tuning range. GSON drops the key on the next save.
    /**
     * On-disk size cap for the SQLite LOD store (main DB, MB). <b>0 = UNCAPPED — the
     * default</b> (user decision, docs/planning/store-cap-behavior-plan.md: admins
     * should simply know the store roughly DOUBLES the world folder when fully warmed
     * — ~7.6 KB/col vs ~10.6 KB/chunk of region data; a silent partial-warmth cap
     * surprises more than disk growth does, and at the old 2048 default a
     * pregenerated world entered a backfill<->eviction treadmill forever). A nonzero
     * value (clamped 64..1048576) opts into the bound for quota-limited hosts: above it
     * the batcher evicts oldest-timestamp rows in batches and returns pages via
     * incremental_vacuum. NOTE the eviction order is oldest FIRST-DEPOSITED (a row's
     * ts is set when it enters and is not refreshed by hits), so a capped store sheds
     * its longest-resident terrain, not its least-recently-served. Evicted columns
     * re-warm on their next serve, and the affected backfill regions are un-marked so
     * an enabled backfill revisits them; the backfill also hard-stops near an active
     * cap rather than churn it.
     */
    public int lodStoreMaxMB = 0;

    /**
     * Resolved disk-reader pool size, honouring the 0 = AUTO default.
     *
     * @param prioritizedReadPath true when the resolved read path carries a REAL priority
     *        mechanism that defers to gameplay independently of concurrency — Moonrise's
     *        {@code Priority.LOW}, i.e. every Paper/Folia server and any Fabric server with
     *        Moonrise resolved. False for vanilla's single-threaded IOWorker, where LSS
     *        concurrency IS the vanilla-delay tradeoff, and for the chunk-IO-overhaul
     *        fallback, where the adaptive throttle owns the real limit and this is a ceiling.
     */
    public int effectiveDiskReaderThreads(boolean prioritizedReadPath) {
        if (diskReaderThreads > 0) return diskReaderThreads;
        if (!prioritizedReadPath) return LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER;
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2,
                LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER,
                LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX);
    }

    /**
     * Resolved per-dimension timestamp-cache size in MB, honouring the 0 = AUTO default.
     *
     * <p>The scan is a Chebyshev (square) ring walk, so the tracked region is
     * {@code (2*(lodDistanceChunks + LOD_DISTANCE_BUFFER) + 1)^2} positions; AUTO provisions
     * TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR (8x) that area at the tile cache's ~5 B/column
     * (D0 — part of the ~13x tile win is spent on coverage, so roaming and multi-player
     * spread stop thrashing eviction; net at the default distance: ~2.5x less RAM (~30 MB
     * -> ~12 MB) tracking ~5.3x more columns than the pre-tile 1.5x/64 B model). Clamped
     * into the same band an
     * explicit value gets — the 512 MB ceiling now clears distance 1024 at full coverage.
     */
    public int effectiveTimestampCacheMB() {
        if (perDimensionTimestampCacheSizeMB > 0) return perDimensionTimestampCacheSizeMB;
        long side = 2L * (lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER) + 1L;
        long columns = (long) (side * side
                * LSSConstants.TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR);
        long mb = columns * LSSConstants.TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN
                / (1024L * 1024L);
        return (int) Math.clamp(mb, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB,
                LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
    }

    /** The store's byte cap for {@code Environment.maxDbBytes}: 0 (or a validated-away
     *  negative) means uncapped = Long.MAX_VALUE. Both platforms wire through this so
     *  the 0-semantics cannot drift. */
    public long lodStoreMaxBytes() {
        return lodStoreMaxMB <= 0 ? Long.MAX_VALUE : lodStoreMaxMB * 1024L * 1024L;
    }

    /**
     * One-line effective-config echo, INFO-logged once at service start on both platforms.
     *
     * <p>The format is a SCRIPT-CONSUMED CONTRACT (PERF round Phase 0 item 1): the
     * measurement harnesses ({@code profile_disk_read.sh}, {@code compress_gate.sh},
     * {@code backfill_profile.sh}) grep {@code "Effective config: "} out of server.log
     * into each run's meta.json and FAIL the arm when a staged knob does not appear
     * here — an ignored config key must invalidate the arm, not silently compare two
     * identical arms (the exact failure mode the 2026-08-06 findings erratum recorded).
     * Comma-separated {@code key=value} pairs in this fixed order; new perf-sensitive
     * knobs APPEND (scripts match per-key substrings, so appending is compatible).
     * Every echoed value is the RESOLVED one — echoing a raw key would hide exactly
     * the resolution the scripts need to see. Pinned by ConfigValidationTest + the
     * Paper twin; the call-site wiring (resolved arguments, once per service start)
     * is source-regex-pinned in ChannelAccessorContractTest.
     *
     * @param effectiveReaderThreads the RESOLVED pool size — 0=AUTO already applied via
     *        {@link #effectiveDiskReaderThreads(boolean)}
     * @param effectiveCompressedColumns the LIVE wire-compression state AFTER the zstd
     *        native probe, not the config request — a probe-failed server ships raw for
     *        every session, and echoing the request would let compress_gate.sh compare
     *        two identical raw arms with both marked valid (B0 review M1)
     */
    public String effectiveConfigEcho(int effectiveReaderThreads,
                                      boolean effectiveCompressedColumns) {
        return "Effective config: useNbtTranscode=" + useNbtTranscode
                + ", diskReaderThreads=" + effectiveReaderThreads
                + ", useCompressedColumns=" + effectiveCompressedColumns
                + ", useBackgroundReadSplit=" + useBackgroundReadSplit
                + ", useSelectiveNbtParse=" + useSelectiveNbtParse;
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

    /** MiB — the mb* bandwidth keys' unit. */
    private static final double MB = 1024.0 * 1024.0;
    // The shipped bandwidth defaults, in MiB/s (2026-08-05 user decision on the byte
    // values; re-denominated by the 2026-08-08 key rename).
    private static final double DEFAULT_MB_PER_PLAYER = 25.0;
    private static final double DEFAULT_MB_GLOBAL = 75.0;

    /** The key-rename resolution ladder (2026-08-08 rework), pure: the NEW decimal-MiB
     *  key wins when present (its sentinel −1 means "not in the file"), else the legacy
     *  byte key converts, else the default. Shared by validate() and the accessors so a
     *  config validate() never touched still resolves sanely (the lodStoreMaxBytes()
     *  robustness convention) instead of leaking a −1 sentinel to consumers. */
    private static double resolveMb(double mbValue, int legacyBytes, double defaultMb) {
        if (mbValue >= 0) return mbValue;
        return legacyBytes >= 0 ? legacyBytes / MB : defaultMb;
    }

    /** Resolved per-player bandwidth in bytes/s — the ONLY reader-facing form (the
     *  mb/legacy field pair is a file-format concern; validate() canonicalizes it). */
    public int bytesPerSecondPerPlayer() {
        return (int) Math.round(
                resolveMb(mbPerSecondLimitPerPlayer, bytesPerSecondLimitPerPlayer, DEFAULT_MB_PER_PLAYER) * MB);
    }

    /** Resolved global bandwidth ceiling in bytes/s — see {@link #bytesPerSecondPerPlayer()}. */
    public int bytesPerSecondGlobal() {
        return (int) Math.round(
                resolveMb(mbPerSecondLimitGlobal, bytesPerSecondLimitGlobal, DEFAULT_MB_GLOBAL) * MB);
    }

    /** validate()'s half of the rename: fold the resolution into the mb field (what
     *  save() writes and the clamps bound) and re-sentinel the legacy field so it can
     *  never leak stale state into a later validate pass. */
    private void resolveBandwidthKeys() {
        mbPerSecondLimitPerPlayer =
                resolveMb(mbPerSecondLimitPerPlayer, bytesPerSecondLimitPerPlayer, DEFAULT_MB_PER_PLAYER);
        mbPerSecondLimitGlobal =
                resolveMb(mbPerSecondLimitGlobal, bytesPerSecondLimitGlobal, DEFAULT_MB_GLOBAL);
        bytesPerSecondLimitPerPlayer = -1;
        bytesPerSecondLimitGlobal = -1;
    }

    /** Brand-new install (no config file existed): generate the file with the store ON
     *  — the {@code lodStore} split default. See the field javadoc; the compiled "off"
     *  governs keys absent from existing files, this "on" governs fresh generation. */
    @Override
    protected void onFreshCreate() {
        lodStore = "on";
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
        resolveBandwidthKeys();
        mbPerSecondLimitPerPlayer = Math.clamp(mbPerSecondLimitPerPlayer,
                LSSConstants.MIN_BYTES_PER_SECOND / MB,
                LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER / MB);
        // 0 = AUTO is a first-class value (the default); only a nonzero explicit override
        // clamps into the supported band — the same shape as lodStoreMaxMB.
        diskReaderThreads = diskReaderThreads <= 0 ? 0 : Math.clamp(diskReaderThreads,
                LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer,
                LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        // 0 = disabled is a first-class value (the default); any nonzero opt-in clamps into
        // the supported band — same shape as lodStoreMaxMB.
        outboundBufferCeilingKB = outboundBufferCeilingKB <= 0 ? 0 : Math.clamp(
                outboundBufferCeilingKB, LSSConstants.MIN_OUTBOUND_BUFFER_CEILING_KB,
                LSSConstants.MAX_OUTBOUND_BUFFER_CEILING_KB);
        mbPerSecondLimitGlobal = Math.clamp(mbPerSecondLimitGlobal,
                LSSConstants.MIN_BYTES_PER_SECOND / MB,
                LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT / MB);
        generationConcurrencyLimitGlobal = Math.clamp(generationConcurrencyLimitGlobal, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);
        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        dirtyBroadcastIntervalSeconds = Math.clamp(dirtyBroadcastIntervalSeconds, LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
        // Config review section 9.1: the per-player ceiling used to be MAX_CONCURRENCY_LIMIT
        // (1000) while the GLOBAL ceiling is MAX_CONCURRENT_GENERATIONS — a per-player value
        // above the fleet-wide one is unreachable by construction (a player cannot hold more
        // generation slots than exist), so it validated to silent nonsense. Clamp against the
        // configured global, which is itself already clamped on the line above.
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer,
                LSSConstants.MIN_CONCURRENCY_LIMIT, generationConcurrencyLimitGlobal);
        // 0 = AUTO (derived from lodDistanceChunks); only an explicit value clamps.
        perDimensionTimestampCacheSizeMB = perDimensionTimestampCacheSizeMB <= 0 ? 0
                : Math.clamp(perDimensionTimestampCacheSizeMB,
                        LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
        missMemoTtlSeconds = Math.clamp(missMemoTtlSeconds, LSSConstants.MIN_MISS_MEMO_TTL_SECONDS, LSSConstants.MAX_MISS_MEMO_TTL_SECONDS);
        lodStore = dev.vox.lss.common.store.LodStoreMode.normalize(lodStore).configValue();
        lodStoreResweepSeconds = Math.clamp(lodStoreResweepSeconds,
                LSSConstants.MIN_LOD_STORE_RESWEEP_SECONDS, LSSConstants.MAX_LOD_STORE_RESWEEP_SECONDS);
        // 0 (and negative nonsense) = uncapped — the resweepSeconds 0-means-off
        // pattern; only a nonzero opt-in cap gets the 64..1048576 floor/ceiling.
        lodStoreMaxMB = lodStoreMaxMB <= 0 ? 0 : Math.clamp(lodStoreMaxMB,
                LSSConstants.MIN_LOD_STORE_MAX_MB, LSSConstants.MAX_LOD_STORE_MAX_MB);
        lodStoreBackfillColumnsPerSecond = Math.clamp(lodStoreBackfillColumnsPerSecond,
                LSSConstants.MIN_LOD_STORE_BACKFILL_CPS, LSSConstants.MAX_LOD_STORE_BACKFILL_CPS);
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
