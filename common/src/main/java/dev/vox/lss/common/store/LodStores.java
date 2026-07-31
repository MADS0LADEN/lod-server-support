package dev.vox.lss.common.store;

import dev.vox.lss.common.LSSLogger;

/**
 * Factory for the configured LOD store (both platform services call here):
 * {@code memory} → the bounded in-memory store; {@code full} → the SQLite store ALONE;
 * {@code off} → the caller never calls this. Every failure degrades — memory-only when
 * SQLite can't init (warm joins then survive kicks, not restarts), null (store-off)
 * when even the codec native can't load — with one warning each, never a crash.
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
 */
public final class LodStores {

    private LodStores() {}

    /** Null = run without a store (the codec-probe degrade; caller logs its own warn). */
    public static LodStoreService createOrNull(LodStoreMode mode, long memoryMaxBytes,
                                               SqliteLodStore.Environment env) {
        StoreCodec codec = StoreCodec.zstdOrNull();
        if (codec == null) return null;
        var diag = new LodStoreDiagnostics();
        if (mode != LodStoreMode.FULL) {
            return new MemoryLodStore(mode, codec, memoryMaxBytes, diag);
        }
        SqliteLodStore sqlite = SqliteLodStore.createOrNull(mode, env, diag);
        if (sqlite == null) {
            LSSLogger.warn("lodStore=full requested but the SQLite store is unavailable —"
                    + " degrading to the in-memory store (warm joins survive kicks, not"
                    + " restarts)");
            return new MemoryLodStore(mode, codec, memoryMaxBytes, diag);
        }
        return sqlite;
    }
}
