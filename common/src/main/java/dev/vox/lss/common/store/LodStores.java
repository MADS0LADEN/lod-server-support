package dev.vox.lss.common.store;

import dev.vox.lss.common.LSSLogger;

/**
 * Factory for the configured LOD-store composition (both platform services call here):
 * {@code memory} → the bounded memory tier alone; {@code full} → memory + SQLite
 * ({@link TieredLodStore}); {@code off} → the caller never calls this. Every failure
 * degrades — memory-only when SQLite can't init, null (store-off) when even the codec
 * native can't load — with one warning each, never a crash.
 */
public final class LodStores {

    private LodStores() {}

    /** Null = run without a store (the codec-probe degrade; caller logs its own warn). */
    public static LodStoreService createOrNull(LodStoreMode mode, long memoryMaxBytes,
                                               SqliteLodStore.Environment env) {
        StoreCodec codec = StoreCodec.zstdOrNull();
        if (codec == null) return null;
        var diag = new LodStoreDiagnostics();
        var memory = new MemoryLodStore(mode, codec, memoryMaxBytes, diag);
        if (mode != LodStoreMode.FULL) {
            return memory;
        }
        SqliteLodStore sqlite = SqliteLodStore.createOrNull(mode, env, diag);
        if (sqlite == null) {
            LSSLogger.warn("lodStore=full requested but the SQLite tier is unavailable —"
                    + " running with the memory tier only (warm joins survive kicks, not"
                    + " restarts)");
            return memory;
        }
        return new TieredLodStore(memory, sqlite, diag);
    }
}
