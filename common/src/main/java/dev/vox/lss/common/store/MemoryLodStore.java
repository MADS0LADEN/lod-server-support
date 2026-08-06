package dev.vox.lss.common.store;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Phase 1 bounded in-memory LOD store tier (plan §1 "memory tier = disposable
 * integration spike"): compressed entries, RANDOM eviction, a single batcher thread for
 * deposits. Exists primarily to prove the rung/counters/parity plumbing before SQLite;
 * Phase 2 runs the memory-vs-SQLite-alone A/B with "delete the tier" as an explicitly
 * permitted outcome.
 *
 * <p><b>Eviction is RANDOM, deliberately</b> (the "scan-resistant (SLRU/2Q or random —
 * measured)" decision): the workload is closest-first scans over a disc larger than the
 * cap, where each position is touched once per join. LRU is scan-pathological there —
 * during a populate pass it evicts the OLDEST = nearest columns, so the next
 * closest-first scan misses its whole head and re-deposits it, evicting what the tail
 * needs: hit rate ≈ 0 by construction. Random keeps a uniform sample of the disc, so
 * hit rate ≈ cap/working-set — the best a single-touch workload admits. Measured by
 * {@code MemoryLodStoreTest}'s corpus-replay curve.
 *
 * <p><b>Threading.</b> {@code get}: reader-pool threads (CHM read + decompress — the
 * right place to spend decompress CPU, off the main and processing threads).
 * {@code deposit}: processing thread enqueue only; ALL map inserts + eviction happen on
 * the batcher thread (single writer — the eviction key list is batcher-confined).
 * {@code invalidate}/{@code delete}: processing thread, SYNCHRONOUS map removal — this
 * closes the edit-then-stale-hit window outright (no SQLite single-writer constraint in
 * this tier; the key list tolerates the resulting ghosts). The batcher contract imposed
 * by the soak quiescence gate (check_soak SERVER_DRAINS, Phase 0 review F1) holds by
 * construction: the queue drains continuously (blocking take, no sub-batch holding) and
 * the {@code store.queue} gauge is updated on DRAIN.
 */
public final class MemoryLodStore implements LodStoreService {

    private record Entry(byte[] compressed, int usize, long ts) {
        int residentBytes() { return this.compressed.length + 64; } // approx per-entry overhead
    }

    /** {@code preFramed}: {@code bytes} IS the compressed frame ({@code usize}
     *  caller-supplied) — the batcher skips its compress (plan §3 reuse). */
    private record Deposit(String dimension, long packed, byte[] bytes, long ts,
                           long enqueuedNanos, boolean preFramed, int usize) {
        Deposit(String dimension, long packed, byte[] bytes, long ts, long enqueuedNanos) {
            this(dimension, packed, bytes, ts, enqueuedNanos, false, 0);
        }
    }

    private static final byte[] EMPTY = new byte[0];
    private static final int QUEUE_CAPACITY = 1024;
    /** Rebuild the eviction key list when ghosts (deleted/invalidated keys) dominate. */
    private static final int KEY_LIST_SLACK_FACTOR = 4;

    private final LodStoreMode mode;
    private final LodStoreDiagnostics diag;
    private final StoreCodec codec;
    private final long maxBytes;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Entry>> byDimension =
            new ConcurrentHashMap<>();
    private final AtomicLong residentBytes = new AtomicLong();

    private final ArrayBlockingQueue<Deposit> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread batcher;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean errorWarned = new AtomicBoolean();

    // Batcher-thread-confined: candidate keys for random eviction. May contain ghosts
    // (synchronously deleted keys) — eviction skips them; a slack-factor rebuild bounds
    // the ghost population under edit churn.
    private final ArrayList<String> keyDims = new ArrayList<>();
    private final it.unimi.dsi.fastutil.longs.LongArrayList keyPositions =
            new it.unimi.dsi.fastutil.longs.LongArrayList();
    private final Random evictionRandom = new Random();

    // Invalidation tombstones — the queue-latency poison guard: a deposit ENQUEUED
    // before an invalidation/delete of the same position must never apply AFTER it (the
    // sync map removal alone can't stop a deposit still in the queue — or mid-apply —
    // from resurrecting pre-edit bytes, which the next re-ask would then hit and
    // re-stamp: sealed stale terrain, exactly what post-stale-guard deposits exist to
    // prevent). invalidate()/delete() stamp (dim,packed)->nanos; the batcher skips any
    // deposit enqueued at-or-before its position's tombstone. Swept by age on the
    // batcher (edits are rare relative to deposits, the map stays small).
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> tombstones =
            new ConcurrentHashMap<>();
    private static final long TOMBSTONE_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private long lastTombstoneSweepNanos = System.nanoTime();

    /** Null when the codec probe fails (unsupported native) — the caller warns once and
     *  runs without a store (the documented degrade, never a crash). */
    public static MemoryLodStore createOrNull(LodStoreMode mode, long maxBytes) {
        StoreCodec codec = StoreCodec.zstdOrNull();
        if (codec == null) return null;
        return new MemoryLodStore(mode, codec, maxBytes, new LodStoreDiagnostics());
    }

    /** Diagnostics are INJECTED so a tiered composition (memory in front of SQLite,
     *  Phase 2) reports through one counter family the exporters already read. */
    MemoryLodStore(LodStoreMode mode, StoreCodec codec, long maxBytes, LodStoreDiagnostics diag) {
        this.mode = mode;
        this.codec = codec;
        this.maxBytes = maxBytes;
        this.diag = diag;
        this.batcher = new Thread(this::batcherLoop, Brand.shortName() + " LOD Store Batcher");
        this.batcher.setDaemon(true);
        this.batcher.setPriority(Thread.MIN_PRIORITY + 1);
        this.batcher.start();
    }

    @Override
    public LodStoreMode mode() {
        return this.mode;
    }

    @Override
    public StoreHit get(String dimension, long packed) {
        try {
            var dim = this.byDimension.get(dimension);
            if (dim == null) return null;
            Entry e = dim.get(packed);
            if (e == null) return null;
            if (e.usize() == 0) {
                this.diag.recordMemHit();
                return new StoreHit(EMPTY, e.ts());
            }
            byte[] raw = this.codec.decompress(e.compressed(), e.usize());
            if (raw.length != e.usize()) throw new IllegalStateException(
                    "decompressed " + raw.length + " != stored usize " + e.usize());
            // Counted only AFTER a successful decompress (review MINOR-4): a failed hit
            // resolves as an errored miss below and must not inflate mem_hits past hits.
            this.diag.recordMemHit();
            return new StoreHit(raw, e.ts());
        } catch (Throwable t) {
            // Contained: count, warn once, DELETE the poisoned entry (a repeatable
            // decompress failure must not re-fail every re-declaration), read as a miss
            // — the NBT ladder serves it and the delivery path re-deposits.
            this.diag.recordError();
            delete(dimension, packed);
            if (this.errorWarned.compareAndSet(false, true)) {
                LSSLogger.warn("LOD store read failed — served from disk instead (counted"
                        + " store.errors; further failures are silent)", t);
            }
            return null;
        }
    }

    /** The resident frame VERBATIM (plan §3) — deliberately unvalidated (review A8,
     *  accepted: session-lifetime RAM is not the bit-rot threat model; a step below
     *  {@link #get}'s round-trip length check, stated so it isn't rediscovered). */
    @Override
    public FrameHit getFrame(String dimension, long packed) {
        var dim = this.byDimension.get(dimension);
        if (dim == null) return null;
        Entry e = dim.get(packed);
        if (e == null) return null;
        this.diag.recordMemHit();
        return e.usize() == 0 ? new FrameHit(EMPTY, 0, e.ts())
                : new FrameHit(e.compressed(), e.usize(), e.ts());
    }

    @Override
    public boolean depositFrame(String dimension, long packed, byte[] frame, int usize,
                                long chash, long fhash, long columnTimestamp,
                                long srcStampSeconds) {
        // chash/fhash unused: the memory tier stores no hashes (see getFrame's stated
        // no-validation stance). All-air never rides the frame path.
        if (frame == null || frame.length == 0 || usize <= 0) return false;
        if (this.shutdown.get()) return false;
        var dep = new Deposit(dimension, packed, frame, columnTimestamp,
                System.nanoTime(), true, usize);
        while (!this.queue.offer(dep)) {
            // In-loop shutdown re-check (SQLite-twin parity, 2026-08-05 review H4): after
            // shutdown the batcher is dead and the queue cleared, so a racing deposit
            // would otherwise park entries in the dead queue permanently.
            if (this.shutdown.get()) return false;
            if (this.queue.poll() != null) {
                this.diag.recordDepositDrop();
                // Shed still returns TRUE — see the SQLite twin's depositFrame comment.
            }
        }
        return true;
    }

    @Override
    public boolean deposit(String dimension, long packed, byte[] sectionBytes, long columnTimestamp,
                           long acquiredEpochSeconds) {
        // acquiredEpochSeconds unused: the memory store has no freshness sweep — it
        // dies with the session, so region-header comparisons never apply to it.
        if (this.shutdown.get()) return false;
        byte[] normalized = sectionBytes == null || sectionBytes.length == 0 ? EMPTY : sectionBytes;
        var dep = new Deposit(dimension, packed, normalized, columnTimestamp, System.nanoTime());
        boolean shed = false;
        while (!this.queue.offer(dep)) {
            // In-loop shutdown re-check (SQLite-twin parity, 2026-08-05 review H4) — see
            // depositFrame.
            if (this.shutdown.get()) return false;
            // Shed the OLDEST (head) — the store is derived data; a shed deposit
            // re-deposits on the next serve of that column.
            if (this.queue.poll() != null) {
                this.diag.recordDepositDrop();
                shed = true;
            }
        }
        // Deliberately NO gauge write here: store.queue is a DRAIN-SIDE gauge (the
        // SERVER_DRAINS contract — the SQLite twin documents the burn-in red a
        // producer-side write caused; R2 found this twin kept the old shape).
        return !shed;
    }

    @Override
    public void invalidate(String dimension, long[] positions) {
        long now = System.nanoTime();
        var tombs = this.tombstones.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        var dim = this.byDimension.get(dimension);
        for (long packed : positions) {
            tombs.put(packed, now);
            if (dim != null) {
                Entry removed = dim.remove(packed);
                if (removed != null) {
                    this.residentBytes.addAndGet(-removed.residentBytes());
                }
            }
        }
        this.diag.setMemBytes(this.residentBytes.get());
    }

    @Override
    public void delete(String dimension, long packed) {
        this.tombstones.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                .put(packed, System.nanoTime());
        var dim = this.byDimension.get(dimension);
        if (dim == null) return;
        Entry removed = dim.remove(packed);
        if (removed != null) {
            this.residentBytes.addAndGet(-removed.residentBytes());
            this.diag.setMemBytes(this.residentBytes.get());
        }
    }

    @Override
    public LodStoreDiagnostics diagnostics() {
        return this.diag;
    }

    @Override
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) return;
        this.batcher.interrupt();
        try {
            this.batcher.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.byDimension.clear();
        this.residentBytes.set(0);
        this.queue.clear();
        this.diag.setMemBytes(0);
        this.diag.setQueueDepth(0);
    }

    // ---- batcher thread ----

    private void batcherLoop() {
        while (!this.shutdown.get()) {
            Deposit dep;
            try {
                dep = this.queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return; // shutdown
            }
            if (dep == null) {
                // Idle sweep (review MINOR-2): invalidations tombstone on every edit even
                // with zero LSS clients online (no deposits → the apply-side sweep never
                // runs) — days of edits would otherwise accumulate tombstones unboundedly.
                sweepTombstones(System.nanoTime());
                continue;
            }
            try {
                applyDeposit(dep);
            } catch (Throwable t) {
                this.diag.recordError();
                if (this.errorWarned.compareAndSet(false, true)) {
                    LSSLogger.warn("LOD store deposit failed (counted store.errors;"
                            + " further failures are silent)", t);
                }
            }
            // Drain-side gauge update (the check_soak SERVER_DRAINS batcher contract).
            this.diag.setQueueDepth(this.queue.size());
        }
    }

    private void applyDeposit(Deposit dep) {
        // Tombstone check — the queue-latency poison guard (see the field comment). An
        // invalidation stamped at-or-after this deposit's enqueue means the deposit's
        // bytes may predate the edit: skip silently (the next serve re-deposits).
        var tombs = this.tombstones.get(dep.dimension());
        if (tombs != null) {
            Long t = tombs.get(dep.packed());
            if (t != null && t >= dep.enqueuedNanos()) {
                this.diag.recordDepositSkip();
                return;
            }
        }
        var dim = this.byDimension.computeIfAbsent(dep.dimension(), k -> new ConcurrentHashMap<>());
        // Latest-wins by STORED timestamp: an older deposit never overwrites a newer row
        // (equal stamps overwrite — same-second serves carry equivalent content).
        Entry existing = dim.get(dep.packed());
        if (existing != null && existing.ts() > dep.ts()) {
            this.diag.recordDepositSkip();
            return;
        }
        byte[] compressed;
        int usize;
        if (dep.preFramed()) {
            compressed = dep.bytes(); // the wire frame, verbatim (plan §3 reuse)
            usize = dep.usize();
        } else {
            compressed = dep.bytes().length == 0 ? EMPTY : this.codec.compress(dep.bytes());
            usize = dep.bytes().length;
        }
        var entry = new Entry(compressed, usize, dep.ts());
        Entry replaced = dim.put(dep.packed(), entry);
        this.diag.recordDeposit();
        this.residentBytes.addAndGet(entry.residentBytes());
        if (replaced != null) {
            this.residentBytes.addAndGet(-replaced.residentBytes());
        } else {
            this.keyDims.add(dep.dimension());
            this.keyPositions.add(dep.packed());
        }
        // Tombstone RE-check after the put: an invalidate interleaving between the first
        // check and the put would otherwise be resurrected by it (invalidate orders
        // tombstone-stamp BEFORE map-remove; we order map-put BEFORE this re-read, so
        // every interleaving either lets invalidate's own remove win or lands here).
        tombs = this.tombstones.get(dep.dimension());
        if (tombs != null) {
            Long t2 = tombs.get(dep.packed());
            if (t2 != null && t2 >= dep.enqueuedNanos() && dim.remove(dep.packed(), entry)) {
                this.residentBytes.addAndGet(-entry.residentBytes());
            }
        }
        rebuildKeyListIfGhostHeavy();
        evictIfOver();
        this.diag.setMemBytes(this.residentBytes.get());
        sweepTombstones(dep.enqueuedNanos());
    }

    /** Deletes/invalidations leave ghosts in the eviction key list; rebuild it from the
     *  live map when ghosts dominate (batcher thread — the list is batcher-confined). */
    private void rebuildKeyListIfGhostHeavy() {
        int live = 0;
        for (var dim : this.byDimension.values()) live += dim.size();
        if (this.keyPositions.size() <= (long) KEY_LIST_SLACK_FACTOR * Math.max(64, live)) return;
        this.keyDims.clear();
        this.keyPositions.clear();
        for (var e : this.byDimension.entrySet()) {
            for (Map.Entry<Long, Entry> pos : e.getValue().entrySet()) {
                this.keyDims.add(e.getKey());
                this.keyPositions.add(pos.getKey());
            }
        }
    }

    private void sweepTombstones(long nowNanos) {
        if (nowNanos - this.lastTombstoneSweepNanos < TOMBSTONE_TTL_NANOS) return;
        this.lastTombstoneSweepNanos = nowNanos;
        for (var tombs : this.tombstones.values()) {
            tombs.values().removeIf(stamp -> nowNanos - stamp > TOMBSTONE_TTL_NANOS);
        }
    }

    private void evictIfOver() {
        while (this.residentBytes.get() > this.maxBytes && !this.keyPositions.isEmpty()) {
            int idx = this.evictionRandom.nextInt(this.keyPositions.size());
            String dimName = this.keyDims.get(idx);
            long packed = this.keyPositions.getLong(idx);
            int last = this.keyPositions.size() - 1;
            this.keyDims.set(idx, this.keyDims.get(last));
            this.keyPositions.set(idx, this.keyPositions.getLong(last));
            this.keyDims.remove(last);
            this.keyPositions.removeLong(last);
            var dim = this.byDimension.get(dimName);
            if (dim == null) continue;
            Entry removed = dim.remove(packed);
            if (removed != null) {
                this.residentBytes.addAndGet(-removed.residentBytes());
                this.diag.recordMemEviction();
            }
            // else: a ghost (synchronously deleted key) — skip, not an eviction
        }
    }

    // ---- test seams ----

    /** Blocks until the batcher has drained the queue (test determinism). */
    public void awaitQuiesceForTest(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while ((!this.queue.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        // one more beat: the last taken deposit may still be applying
        Thread.sleep(20);
    }

    /** Live entry count across dimensions (test observability). */
    public int sizeForTest() {
        int n = 0;
        for (var dim : this.byDimension.values()) n += dim.size();
        return n;
    }
}
