package dev.vox.lss.common.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The region-summary counter family (region-summary-sync-plan.md §8). Monotonic
 * counters (exporter whitelist members) plus the sweeper's per-request assembly-time
 * high-water gauge. Multi-thread producers (ingress, tick pump, sweeper) — all atomic.
 */
public final class RegionSummaryDiagnostics {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong rangeFiltered = new AtomicLong();
    private final AtomicLong tilesKnown = new AtomicLong();
    private final AtomicLong tilesNeverClean = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong refreshMsMax = new AtomicLong();

    /** One C2S request accepted at ingress (pre-clamp, pre-admission). */
    public void recordRequest() { this.requests.incrementAndGet(); }

    /** One request whose CENTER was clamped to the player's window (the
     *  range_filtered rule applied to tiles — hostile/stale centers are harmless
     *  because they can only select in-window table reads, but they are counted). */
    public void recordRangeFiltered() { this.rangeFiltered.incrementAndGet(); }

    /** Tile dispositions of one assembled frame. */
    public void recordTiles(long known, long neverClean) {
        this.tilesKnown.addAndGet(known);
        this.tilesNeverClean.addAndGet(neverClean);
    }

    /** One S2C frame sent on the dedicated lane (NOT part of service.bytes_sent —
     *  the far-player lane precedent; cross-identity audits stay exact). */
    public void recordFrameSent(int frameBytes) {
        this.frames.incrementAndGet();
        this.bytes.addAndGet(frameBytes);
    }

    /** One frame assembly's wall duration — high-water only (gauge, never exported
     *  as a monotonic counter). */
    public void recordRefreshMillis(long ms) {
        this.refreshMsMax.accumulateAndGet(ms, Math::max);
    }

    /** The {@code /lsslod diag} one-liner, null while the feature is untouched (no
     *  request ever arrived — soak/benchmark diag output stays byte-unchanged). */
    public String diagLineOrNull() {
        long reqs = this.requests.get();
        if (reqs == 0) return null;
        return String.format(
                "Summary: reqs=%d, frames=%d, tiles known=%d never_clean=%d, range_filtered=%d, bytes=%d, refresh_ms_max=%d",
                reqs, this.frames.get(), this.tilesKnown.get(), this.tilesNeverClean.get(),
                this.rangeFiltered.get(), this.bytes.get(), this.refreshMsMax.get());
    }

    public long getRequests() { return this.requests.get(); }
    public long getRangeFiltered() { return this.rangeFiltered.get(); }
    public long getTilesKnown() { return this.tilesKnown.get(); }
    public long getTilesNeverClean() { return this.tilesNeverClean.get(); }
    public long getBytes() { return this.bytes.get(); }
    public long getFrames() { return this.frames.get(); }
    public long getRefreshMsMax() { return this.refreshMsMax.get(); }
}
