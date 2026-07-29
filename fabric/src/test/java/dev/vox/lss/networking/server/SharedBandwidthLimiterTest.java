package dev.vox.lss.networking.server;

import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.PlayerBandwidthTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedBandwidthLimiterTest {

    @Test
    void singlePlayerGetsFullAllocation() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(10_000_000, allocation);
    }

    @Test
    void multiPlayerFairSplit() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(4);
        assertEquals(2_500_000, allocation);
    }

    @Test
    void zeroPlayersReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(0);
        assertEquals(0, allocation);
    }

    @Test
    void recordSendReducesRemaining() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(3_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(7_000_000, allocation);
    }

    @Test
    void budgetExhaustedReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(0, allocation);
    }

    @Test
    void overBudgetReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(15_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(0, allocation);
    }

    @Test
    void tokensRefillAfterTime() throws InterruptedException {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(10_000_000);
        assertEquals(0, limiter.getPerPlayerAllocation(1));

        Thread.sleep(150);

        long allocation = limiter.getPerPlayerAllocation(1);
        assertTrue(allocation > 0, "Tokens should refill after elapsed time");
        assertTrue(allocation <= 10_000_000, "Tokens should not exceed max capacity");
    }

    @Test
    void tokensCappedAtMaxCapacity() throws InterruptedException {
        var limiter = new SharedBandwidthLimiter(10_000_000);

        // Wait for potential over-refill
        Thread.sleep(200);

        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(10_000_000, allocation, "Tokens should be capped at maxBytesPerSecond");
    }

    @Test
    void totalBytesSentTracking() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        assertEquals(0, limiter.getTotalBytesSent(), "Total should be 0 initially");
        limiter.recordSend(1000);
        limiter.recordSend(2000);
        assertEquals(3000, limiter.getTotalBytesSent(), "Total should accumulate sends");
        limiter.recordSend(500);
        assertEquals(3500, limiter.getTotalBytesSent(), "Total should keep accumulating");
    }

    // ---- PlayerBandwidthTracker burst cap ----

    @Test
    void playerBurstAfterIdleIsCappedAtQuarterAllocation() throws InterruptedException {
        // An idle player accumulates refill; without the allocation/4 burst cap, the
        // backlog flushes in one tick as a lag spike (invisible in per-second averages).
        var tracker = new PlayerBandwidthTracker();
        long allocation = 4_000_000; // burst cap = allocation / 4 = 1_000_000
        int chunk = 50_000;

        // Idle past the 250ms burst window: the uncapped refill (~1.6MB) exceeds the cap
        Thread.sleep(400);

        long sent = 0;
        while (tracker.canSend(allocation) && sent < allocation) {
            tracker.recordSend(chunk);
            sent += chunk;
        }

        assertTrue(sent >= 1_000_000,
                "post-idle tokens must cover the full burst cap (sent=" + sent + ")");
        // Slack allows for refill drift if milliseconds elapse inside the loop (4000 bytes/ms);
        // an uncapped refill would send >= 1_600_000.
        assertTrue(sent <= 1_300_000,
                "post-idle burst must be capped at allocation/4, not the raw refill (sent=" + sent + ")");
    }

    // ---- Debt-carrying enforcement (R2-2) ----

    @Test
    void oversizedPayloadDebtConvergesTheSustainedRateToTheCap() {
        // The zero-floor bug: with payloads larger than the per-poll refill, forgiving the
        // overdraft let one payload ship per poll — ~20 payloads/s regardless of the cap.
        // Debt-carrying admits the payload (presence gate — a sufficiency gate would
        // deadlock anything above the burst window) but blocks the NEXT send until the
        // refill pays the debt down, converging the sustained rate to the cap.
        long[] clock = {0};
        var tracker = new PlayerBandwidthTracker(() -> clock[0]);
        final long allocation = 1_000; // 1000 B/s
        final int payload = 300;       // > burst cap (250) and > per-poll refill (50)

        long sentBytes = 0;
        for (int poll = 0; poll < 200; poll++) { // 10 simulated seconds at 50 ms polls
            clock[0] += 50_000_000L;
            if (tracker.canSend(allocation)) {
                tracker.recordSend(payload);
                sentBytes += payload;
            }
        }
        // Cap 1000 B/s x 10 s = 10_000 bytes; one payload of slack for the final admit.
        // The pre-fix floor-forgiveness shape sent one payload per poll: 60_000 bytes.
        assertTrue(sentBytes <= 10_000 + payload,
                "sustained rate must converge to the configured cap, got " + sentBytes + " bytes in 10s");
        assertTrue(sentBytes >= 9_000,
                "debt must not under-deliver either, got " + sentBytes + " bytes in 10s");
    }

    @Test
    void sharedLimiterDebtStallsAllAllocationsUntilPaidDown() {
        // The named fairness change: one player's oversized payload takes the SHARED
        // bucket negative, stalling every player's allocation for debt/cap seconds.
        // Correct global-cap enforcement over per-player isolation — deliberate.
        long[] clock = {0};
        var limiter = new SharedBandwidthLimiter(1_000, () -> clock[0]);
        assertTrue(limiter.getPerPlayerAllocation(1) > 0, "premise: full bucket");
        limiter.recordSend(3_000); // 2000 bytes of debt beyond the 1000-byte bucket

        // Polled every step like the production tick loop (the refill credits at most 1 s
        // of history per call — an unpolled gap is deliberately discarded).
        clock[0] += 1_000_000_000L; // +1s pays 1000 of the 2000 debt
        assertEquals(0, limiter.getPerPlayerAllocation(1),
                "debt still outstanding after 1s — allocations stay stalled");
        clock[0] += 1_000_000_000L; // +2s total: debt exactly paid, bucket at 0
        assertEquals(0, limiter.getPerPlayerAllocation(1),
                "bucket at exactly 0 still allocates nothing");
        clock[0] += 500_000_000L;   // +2.5s: 500 bytes of real credit
        long allocation = limiter.getPerPlayerAllocation(1);
        assertTrue(allocation > 0 && allocation <= 500,
                "allocation resumes once the debt is paid down, got " + allocation);
    }

    @Test
    void fractionalRefillsAccumulateAcrossPolls() {
        // The truncation bug: advancing the refill anchor to `now` even when the computed
        // refill rounded to zero threw the fractional bytes away every poll — allocations
        // below ~20 B/s at 50 ms polls starved FOREVER. The anchor now advances by
        // consumed time only, so remainders accumulate.
        long[] clock = {0};
        var tracker = new PlayerBandwidthTracker(() -> clock[0]);
        final long allocation = 10; // 0.5 bytes per 50 ms poll — always truncates to 0

        boolean sendable = false;
        for (int poll = 0; poll < 40 && !sendable; poll++) { // 2 simulated seconds
            clock[0] += 50_000_000L;
            sendable = tracker.canSend(allocation);
        }
        assertTrue(sendable,
                "a sub-truncation-threshold allocation must accumulate to a send within 2s, not starve");
    }
}
