package dev.vox.lss.common.store;

/**
 * The LOD store (docs/planning/lod-store-implementation-plan.md §1): serves previously
 * serialized wire-format section bytes so a warm hit does no chunk NBT parse, no region
 * read, no serialization. Phase 1 ships the bounded in-memory tier; Phase 2 adds SQLite
 * behind the same interface (and the flat-file fallback engine, if the SQLite spike had
 * failed, would slot here too).
 *
 * <p><b>Boundary invariants (§1, all review-derived — do not relax):</b>
 * <ul>
 * <li><b>All-air speaks {@code byte[0]}</b>, never null: null means MISS. The read side
 *     treats null section bytes as authoritative-not-found (which seeds the miss memo),
 *     so an all-air row leaking out as null would memoize a false absence.</li>
 * <li><b>A hit serves its STORED timestamp</b> — never a freshly fabricated stamp
 *     (delivery honesty: a fabricated newer stamp would answer false up_to_date).</li>
 * <li><b>Section bytes only</b>, never a pre-built payload — keeps the v16 shim's
 *     source-byte strip and the VSS pair checks safe.</li>
 * <li><b>Deposits are latest-wins by STORED timestamp</b>, not arrival order (a slow
 *     serve-time deposit must not overwrite a newer save-hook deposit).</li>
 * <li><b>Deposits ride the delivery path AFTER the stale guard</b> — callers must never
 *     deposit from the reader (an edit overtaking an in-flight read would poison the
 *     store with pre-edit bytes that then get re-stamped, sealed).</li>
 * </ul>
 *
 * <p>Threading: {@link #get} runs on reader-pool threads; {@link #deposit} enqueues from
 * the processing thread (the write itself happens on the store's own batcher thread);
 * {@link #invalidate}/{@link #delete} apply SYNCHRONOUSLY from the processing thread —
 * the memory tier has no SQLite single-writer constraint, and a synchronous remove
 * closes the stale-hit window between a tscache invalidation and an async store delete
 * outright (safer than the plan's async-delete + freshness-check shape; recorded in the
 * progress file). Never call anything here from the server thread or a region thread.
 */
public interface LodStoreService {

    /** A store hit: wire-format section bytes ({@code length == 0} = all-air) plus the
     *  stored column timestamp. */
    record StoreHit(byte[] sectionBytes, long columnTimestamp) {}

    /** The mode this store was built for (memory tier only vs memory+disk). */
    LodStoreMode mode();

    /** Look up a column; null = miss. Reader-pool threads. Must never throw — internal
     *  failures are contained, counted {@code store.errors}, and read as a miss. */
    StoreHit get(String dimension, long packed);

    /**
     * Enqueue a deposit (processing thread; the write happens on the batcher thread).
     * {@code sectionBytes} null or empty both mean all-air and are normalized to
     * {@code byte[0]} at this boundary. A full queue sheds the OLDEST entry (counted
     * {@code store.deposit_drops}) — the store is derived data; a shed deposit
     * re-deposits on the next serve.
     */
    void deposit(String dimension, long packed, byte[] sectionBytes, long columnTimestamp);

    /** Synchronously drop the given positions (the dirty/edit invalidation fan-out). */
    void invalidate(String dimension, long[] positions);

    /** Synchronously drop one position (the not-found ghost guard: disk said a
     *  previously-served chunk no longer exists — without this the store re-serves
     *  deleted terrain forever). */
    void delete(String dimension, long packed);

    /** The store's counter family (the same instance the exporters read). */
    LodStoreDiagnostics diagnostics();

    /** Stop the batcher thread; the memory tier discards content (derived data). */
    void shutdown();
}
