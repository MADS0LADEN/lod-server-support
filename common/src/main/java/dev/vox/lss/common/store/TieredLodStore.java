package dev.vox.lss.common.store;

/**
 * The {@code lodStore=full} composition: the bounded memory tier in front of the SQLite
 * tier, reporting through ONE shared {@link LodStoreDiagnostics} (mem hits count
 * {@code store.mem_hits} inside the memory tier; the rung counts {@code store.hits} for
 * either tier answering — SQLite-tier hits are therefore {@code hits − mem_hits}).
 *
 * <p>Deliberately NO promotion-on-read from SQLite into the memory tier: the Phase 1
 * curve measurement showed refill churn collapses an evict-on-admit sample under
 * pressure, and a warm SQLite point read (~5 µs + decompress) is already cheap enough
 * that promotion buys little — the Phase 2 memory-vs-SQLite A/B (delete-the-tier as a
 * permitted outcome) decides the tier's fate on measurements, not intuition.
 */
public final class TieredLodStore implements LodStoreService {

    private final MemoryLodStore memory;
    private final SqliteLodStore sqlite;
    private final LodStoreDiagnostics diag;

    TieredLodStore(MemoryLodStore memory, SqliteLodStore sqlite, LodStoreDiagnostics diag) {
        this.memory = memory;
        this.sqlite = sqlite;
        this.diag = diag;
        // A periodic resweep that drops a stale SQLite row must evict the memory tier's
        // copy too — the front tier answers first, so without this fan-out the sweep's
        // staleness bound (Paper's unfired-event guarantee) would not hold for
        // memory-resident rows. invalidate() is tombstone-stamped and thread-safe from
        // the batcher thread.
        sqlite.setSweepDropListener(memory::invalidate);
    }

    @Override
    public LodStoreMode mode() {
        return LodStoreMode.FULL;
    }

    @Override
    public StoreHit get(String dimension, long packed) {
        StoreHit hit = this.memory.get(dimension, packed);
        if (hit != null) return hit;
        return this.sqlite.get(dimension, packed);
    }

    @Override
    public void deposit(String dimension, long packed, byte[] sectionBytes, long columnTimestamp) {
        this.memory.deposit(dimension, packed, sectionBytes, columnTimestamp);
        this.sqlite.deposit(dimension, packed, sectionBytes, columnTimestamp);
    }

    @Override
    public void invalidate(String dimension, long[] positions) {
        this.memory.invalidate(dimension, positions);
        this.sqlite.invalidate(dimension, positions);
    }

    @Override
    public void delete(String dimension, long packed) {
        this.memory.delete(dimension, packed);
        this.sqlite.delete(dimension, packed);
    }

    @Override
    public LodStoreDiagnostics diagnostics() {
        return this.diag;
    }

    /** The SQLite tier (harness/test seam — e.g. awaiting the startup sweep). */
    public SqliteLodStore sqliteTier() {
        return this.sqlite;
    }

    @Override
    public void shutdown() {
        this.memory.shutdown();
        this.sqlite.shutdown();
    }
}
