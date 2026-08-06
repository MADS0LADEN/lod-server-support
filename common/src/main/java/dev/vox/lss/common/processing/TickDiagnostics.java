package dev.vox.lss.common.processing;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;

/**
 * Tracks per-tick diagnostic counters for RequestProcessingService.
 * Maintains a "last tick" snapshot and a "current tick" accumulator.
 * Call {@link #reset(ProcessingDiagnostics)} at the start of each tick to snapshot
 * the current tick values and reset accumulators.
 */
public class TickDiagnostics {
    // Last-tick snapshot (read by diagnostics/logging)
    private int lastTickSectionsSent;
    private int lastTickDiskQueued;
    private int lastTickDiskDrained;
    private int lastTickGenDrained;
    private int lastTickInMemorySerialized;
    private int lastTickBytesFlushed;
    private int lastTickQueuePeak;
    private int lastTickSkippedDuplicate;
    private int lastTickUpToDate;

    // Current-tick accumulators (written during tick processing)
    private int curTickSectionsSent;
    private int curTickBytesFlushed;
    private int curTickQueuePeak;

    // Cumulative send counters — service-scoped, so they survive the per-player state
    // teardown on kick and dimension change. Single-writer (main/pump thread), volatile
    // because /lsslod stats|diag reads them from the invoking player's REGION thread on
    // Folia (2026-08-05 review H3 — the PaperChunkGenerationService house rule: counters
    // a command renders must be JMM-visible off the writer thread).
    private volatile long totalSectionsSent;
    private volatile long totalBytesSent;
    private volatile long totalWireBytesSent;

    // Sliding window bandwidth rate (~5s at 20 TPS). The scalars are volatile for the
    // same Folia command-thread reads as the totals above; the ring ARRAYS stay plain, so
    // a cross-thread getWindowBytesPerSecond() is best-effort — a mid-reset read can
    // misreport one command's rate (diag-only, bounded, self-corrects next invocation).
    private static final int WINDOW_TICKS = 100;
    private final int[] byteRing = new int[WINDOW_TICKS];
    private final long[] nanosRing = new long[WINDOW_TICKS];
    private volatile long windowByteSum;
    private volatile int ringPos;
    private volatile int ringCount;

    /**
     * Snapshot current tick values into last-tick fields, pull off-thread counters,
     * and reset current tick accumulators.
     */
    public void reset(ProcessingDiagnostics diag) {
        // Push current tick into sliding window before resetting
        windowByteSum -= byteRing[ringPos];
        byteRing[ringPos] = curTickBytesFlushed;
        nanosRing[ringPos] = System.nanoTime();
        windowByteSum += curTickBytesFlushed;
        ringPos = (ringPos + 1) % WINDOW_TICKS;
        if (ringCount < WINDOW_TICKS) ringCount++;

        this.lastTickSectionsSent = this.curTickSectionsSent;
        this.lastTickDiskQueued = diag.getLastDiskQueued();
        this.lastTickDiskDrained = diag.getLastDiskDrained();
        this.lastTickGenDrained = diag.getLastGenDrained();
        this.lastTickInMemorySerialized = diag.getLastInMemory();
        this.lastTickBytesFlushed = this.curTickBytesFlushed;
        this.lastTickQueuePeak = this.curTickQueuePeak;
        this.lastTickSkippedDuplicate = diag.getLastSkippedDuplicate();
        this.lastTickUpToDate = diag.getLastUpToDate();
        this.curTickSectionsSent = 0;
        this.curTickBytesFlushed = 0;
        this.curTickQueuePeak = 0;
    }

    public long getWindowBytesPerSecond() {
        if (ringCount < 2) return 0;
        int newestIdx = (ringPos - 1 + WINDOW_TICKS) % WINDOW_TICKS;
        int oldestIdx = ringCount < WINDOW_TICKS ? 0 : ringPos;
        long elapsedNanos = nanosRing[newestIdx] - nanosRing[oldestIdx];
        if (elapsedNanos <= 0) return 0;
        // N samples span N-1 intervals: the oldest bucket's bytes flushed during the tick
        // ENDING at its own stamp — before the measured span began — so it is excluded
        // from the numerator (including it inflated the rate ~N/(N-1): +100% at
        // ringCount 2, ~+1% at the full window).
        long spanBytes = windowByteSum - byteRing[oldestIdx];
        return spanBytes * LSSConstants.NANOS_PER_SECOND / elapsedNanos;
    }

    public void recordSectionSent(int estimatedBytes) {
        this.curTickSectionsSent++;
        this.curTickBytesFlushed += estimatedBytes;
        this.totalSectionsSent++;
        this.totalBytesSent += estimatedBytes;
    }

    /** Shipped payload size at send success (frame for codec-1 columns) — the counted
     *  wire volume that matches observed bandwidth, next to the raw-denominated
     *  {@link #getTotalBytesSent} the limiter charges (compressed-columns plan §4). */
    public void recordWireSent(int wireBytes) {
        this.totalWireBytesSent += wireBytes;
    }

    public long getTotalSectionsSent() { return this.totalSectionsSent; }
    public long getTotalBytesSent() { return this.totalBytesSent; }
    public long getTotalWireBytesSent() { return this.totalWireBytesSent; }

    public void updateQueuePeak(int queueSize) {
        this.curTickQueuePeak = Math.max(this.curTickQueuePeak, queueSize);
    }

    public String format(int maxSendQueueSize) {
        return String.format("sent=%d, disk=%d/%d, utd=%d, gen=%d, in_mem=%d, skipped=%d, bytes=%s, qpeak=%d/%d",
                lastTickSectionsSent, lastTickDiskDrained, lastTickDiskQueued,
                lastTickUpToDate, lastTickGenDrained,
                lastTickInMemorySerialized, lastTickSkippedDuplicate,
                DiagnosticsFormatter.formatBytes(lastTickBytesFlushed),
                lastTickQueuePeak, maxSendQueueSize);
    }

    public String formatSummary(long bwRate, long maxBytesPerSecondGlobal) {
        return String.format("sent=%d/tick, disk=%d/%d, utd=%d, bw=%s/%s",
                lastTickSectionsSent, lastTickDiskDrained, lastTickDiskQueued,
                lastTickUpToDate,
                DiagnosticsFormatter.formatBytes(bwRate), DiagnosticsFormatter.formatBytes(maxBytesPerSecondGlobal));
    }

}
