package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client transfer governor's control-loop pins (adaptive-transfer-rate-plan.md,
 * Mechanism A — both review lenses' fix list). Pure unit suite: the governor's inputs
 * all arrive through {@code tick(...)} and the injectable clock.
 */
class TransferRateGovernorTest {

    private static final long KB = 1024;
    private static final long STEP = TransferRateGovernor.STEP_BYTES_PER_SEC;
    private static final long INTERVAL = TransferRateGovernor.INTERVAL_MILLIS;
    private static final int CONGESTED_PING = 2_000; // baseline 50 → excess ~1950
    private static final int NORMAL_PING = 50;

    /** Seeds a 50 ms ping baseline, then runs congested slow intervals until engaged. */
    private long engageAt(TransferRateGovernor g, long t, long bytesPerInterval) {
        // Baseline seeds from the first nonzero sample; a later congested reading is
        // then a large excess.
        g.tick(t, 0, 0, 1, false, NORMAL_PING, true);
        long bytes = 0;
        long cols = 0;
        // First evaluated interval sees congested ping + slow rate → engage.
        g.tick(t + INTERVAL, bytes + bytesPerInterval, cols + 10, 1, false,
                CONGESTED_PING, true);
        assertTrue(g.isEngaged(), "congested shortfall interval must engage");
        return t + INTERVAL;
    }

    // ---- Engagement gate ----

    @Test
    void demandLimitedShortfallWithNormalPingNeverEngages() {
        // Review M1's headline: measured rate equals the demand/serve rate whenever the
        // link is not the bottleneck — without the congestion conjunct every walking
        // player on a healthy link would engage. 100 KB/s with normal ping, forever.
        var g = new TransferRateGovernor();
        long t = 0;
        long bytes = 0;
        for (int i = 0; i < 50; i++) {
            g.tick(t, bytes, i * 5L, 1, false, NORMAL_PING, true);
            t += INTERVAL;
            bytes += 200 * KB;
        }
        assertFalse(g.isEngaged(), "normal ping must never engage, whatever the rate");
        assertEquals(0, g.sustainedColumnsPerSecond(), "no governed cap while unengaged");
    }

    @Test
    void congestedShortfallEngagesAtMeasuredMinusHalfStep() {
        var g = new TransferRateGovernor();
        g.tick(0, 0, 0, 1, false, NORMAL_PING, true);
        // 800 KB over 2 s = 400 KB/s measured, ping excess ~1950 ms.
        g.tick(INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING, true);
        assertTrue(g.isEngaged());
        assertEquals(400 * KB - STEP / 2, g.getDesiredBytesPerSec(),
                "bootstrap-by-shortfall: desired = measured − STEP/2");
    }

    @Test
    void idleIntervalNeverAdjusts() {
        // The DH idle-collapse fix: no demand (awaiting empty) → non-qualifying even
        // with congested ping and bytes flowing (a trailing delivery).
        var g = new TransferRateGovernor();
        g.tick(0, 0, 0, 0, false, NORMAL_PING, true);
        g.tick(INTERVAL, 100 * KB, 10, 0, false, CONGESTED_PING, true);
        assertFalse(g.isEngaged(), "an idle interval must never engage");
    }

    @Test
    void haltOverlappedIntervalIsNonQualifying() {
        // Review m1 (integration): a #71 halt keeps the awaiting set populated while
        // the client deliberately stops ingesting — the depressed tail rate is not a
        // link measurement.
        var g = new TransferRateGovernor();
        g.tick(0, 0, 0, 1, false, NORMAL_PING, true); // seeds interval + 50 ms baseline
        g.tick(INTERVAL / 2, 0, 0, 1, true, CONGESTED_PING, true); // halt mid-interval
        g.tick(INTERVAL, 100 * KB, 10, 1, false, CONGESTED_PING, true);
        assertFalse(g.isEngaged(), "a halt-overlapped interval must not engage");
        // The same shape WITHOUT the halt engages — proves the halt was the gate.
        g.tick(2 * INTERVAL, 200 * KB, 20, 1, false, CONGESTED_PING, true);
        assertTrue(g.isEngaged());
    }

    @Test
    void negativeCounterDeltaIsNonQualifyingAndReseeds() {
        // Review m3: a session reset zeroes the gate counters mid-interval.
        var g = new TransferRateGovernor();
        g.tick(0, 500 * KB, 50, 1, false, NORMAL_PING, true);
        g.tick(INTERVAL, 100 * KB, 10, 1, false, CONGESTED_PING, true); // ran backwards
        assertFalse(g.isEngaged(), "a reset-spanning interval must not engage");
        // Next interval measures cleanly from the reseeded start.
        g.tick(2 * INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING, true);
        assertTrue(g.isEngaged(), "the clean interval after the reseed engages");
    }

    @Test
    void fastMeasuredRateNeverEngages() {
        var g = new TransferRateGovernor();
        g.tick(0, 0, 0, 1, false, NORMAL_PING, true);
        // 16 MB over 2 s = 8 MB/s — above ENGAGE_BELOW even with congested ping.
        g.tick(INTERVAL, 16_384 * KB, 500, 1, false, CONGESTED_PING, true);
        assertFalse(g.isEngaged(), "sessions measuring above the threshold never engage");
    }

    // ---- Engaged AIMD ----

    @Test
    void keptUpStepsUpAndShortfallCutsWithTheAbsoluteBand() {
        var g = new TransferRateGovernor();
        long t = 0;
        g.tick(t, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING, true);
        long desired = 400 * KB - STEP / 2; // 272 KB/s
        assertEquals(desired, g.getDesiredBytesPerSec());
        // Kept-up: measured within STEP/4 below desired → +STEP (the ABSOLUTE band,
        // review M3 — a multiplicative 0.9 band ratchets standing queue).
        long bytes = 800 * KB;
        long measuredBps = desired - STEP / 4 + KB; // just inside the band
        bytes += measuredBps * 2;
        g.tick(t += INTERVAL, bytes, 200, 1, false, CONGESTED_PING, true);
        desired += STEP;
        assertEquals(desired, g.getDesiredBytesPerSec(), "kept-up adds STEP");
        // Shortfall: measured below desired − STEP/4 → cut to measured − STEP/2.
        long slowBps = desired - STEP / 4 - KB;
        bytes += slowBps * 2;
        g.tick(t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING, true);
        assertEquals(Math.max(slowBps - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "shortfall cuts to measured − STEP/2");
    }

    @Test
    void everyEighthKeptUpIsADrainInterval() {
        // Review M3's second defect: a rate-matched loop never drains INHERITED queue —
        // the deterministic drain bias bleeds it every DRAIN_EVERY_KEPT_UP kept-ups.
        var g = new TransferRateGovernor();
        long t = 0;
        g.tick(t, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING, true);
        long bytes = 800 * KB;
        // Feed exactly-kept-up intervals (measured = desired): 7 step up, the 8th drains.
        for (int keptUp = 1; keptUp <= TransferRateGovernor.DRAIN_EVERY_KEPT_UP; keptUp++) {
            long desiredBefore = g.getDesiredBytesPerSec();
            long measuredBps = desiredBefore;
            bytes += measuredBps * 2;
            g.tick(t += INTERVAL, bytes, 100 + keptUp * 10L, 1, false, CONGESTED_PING, true);
            if (keptUp < TransferRateGovernor.DRAIN_EVERY_KEPT_UP) {
                assertEquals(desiredBefore + STEP, g.getDesiredBytesPerSec(),
                        "kept-up " + keptUp + " probes up");
            } else {
                assertEquals(measuredBps - STEP / 4, g.getDesiredBytesPerSec(),
                        "the 8th consecutive kept-up bleeds standing queue instead");
            }
        }
    }

    @Test
    void desiredFloorsAtMinRate() {
        var g = new TransferRateGovernor();
        long t = 0;
        g.tick(t, 0, 0, 1, false, NORMAL_PING, true);
        // Engage at a crawl: 20 KB over 2 s = 10 KB/s.
        g.tick(t += INTERVAL, 20 * KB, 4, 1, false, CONGESTED_PING, true);
        assertEquals(TransferRateGovernor.MIN_RATE_BYTES_PER_SEC, g.getDesiredBytesPerSec(),
                "desired floors at MIN_RATE, never below");
    }

    // ---- Disengagement ----

    @Test
    void pingNormalStreakDisengagesEvenWhileConverged() {
        // Review m10 (frozen-engaged): a converged session yields no qualifying
        // intervals, so the rate path can never disengage it — the ping-normal path
        // must, or a later teleport on a healed link resumes under a stale low cap.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        long bytes = 300 * KB;
        // Converged: no demand (awaiting 0), normal ping, no bytes.
        for (int i = 0; i < TransferRateGovernor.DISENGAGE_PING_NORMAL_INTERVALS; i++) {
            assertTrue(g.isEngaged(), "still engaged at converged interval " + i);
            g.tick(t += INTERVAL, bytes, 30, 0, false, NORMAL_PING, true);
        }
        assertFalse(g.isEngaged(), "sustained normal ping must disengage");
        assertEquals(0, g.sustainedColumnsPerSecond(), "the cap drops entirely");
    }

    @Test
    void elevatedPingResetsThePingNormalStreak() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        long bytes = 300 * KB;
        for (int i = 0; i < TransferRateGovernor.DISENGAGE_PING_NORMAL_INTERVALS - 1; i++) {
            g.tick(t += INTERVAL, bytes, 30, 0, false, NORMAL_PING, true);
        }
        g.tick(t += INTERVAL, bytes, 30, 0, false, CONGESTED_PING, true); // resets streak
        for (int i = 0; i < TransferRateGovernor.DISENGAGE_PING_NORMAL_INTERVALS - 1; i++) {
            g.tick(t += INTERVAL, bytes, 30, 0, false, NORMAL_PING, true);
        }
        assertTrue(g.isEngaged(), "the streak must restart after an elevated reading");
    }

    // ---- Size estimator + conversion ----

    @Test
    void conversionFloorsAtOneColumnPerSecond() {
        // Review m2/m5: MIN_RATE over a >64 KB column EWMA would integer-convert to the
        // <=0 OFF sentinel — and a 0 budget kills the walk (no declaration ever).
        var g = new TransferRateGovernor();
        g.tick(0, 0, 0, 1, false, NORMAL_PING, true);
        // 2 columns of 100 KB each: EWMA seeds at ~100 KB; measured 100 KB/s → engage
        // at MIN_RATE (100K − 128K < MIN). MIN_RATE/100KB < 1 → floors at 1.
        g.tick(INTERVAL, 200 * KB, 2, 1, false, CONGESTED_PING, true);
        assertTrue(g.isEngaged());
        assertEquals(1, g.sustainedColumnsPerSecond(), "conversion floors at 1 col/s");
        assertEquals(1, g.burstColumnsPerSecond(), "burst floors at 1 too");
    }

    @Test
    void burstCapIsAQuarterOfSustainedRoundedUp() {
        // Review M2's seam split: burst = ceil(R/4) so the spacing gate equilibrates
        // at the 5-tick floor — 4 Hz quarter-batches.
        var g = new TransferRateGovernor();
        g.tick(0, 0, 0, 1, false, NORMAL_PING, true);
        // 100 columns × 8 KB = 800 KB over 2 s: EWMA ~8 KB, desired 272 KB/s → R = 34.
        g.tick(INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING, true);
        int sustained = g.sustainedColumnsPerSecond();
        assertEquals(34, sustained);
        assertEquals((sustained + 3) / 4, g.burstColumnsPerSecond());
    }

    @Test
    void sizeEstimatorRisesFastAndDecaysSlowly() {
        // Review M4: bimodal sizes (ghost-clears vs terrain). After an ocean run the
        // estimate must jump most of the way up on the FIRST terrain interval
        // (under-counting R instead of over-bursting), and terrain→ocean must decay
        // slowly rather than collapse.
        var g = new TransferRateGovernor();
        long t = 0;
        long bytes = 0;
        long cols = 0;
        g.tick(t, bytes, cols, 1, false, NORMAL_PING, true);
        // Ocean: 100 columns of 1 KB.
        bytes += 100 * KB;
        cols += 100;
        g.tick(t += INTERVAL, bytes, cols, 1, false, NORMAL_PING, true);
        // Terrain: 50 columns of 16 KB.
        bytes += 50 * 16 * KB;
        cols += 50;
        g.tick(t += INTERVAL, bytes, cols, 1, false, NORMAL_PING, true);
        // EWMA after fast-up from 1 KB toward 16 KB: 1 + 0.5*(16−1) = 8.5 KB — most
        // of the way up in ONE interval (a symmetric slow alpha would sit at ~1.75 KB
        // and over-burst ~5x on the next batch).
        assertEquals(8.5 * KB, g.getSizeEstimateForTest(), 1.0,
                "terrain after ocean must raise the estimate fast");
        // Ocean again: a 1 KB-mean interval decays SLOWLY (alpha 0.05): 8.5 − 0.05*7.5
        // = 8.125 KB — never a collapse back to ghost-clear size.
        bytes += 40 * KB;
        cols += 40;
        g.tick(t += INTERVAL, bytes, cols, 1, false, NORMAL_PING, true);
        assertEquals(8.5 * KB - 0.05 * (8.5 * KB - 1 * KB), g.getSizeEstimateForTest(), 1.0,
                "one ocean interval must not collapse the size estimate");
    }

    // ---- Gates and lifecycle ----

    @Test
    void inactiveHardResetsAndSuppliesNoCap() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        assertTrue(g.isEngaged());
        // Kill switch / legacy session: active=false hard-resets — no stale cap.
        g.tick(t + INTERVAL, 400 * KB, 40, 1, false, CONGESTED_PING, false);
        assertFalse(g.isEngaged());
        assertEquals(0, g.sustainedColumnsPerSecond());
    }

    @Test
    void harnessJvmPropertyGatesTheGovernorOff() {
        // Integration review M1: soak configs create sub-threshold qualifying intervals
        // (bandwidth-throttle caps at 256 KB/s; superflat columns keep byte rates low),
        // and a governed want-set breaks premises calibrated to the constant budget.
        System.setProperty("lss.soak", "true");
        try {
            var g = new TransferRateGovernor();
            g.tick(0, 0, 0, 1, false, NORMAL_PING, true);
            g.tick(INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING, true);
            assertFalse(g.isEngaged(), "-Dlss.soak must gate the governor off");
        } finally {
            System.clearProperty("lss.soak");
        }
    }

    @Test
    void resetKillsEverythingIncludingBaselineAndEstimator() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        g.reset();
        assertFalse(g.isEngaged());
        assertEquals(0, g.sustainedColumnsPerSecond());
        // After reset the baseline reseeds — a formerly-congested reading seeds the NEW
        // baseline, so the same ping no longer reads as excess and cannot engage.
        g.tick(t += INTERVAL, 0, 0, 1, false, CONGESTED_PING, true);
        g.tick(t += INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING, true);
        assertFalse(g.isEngaged(),
                "post-reset the baseline reseeds from the first sample — no excess");
    }

    @Test
    void baselineDriftsUpwardOneMsPerSecond() {
        // A genuinely changed route must re-baseline in minutes, not never.
        var g = new TransferRateGovernor();
        long t = 0;
        g.tick(t, 0, 0, 1, false, 50, true);
        // 300 s later the baseline has drifted +300 ms; a 320 ms ping now reads as only
        // ~-30 excess (below the engage conjunct) instead of +270.
        t += 300_000;
        g.tick(t, 0, 0, 1, false, 320, true);
        g.tick(t + INTERVAL, 300 * KB, 30, 1, false, 320, true);
        assertFalse(g.isEngaged(),
                "the drifted baseline absorbs a modest permanent ping shift");
    }

    // ---- Composition (manager-level helper) ----

    @Test
    void composeRateCapsHandlesBothOffSentinels() {
        // Review m2: <=0 = OFF on each side and must never win a naive min.
        assertEquals(0, LodRequestManager.composeRateCaps(0, 0), "both off = off");
        assertEquals(40, LodRequestManager.composeRateCaps(40, 0), "manual only");
        assertEquals(25, LodRequestManager.composeRateCaps(0, 25), "governed only");
        assertEquals(25, LodRequestManager.composeRateCaps(40, 25), "min when both");
        assertEquals(40, LodRequestManager.composeRateCaps(40, 90), "min when both, manual tighter");
        assertEquals(0, LodRequestManager.composeRateCaps(-1, -2), "negatives are off");
    }
}
