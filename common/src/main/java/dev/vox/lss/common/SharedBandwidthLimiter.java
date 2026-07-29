package dev.vox.lss.common;

import java.util.function.LongSupplier;

/**
 * Token bucket bandwidth limiter that divides a global budget fairly among active players.
 * Tokens refill proportionally to elapsed real time, preventing bursty traffic patterns.
 *
 * <p>Debt-carrying: {@link #recordSend} may take the balance NEGATIVE. The old
 * zero-floor forgave the whole overdraft, so any payload larger than the per-tick refill
 * shipped every tick and the sustained rate converged to ~20 payloads/s regardless of the
 * configured cap (3-40x over on tight caps — invisible at the defaults, which is why it
 * survived). The presence gate ({@code availableTokens > 0} via
 * {@link #getPerPlayerAllocation}) still admits one oversized payload — a sufficiency
 * gate would deadlock payloads above the refill — but the debt then stalls ALL players'
 * allocations for debt/cap seconds until the refill pays it down. That shared stall is a
 * deliberate fairness change: correct global-cap enforcement over per-player isolation.
 * Instantaneous bound: the allocation is snapshotted once per tick, so N players can each
 * land one oversized payload in the tick the debt is incurred — the overshoot is one
 * payload PER PLAYER, all carried as debt and paid back before anything else ships.
 *
 * <p><b>Thread safety:</b> This class is <b>not</b> thread-safe. All methods must be called
 * from the server tick thread only. Calling from multiple threads will silently corrupt
 * internal counters.</p>
 */
public class SharedBandwidthLimiter {
    private final long maxBytesPerSecond;
    private final LongSupplier nanoClock;
    private long availableTokens;
    private long lastRefillNanos;

    private long totalBytesSent;

    public SharedBandwidthLimiter(long maxBytesPerSecond) {
        this(maxBytesPerSecond, System::nanoTime);
    }

    /** Clock-injected flavor (tests). The clock also seeds the refill anchor. */
    public SharedBandwidthLimiter(long maxBytesPerSecond, LongSupplier nanoClock) {
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.nanoClock = nanoClock;
        this.availableTokens = maxBytesPerSecond;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    private void refill() {
        long now = this.nanoClock.getAsLong();
        long elapsedNanos = now - this.lastRefillNanos;
        if (elapsedNanos < LSSConstants.NANOS_PER_MS) return; // skip sub-millisecond refills
        if (elapsedNanos > LSSConstants.NANOS_PER_SECOND) {
            // Idle gap: cap the credit to 1 s AND discard the excess history — a lagging
            // anchor would otherwise keep crediting a full second on every later call.
            this.lastRefillNanos = now - LSSConstants.NANOS_PER_SECOND;
            elapsedNanos = LSSConstants.NANOS_PER_SECOND;
        }
        long refill = elapsedNanos * this.maxBytesPerSecond / LSSConstants.NANOS_PER_SECOND;
        if (refill > 0) {
            // Advance the anchor by CONSUMED time, not to `now`: the integer division
            // truncates up to one byte's worth of nanos, and swallowing that remainder
            // every call systematically under-refills near the truncation threshold and
            // starved sub-threshold allocations completely.
            this.lastRefillNanos += refill * LSSConstants.NANOS_PER_SECOND / this.maxBytesPerSecond;
            this.availableTokens = Math.min(this.availableTokens + refill, this.maxBytesPerSecond);
        }
    }

    public long getPerPlayerAllocation(int activePlayerCount) {
        this.refill();
        if (this.availableTokens <= 0 || activePlayerCount <= 0) return 0;
        return this.availableTokens / activePlayerCount;
    }

    public void recordSend(int bytes) {
        this.availableTokens -= bytes; // may go negative — debt, paid down by the refill
        this.totalBytesSent += bytes;
    }

    public long getTotalBytesSent() {
        return this.totalBytesSent;
    }
}
