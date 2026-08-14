package dev.vox.lss.common.store;

/**
 * The LOD store (docs/planning/lod-store-implementation-plan.md §1): serves previously
 * serialized wire-format section bytes so a warm hit does no chunk NBT parse, no region
 * read, no serialization. (History: Phase 1 shipped a bounded in-memory tier; the tier
 * was deleted 2026-08-13 — SQLite is the only engine.) Phase 2 added SQLite
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
 * <p>Threading: {@link #get} runs on reader-pool threads; {@link #deposit}/{@link
 * #delete} are MULTI-PRODUCER — the processing thread (delivery-path deposits,
 * invalidation fan-out) AND the backfill worker (Phase 4; the Fabric save hook is
 * DELETE-only since R2-M2, and its deletes ride the same multi-producer contract from
 * the main thread or C2ME/Moonrise save threads). The write itself always happens on
 * the store's own batcher thread; implementations must accept concurrent producers.
 * {@link #invalidate}/{@link #delete} must be EFFECTIVE before any subsequent
 * {@code get()} can return the invalidated bytes — the implementation chooses how: the
 * memory store removes synchronously + tombstones queued deposits (no single-writer
 * constraint); a disk store may instead close the window with a freshness check on the
 * hit path (the plan §1 ordering paragraph), and must then re-derive the no-stale-hit
 * argument explicitly.
 */
public interface LodStoreService {

    /** Row body layout tags ({@code wirefmt} column, C4/XVER §5): {@code 19} = the
     *  pre-v0.10.0 native section layout, {@code 20} = the canonical v20 dictionary
     *  form every post-C2 row deposits as. */
    int WIREFMT_NATIVE_19 = 19;
    int WIREFMT_V20 = 20;

    /** A store hit: wire-format section bytes ({@code length == 0} = all-air) plus the
     *  stored column timestamp. {@code wirefmt} (C4, XVER §5): the row's body format —
     *  20 = the canonical v20 dictionary layout; 19 = a pre-migration native-layout row
     *  (the lazy-upgraded v0.9.x store), which the serve rung must translate to v20
     *  before it enters the pipeline (raw() == v20 is a C2 invariant). */
    record StoreHit(byte[] sectionBytes, long columnTimestamp, int wirefmt) {
        public StoreHit(byte[] sectionBytes, long columnTimestamp) {
            this(sectionBytes, columnTimestamp, 20);
        }
    }

    /** A frame-form store hit (protocol 19 verbatim serving —
     *  compressed-columns-implementation-plan.md §3): the stored zstd frame, its
     *  validated uncompressed size, and the stored column timestamp.
     *  {@code usize == 0} = all-air ({@code frame} is empty). {@code wirefmt}: see
     *  {@link StoreHit} — a 19 row's decompressed body is NATIVE layout. */
    record FrameHit(byte[] frame, int usize, long columnTimestamp, int wirefmt) {
        public FrameHit(byte[] frame, int usize, long columnTimestamp) {
            this(frame, usize, columnTimestamp, 20);
        }
    }

    /** The store's content hash (CRC32C since schema v4, zero-extended into the 64-bit
     *  hash columns): {@code chash} over raw bytes, {@code fhash} over the compressed
     *  frame. ONE canonical implementation — the frame-reuse deposit computes both on
     *  the processing thread and the store validates against them, so the function must
     *  be shared, not twinned.
     *
     *  <p>CRC32C replaced FNV-1a 64 in the perf round (Phase 2 / R4): the serial byte
     *  loop was ~28% of the SQLite batcher thread under deposit load, and CRC32C runs
     *  as a hardware intrinsic. This is CORRUPTION DETECTION ONLY — never an
     *  identity/dedup key, never crosses a jar boundary; detection strength drops
     *  2^-64 to 2^-32 per corrupted row on DERIVED data whose worst case is one wrong
     *  LOD column the purge ladder then deletes. {@code usize == 0} short-circuits
     *  before any hash compare, so the CRC-of-empty degenerate value is never
     *  consulted. ({@code DirtyContentFilter.fnv1a64} is a separate function with its
     *  own 0-sentinel and stays FNV.)
     *
     *  <p><b>{@code new CRC32C()} per call — never a static field, never a
     *  ThreadLocal:</b> the instance is mutable and non-thread-safe, and this is called
     *  from three thread families (batcher, reader pool, processing thread); a shared
     *  instance produces wrong hashes that drive the row-poison purge into deleting
     *  good rows. The cost is the update intrinsic, not the allocation. */
    static long contentHash(byte[] data) {
        var crc = new java.util.zip.CRC32C();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    /** FNV-1a 64 — the schema-3 / {@code wirefmt = 19} row validator, RENAMED AND KEPT
     *  (mega plan R-1) rather than deleted: C4's lazy migration dispatches per-row hash
     *  validation on the {@code wirefmt} column, and legacy rows validate against THIS
     *  function. Not called between B2 and C4 (dev-window builds drop-and-rebuild via
     *  the schema bump instead); deleting it "as dead" re-introduces it wrong at C4. */
    static long legacyContentHashFnv(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** C4: wire the native→v20 body translator the background migration walk uses
     *  (the same platform function the serve rung gets). Default: ignored — only the
     *  SQLite tier migrates; an unwired translator leaves the walk waiting while
     *  serves still translate via the reader rung. */
    default void setLegacyMigrationTranslator(java.util.function.UnaryOperator<byte[]> t) {}

    /** C4: the background-migration status token for `/lsslod store status` — empty
     *  when no walk is pending, else e.g. {@code " migrating=1234/98765"}. */
    default String migrationStatusToken() {
        return "";
    }

    /** The mode this store was built for (OFF vs FULL — the SQLite engine). */
    LodStoreMode mode();

    /** Look up a column; null = miss. Reader-pool threads. Must never throw — internal
     *  failures are contained, counted {@code store.errors}, and read as a miss. */
    StoreHit get(String dimension, long packed);

    /**
     * Frame-form lookup (protocol 19 verbatim serving): the stored zstd frame WITHOUT a
     * decompress — integrity comes from frame-level validation ({@code fhash} + declared
     * content size, plan §0.1) instead of the raw-hash-after-decompress {@link #get}
     * pays. Null = miss (same containment contract as get) — or "frames unsupported"
     * (the default), which callers must treat as a plain miss and NOT retry via get()
     * within the same rung (the reader consults exactly one of the two per submit,
     * gated on {@code serveStoreFrames}).
     */
    default FrameHit getFrame(String dimension, long packed) {
        return null;
    }

    /**
     * Enqueue a deposit (processing thread; the write happens on the batcher thread).
     * {@code sectionBytes} null or empty both mean all-air and are normalized to
     * {@code byte[0]} at this boundary. A full queue sheds the OLDEST entry (counted
     * {@code store.deposit_drops}) — the store is derived data; a shed deposit
     * re-deposits on the next serve.
     *
     * <p>{@code srcStampSeconds}: the epoch second the deposited bytes were ACQUIRED
     * (read start / generation serialization) — the freshness sweep's {@code header >=
     * src_stamp} comparison needs a stamp no later than acquisition, or a save landing
     * in the acquisition→deposit gap becomes permanently sweep-invisible (4-agent round
     * R1-M2). {@code <= 0} = unknown; the store stamps at deposit-call time.
     *
     * @return true if the deposit was queued WITHOUT shedding anything; false when the
     *     store refused it (shutdown/latched) or another queued deposit was shed to
     *     admit it. The backfill's done-mark guard keys on this (review B8 — the old
     *     global drop-counter snapshot let any serve-path shed anywhere veto every
     *     region's durable progress); serve-path callers may ignore it.
     */
    boolean deposit(String dimension, long packed, byte[] sectionBytes, long columnTimestamp,
                    long srcStampSeconds);

    /** Legacy 4-arg shape (tests, callers without an acquisition stamp). */
    default boolean deposit(String dimension, long packed, byte[] sectionBytes,
                            long columnTimestamp) {
        return deposit(dimension, packed, sectionBytes, columnTimestamp, 0L);
    }

    /**
     * Pre-compressed deposit (plan §3 "compress once, use twice"): a compressed session
     * already built the wire frame, so the store enqueues the FRAME with caller-computed
     * {@code usize}/{@code chash} (raw hash)/{@code fhash} (frame hash — both via
     * {@link #contentHash}) instead of re-compressing raw on the batcher. Never used for
     * all-air (that stays the raw path's {@code byte[0]} normalization).
     *
     * @return true when the frame deposit was ENQUEUED (shedding an older entry is still
     *     true — unlike {@link #deposit}'s no-shed contract, because the caller's
     *     fallback on false is a RAW deposit and a shed-as-false would double-deposit);
     *     false when unsupported (this default) or the store is down — the caller then
     *     falls back to the raw {@link #deposit}, which no-ops harmlessly on a down store.
     */
    default boolean depositFrame(String dimension, long packed, byte[] frame, int usize,
                                 long chash, long fhash, long columnTimestamp,
                                 long srcStampSeconds) {
        return false;
    }

    /** Synchronously drop the given positions (the dirty/edit invalidation fan-out). */
    void invalidate(String dimension, long[] positions);

    /** Synchronously drop one position (the not-found ghost guard: disk said a
     *  previously-served chunk no longer exists — without this the store re-serves
     *  deleted terrain forever). */
    void delete(String dimension, long packed);

    /** The store's counter family (the same instance the exporters read). */
    LodStoreDiagnostics diagnostics();

    /** One-word health state for status surfaces: "ok" (serving), "sweeping" (startup
     *  freshness pass running — reads miss by design), "latched" (disabled for the
     *  session — writes no-op, reads miss). Review B1: the status command is the
     *  documented "are LODs stale?" triage tool and must not render a dead store as a
     *  healthy one with frozen counters. */
    default String stateToken() {
        return "ok";
    }

    /** Stop the batcher thread (store content is derived data either way). */
    void shutdown();
}
