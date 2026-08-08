package dev.vox.lss.common.store;

import dev.vox.lss.common.LSSLogger;

/**
 * Factory for the LOD store (both platform services call here). {@code lodStore} has
 * exactly two config values: {@code off} — the caller never calls this — and
 * {@code full}, the SQLite store. Every failure degrades with one warning, never a
 * crash: memory-only when SQLite can't init (warm joins then survive kicks, not
 * restarts), null (store-off) when even the codec native can't load.
 *
 * <p><b>The memory tier was DELETED from {@code full} mode</b> (the Phase 2
 * memory-vs-SQLite A/B the plan pre-authorized, 2026-07-31 — evidence in
 * docs/planning/lod-store-progress.md): a front tier bought 5 µs vs ~100 µs on warm
 * hits — both invisible next to the ~2.4 ms NBT path it replaces and the client's
 * network RTT — while costing a SECOND zstd compression of every deposited column
 * (the cold-path gate measured the tiered composition at +14.6% whole-JVM CPU/col,
 * ceiling +10%), a 64 MB RAM budget, eviction machinery, and real composition
 * complexity (the resweep→memory staleness fan-out). SQLite-alone keeps the entire
 * cross-restart value at half the deposit cost.
 *
 * <p><b>{@code lodStore=memory} was then retired as a config value too</b> (2026-08-02,
 * user decision): the same arithmetic that killed the front tier condemns the mode —
 * at 64 MB and ~5.3 KB/col it resides ~12.5k columns, ~6% of one player's disc at
 * lodDistanceChunks=256, under random eviction, while paying a zstd compression on
 * every single deposit. {@link MemoryLodStore} survives ONLY as the degrade below, at
 * a fixed budget with no knob.
 */
public final class LodStores {

    private LodStores() {}

    /**
     * Resident budget for the SQLite-init degrade. Fixed, not configurable: this is a
     * consolation cache on a server whose disk store just failed to open, not an
     * operating mode anyone should be tuning. 64 MB was the old {@code lodStoreMemoryMB}
     * default.
     */
    static final long DEGRADE_MAX_BYTES = 64L * 1024 * 1024;

    /** Null = run without a store (the codec-probe degrade; caller logs its own warn). */
    public static LodStoreService createOrNull(SqliteLodStore.Environment env) {
        StoreCodec codec = StoreCodec.zstdOrNull();
        if (codec == null) return null;
        var diag = new LodStoreDiagnostics();
        SqliteLodStore sqlite = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, diag);
        if (sqlite == null) {
            LSSLogger.warn("lodStore=on requested but the SQLite store is unavailable —"
                    + " degrading to the in-memory store (warm joins survive kicks, not"
                    + " restarts)");
            // MEMORY, not FULL: mode() feeds the diag token, which must report what is
            // actually RUNNING (store=memory), never the configured aspiration.
            return new MemoryLodStore(LodStoreMode.MEMORY, codec, DEGRADE_MAX_BYTES, diag);
        }
        // The store defaults ON (2026-08-08 rework), so its disk cost now lands on servers
        // that never asked for it. Say so once, at the point it becomes true, rather than
        // leaving admins to discover a doubled world folder — a changelog line does not
        // reach someone who upgraded through a host panel.
        LSSLogger.info("LOD store active (lodStore=on). It stores served LOD bytes under"
                + " <world>/lss-lod/ and, once fully warmed, occupies roughly as much space as"
                + " the region files themselves. It is DERIVED data — deleting lss-lod/ is"
                + " always safe. Set lodStore=off to disable, lodStoreMaxMB to bound it.");
        return sqlite;
    }

    /**
     * One-line startup recommendation, emitted by both platforms when the store is OFF and
     * LSS itself is enabled. The store is ON BY DEFAULT since the 2026-08-08 rework, so
     * this line now reaches only admins who explicitly opted out (or whose file predates
     * the rework and says "off") — it stays because the recommendation is still true for
     * them, and it documents the disk tradeoff at the moment it matters. Returns the
     * message rather than logging so the decision is pinnable; callers log INFO. Null when
     * LSS is disabled (nothing to recommend into) and on Folia — the store is unvalidated
     * there and {@code PaperConfig.validate()} WARNS on an armed store; recommending what
     * we warn about is incoherent.
     */
    public static String offRecommendationOrNull(boolean lssEnabled, boolean isFolia) {
        if (!lssEnabled || isFolia) return null;
        return "LOD store is off. Recommended: set \"lodStore\": \"on\" in"
                + " lss-server-config.json for much faster LOD serving; the tradeoff is it"
                + " roughly doubles the size of your world directory.";
    }
}
