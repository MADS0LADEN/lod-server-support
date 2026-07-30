package dev.vox.lss.common.store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the LOD store (docs/planning/lod-store-implementation-plan.md §1). The
 * store's OWN counter family: store hits are deliberately EXCLUDED from
 * {@code disk.submitted/successful/avg_read_ms} and from
 * {@code AdaptiveReadThrottle.recordLatency} — a sub-100 µs hit would collapse the AIMD
 * EWMA and blow the limit open on exactly the C2ME-latched servers where the throttle is
 * the only gameplay protection.
 *
 * <p>Unlike {@code ProcessingDiagnostics} (single-writer volatile longs, processing thread
 * only), these are {@link AtomicLong}s: hits/misses increment on reader-pool threads,
 * deposits on the batcher thread, gauges from batcher/sweep maintenance.
 *
 * <p>The object exists unconditionally (zeros while {@code lodStore=off}) so the
 * diagnostics/exporter schema is identical in every mode — the soak checker requires a
 * stable snapshot shape across the kill-switch A/B arms.
 */
public final class LodStoreDiagnostics {

    // Monotonic counters
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong deposits = new AtomicLong();
    private final AtomicLong depositDrops = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong memHits = new AtomicLong();
    private final AtomicLong memEvictions = new AtomicLong();
    // Hit-read latency (store reads only — never fed by the NBT path)
    private final AtomicLong readTotalNanos = new AtomicLong();
    private final AtomicLong readCount = new AtomicLong();

    // Gauges (set, not summed)
    private volatile long queueDepth;      // write-batcher queue depth (drains to 0 at rest)
    private volatile long memBytes;        // memory-tier resident compressed bytes
    private volatile long dbBytes;         // SQLite main file size
    private volatile long walBytes;        // SQLite -wal size
    private volatile long checkpointMsMax; // slowest TRUNCATE checkpoint observed

    public void recordHit(long readNanos) {
        this.hits.incrementAndGet();
        this.readTotalNanos.addAndGet(readNanos);
        this.readCount.incrementAndGet();
    }

    public void recordMiss() { this.misses.incrementAndGet(); }
    public void recordDeposit() { this.deposits.incrementAndGet(); }
    public void recordDepositDrop() { this.depositDrops.incrementAndGet(); }
    public void recordError() { this.errors.incrementAndGet(); }
    public void recordMemHit() { this.memHits.incrementAndGet(); }
    public void recordMemEviction() { this.memEvictions.incrementAndGet(); }

    public void setQueueDepth(long depth) { this.queueDepth = depth; }
    public void setMemBytes(long bytes) { this.memBytes = bytes; }
    public void setDbBytes(long bytes) { this.dbBytes = bytes; }
    public void setWalBytes(long bytes) { this.walBytes = bytes; }
    public void recordCheckpointMs(long ms) {
        if (ms > this.checkpointMsMax) this.checkpointMsMax = ms;
    }

    public long getHits() { return this.hits.get(); }
    public long getMisses() { return this.misses.get(); }
    public long getDeposits() { return this.deposits.get(); }
    public long getDepositDrops() { return this.depositDrops.get(); }
    public long getErrors() { return this.errors.get(); }
    public long getMemHits() { return this.memHits.get(); }
    public long getMemEvictions() { return this.memEvictions.get(); }
    public long getQueueDepth() { return this.queueDepth; }
    public long getMemBytes() { return this.memBytes; }
    public long getDbBytes() { return this.dbBytes; }
    public long getWalBytes() { return this.walBytes; }
    public long getCheckpointMsMax() { return this.checkpointMsMax; }

    /** Mean store-hit read latency in whole microseconds (0 before the first hit). */
    public long getReadAvgMicros() {
        long n = this.readCount.get();
        return n == 0 ? 0 : (this.readTotalNanos.get() / n) / 1_000;
    }

    /**
     * The {@code /lsslod diag} DiskReader-line TOKEN (never a new line — the golden-order
     * tests pin the line list). Off mode renders the bare {@code store=off}; active modes
     * carry the counters an admin needs to see convergence.
     */
    public String formatToken(LodStoreMode mode) {
        if (mode == LodStoreMode.OFF) return "store=off";
        return String.format(
                "store=%s h=%d m=%d dep=%d drop=%d err=%d q=%d avg_read=%dus",
                mode.configValue(), getHits(), getMisses(), getDeposits(),
                getDepositDrops(), getErrors(), getQueueDepth(), getReadAvgMicros());
    }
}
