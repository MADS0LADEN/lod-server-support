package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.function.LongSupplier;

/**
 * Per-player token bucket that meters outgoing bandwidth.
 *
 * <p>Debt-carrying (see {@link dev.vox.lss.common.SharedBandwidthLimiter}): the old
 * zero-floor in {@link #recordSend} forgave the whole overdraft, so any payload larger
 * than the per-tick refill shipped every tick regardless of the configured cap. The
 * presence gate in {@link #canSend} still admits one oversized payload (a sufficiency
 * gate would deadlock payloads above the burst cap); the debt then blocks this player's
 * next send until the refill pays it down — sustained rate converges to the cap, debt
 * bounded by one payload beyond zero.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from the
 * main server thread (tick loop).
 */
public class PlayerBandwidthTracker {
    private static final int BURST_DIVISOR = 4; // 250ms burst window (~5 ticks at 20 TPS)

    private final LongSupplier nanoClock;
    private long availableTokens = 0;
    private long lastRefillNanos;
    private long totalSectionsSent = 0;
    private long totalBytesSent = 0;

    public PlayerBandwidthTracker() {
        this(System::nanoTime);
    }

    /** Clock-injected flavor (tests). The clock also seeds the refill anchor. */
    public PlayerBandwidthTracker(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public boolean canSend(long allocationBytes) {
        if (allocationBytes <= 0) return false;
        long now = this.nanoClock.getAsLong();
        long elapsedNanos = now - this.lastRefillNanos;
        if (elapsedNanos >= LSSConstants.NANOS_PER_MS) { // skip sub-millisecond refills
            if (elapsedNanos > LSSConstants.NANOS_PER_SECOND) {
                // Idle gap: cap the credit to 1 s AND discard the excess history — a
                // lagging anchor would otherwise keep crediting a full second per call.
                this.lastRefillNanos = now - LSSConstants.NANOS_PER_SECOND;
                elapsedNanos = LSSConstants.NANOS_PER_SECOND;
            }
            long refill = elapsedNanos * allocationBytes / LSSConstants.NANOS_PER_SECOND;
            if (refill > 0) {
                // Advance the anchor by CONSUMED time, not to `now`: the truncated
                // remainder must accumulate or threshold allocations under-refill by up
                // to ~49% and sub-threshold ones (tiny per-player splits) starve forever.
                this.lastRefillNanos += refill * LSSConstants.NANOS_PER_SECOND / allocationBytes;
                long burstCap = allocationBytes / BURST_DIVISOR;
                this.availableTokens = Math.min(this.availableTokens + refill, burstCap);
            }
        }
        return this.availableTokens > 0;
    }

    public void recordSend(int bytes) {
        this.availableTokens -= bytes; // may go negative — debt, paid down by the refill
        this.totalSectionsSent++;
        this.totalBytesSent += bytes;
    }

    public long getTotalSectionsSent() { return this.totalSectionsSent; }
    public long getTotalBytesSent() { return this.totalBytesSent; }
}
