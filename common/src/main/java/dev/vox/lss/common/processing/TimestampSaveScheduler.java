package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.voxel.ColumnTimestampCache;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Latest-wins coalescing scheduler for timestamp-cache saves (issue #62).
 *
 * <p>The old shape enqueued one closure per due save, each capturing its own
 * {@code snapshotForSave()} deep copy, onto the save executor's unbounded queue. Under the
 * ~2 s invalidation-debounce cadence, any save slower than the cadence (the pre-buffering
 * per-{@code writeLong} IO at large cache sizes) grew that queue without bound — tens of
 * ~42 MB snapshots retained at once, OOMing the server. Here the snapshot lands in a
 * single latest-wins slot instead: each empty→full transition queues exactly ONE drain
 * task (which captures nothing — it reads the slot), and a snapshot overwritten before its
 * drain runs is simply dropped in favor of the strictly newer one, the same silent-drop
 * philosophy as the want-set's supersession. Worst-case retention is one snapshot being
 * written plus one in the slot, no matter how slow the disk is.
 *
 * <p>The executor must be the processor's single-threaded FIFO save executor: a
 * multi-threaded executor could run two drains concurrently and publish an older snapshot's
 * atomic rename after a newer one's. That guarantee is per-instance — cross-instance
 * ordering across a Paper {@code /reload} overlap (old instance's final save vs the new
 * instance's periodic saves) remains governed by the unique tmp names + atomic rename in
 * {@link ColumnTimestampCache#save}, exactly as before.
 *
 * <p>Thread model: {@link #schedule} is called only by the processing thread;
 * {@link #drain} runs on the save executor; {@link #discardPending} is called by the
 * platform shutdown thread only after the processing thread has exited.
 */
final class TimestampSaveScheduler {

    private final AtomicReference<ColumnTimestampCache> pending = new AtomicReference<>();
    private final ExecutorService saveExecutor;
    private final Path dataDir;
    // Test observability only (the wiring pin polls it to know a due save fired without
    // racing the executor queue); monotonic, incremented solely by the processing thread.
    private final AtomicLong scheduled = new AtomicLong();

    TimestampSaveScheduler(ExecutorService saveExecutor, Path dataDir) {
        this.saveExecutor = saveExecutor;
        this.dataDir = dataDir;
    }

    /**
     * Publish the newest snapshot (latest-wins) and queue a drain only on the empty→full
     * transition — at most one drain task is ever queued per slot-fill, so the executor
     * queue depth is bounded regardless of how slow saves are.
     */
    void schedule(ColumnTimestampCache snapshot) {
        if (this.pending.getAndSet(snapshot) == null) {
            try {
                this.saveExecutor.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // Only reachable when the processing thread outlived the shutdown join and
                // the executor was shut down under it — the same corner where the final save
                // is skipped today. The snapshot is dropped, matching the pre-coalescing
                // behavior of this race; clear the slot so nothing pins the copy.
                this.pending.set(null);
                LSSLogger.debug("Skipped periodic timestamp cache save — save executor is shutting down");
            }
        }
        // Incremented LAST: a test observing the count sees the schedule's side effects.
        this.scheduled.incrementAndGet();
    }

    /**
     * Shutdown hook: the final save (a fresh snapshot taken after the processing thread
     * exited) supersedes anything still pending — a queued-but-not-started drain then
     * no-ops instead of spending part of the shutdown window on a stale full-file write.
     */
    void discardPending() {
        this.pending.set(null);
    }

    private void drain() {
        var snapshot = this.pending.getAndSet(null);
        if (snapshot != null) {
            snapshot.save(this.dataDir);
        }
    }

    /** How many schedule() calls have been made — wiring-pin observability. */
    long scheduledForTest() {
        return this.scheduled.get();
    }
}
