package dev.vox.lss.networking.client;

import dev.vox.lss.common.processing.PingBackstop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client transfer governor's control-loop pins (adaptive-transfer-rate-plan.md,
 * Mechanism A — both plan-review lenses' fix list plus the implementation review's
 * MAJOR-1 offer-backing and MINOR-2/3 amendments). Pure unit suite: the governor's
 * inputs all arrive through {@code tick(...)} and the injectable clock.
 */
class TransferRateGovernorTest {

    private static final long KB = 1024;
    private static final long STEP = TransferRateGovernor.STEP_BYTES_PER_SEC;
    private static final long INTERVAL = TransferRateGovernor.INTERVAL_MILLIS;
    private static final int CONGESTED_PING = 2_000; // baseline 50 → excess ~1950
    private static final int NORMAL_PING = 50;

    /** Full-signature tick with a generously offer-backed declared count (declared
     *  rides the columns counter ×100, so every interval's offer dwarfs the governed
     *  rate). Tests that pin the offer-backing term pass declared explicitly. */
    private static void tick(TransferRateGovernor g, long now, long bytes, long cols,
                             int awaiting, boolean halted, int ping) {
        g.tick(now, bytes, cols, cols * 100, awaiting, halted, ping, true);
    }

    /** Seeds a 50 ms ping baseline, then TWO consecutive congested slow intervals →
     *  engaged (the round-5 debounce: one interval is a transient, never an
     *  engagement). Cumulative bytes double so measured stays bytesPerInterval/2 s
     *  per interval; callers continuing the timeline resume from 2×bytesPerInterval. */
    private long engageAt(TransferRateGovernor g, long t, long bytesPerInterval) {
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t + INTERVAL, bytesPerInterval, 10, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "one congested interval is a transient, not congestion");
        tick(g, t + 2 * INTERVAL, 2 * bytesPerInterval, 20, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the second consecutive congested interval engages");
        return t + 2 * INTERVAL;
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
            tick(g, t, bytes, i * 5L, 1, false, NORMAL_PING);
            t += INTERVAL;
            bytes += 200 * KB;
        }
        assertFalse(g.isEngaged(), "normal ping must never engage, whatever the rate");
        assertEquals(0, g.sustainedColumnsPerSecond(), "no governed cap while unengaged");
    }

    @Test
    void congestedShortfallEngagesAtMeasuredMinusHalfStep() {
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 800 KB over 2 s = 400 KB/s measured, ping excess ~1950 ms — twice (debounce).
        tick(g, INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged());
        assertEquals(400 * KB - STEP / 2, g.getDesiredBytesPerSec(),
                "bootstrap-by-shortfall: desired = measured − STEP/2");
    }

    @Test
    void engageRequiresTwoConsecutiveCongestedIntervals() {
        // Round-5 review M1: the ping input is a raw 1 Hz probe sample now — a single
        // GC pause / WiFi burst crossing 250 ms must not buy a sticky engagement
        // (there is no ping-normal escape anymore). A normal interval between two
        // congested ones resets the pending streak.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        tick(g, INTERVAL, 200 * KB, 20, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "one congested interval never engages");
        tick(g, 2 * INTERVAL, 400 * KB, 40, 1, false, NORMAL_PING); // transient over
        tick(g, 3 * INTERVAL, 600 * KB, 60, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "a broken streak restarts the debounce");
        tick(g, 4 * INTERVAL, 800 * KB, 80, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "two consecutive congested intervals engage");
    }

    @Test
    void idleIntervalNeverAdjusts() {
        // The DH idle-collapse fix: no demand (awaiting empty) → non-qualifying even
        // with congested ping and bytes flowing (a trailing delivery).
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 0, false, NORMAL_PING);
        tick(g, INTERVAL, 100 * KB, 10, 0, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "an idle interval must never engage");
    }

    @Test
    void haltOverlappedIntervalIsNonQualifying() {
        // Review m1 (integration): a #71 halt keeps the awaiting set populated while
        // the client deliberately stops ingesting — the depressed tail rate is not a
        // link measurement.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING); // seeds interval + 50 ms baseline
        tick(g, INTERVAL / 2, 0, 0, 1, true, CONGESTED_PING); // halt mid-interval
        tick(g, INTERVAL, 100 * KB, 10, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "a halt-overlapped interval must not engage");
        // The same shape WITHOUT the halt engages (two clean congested intervals —
        // the debounce) — proves the halt was the gate.
        tick(g, 2 * INTERVAL, 200 * KB, 20, 1, false, CONGESTED_PING);
        tick(g, 3 * INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged());
    }

    @Test
    void negativeCounterDeltaIsNonQualifyingAndReseeds() {
        // Review m3: a session reset zeroes the gate counters mid-interval.
        var g = new TransferRateGovernor();
        tick(g, 0, 500 * KB, 50, 1, false, NORMAL_PING);
        tick(g, INTERVAL, 100 * KB, 10, 1, false, CONGESTED_PING); // ran backwards
        assertFalse(g.isEngaged(), "a reset-spanning interval must not engage");
        // The next TWO intervals measure cleanly from the reseeded start (debounce).
        tick(g, 2 * INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
        tick(g, 3 * INTERVAL, 500 * KB, 50, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the clean intervals after the reseed engage");
    }

    @Test
    void fastMeasuredRateNeverEngages() {
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 8 MB/s per interval — above ENGAGE_BELOW even with congested ping, twice
        // (so the debounce is provably not what held it back).
        tick(g, INTERVAL, 16_384 * KB, 500, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 32_768 * KB, 1000, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "sessions measuring above the threshold never engage");
    }

    // ---- Engaged AIMD ----

    @Test
    void keptUpStepsUpAndShortfallCutsWithTheAbsoluteBand() {
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long desired = 400 * KB - STEP / 2; // 272 KB/s
        assertEquals(desired, g.getDesiredBytesPerSec());
        // Kept-up: measured within STEP/4 below desired → +STEP (the ABSOLUTE band,
        // review M3 — a multiplicative 0.9 band ratchets standing queue).
        long bytes = 1600 * KB;
        long measuredBps = desired - STEP / 4 + KB; // just inside the band
        bytes += measuredBps * 2;
        tick(g, t += INTERVAL, bytes, 200, 1, false, CONGESTED_PING);
        desired += STEP;
        assertEquals(desired, g.getDesiredBytesPerSec(), "kept-up adds STEP");
        // Shortfall (offer-backed via the helper): measured below desired − STEP/4 →
        // cut to measured − STEP/2.
        long slowBps = desired - STEP / 4 - KB;
        bytes += slowBps * 2;
        tick(g, t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING);
        assertEquals(Math.max(slowBps - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "shortfall cuts to measured − STEP/2");
    }

    @Test
    void underOfferedShortfallFreezesInsteadOfCutting() {
        // Impl review MAJOR-1: the actuator is a stop-and-wait window — a cadence
        // hold, the walk-cost coverage limit, actionable retries, or an RTT-stretched
        // window all collapse the ACHIEVED rate with no link involvement. A shortfall
        // interval that did not OFFER ≥ ¾ of the governed rate is the window's doing
        // and must freeze, not cut (the ratchet-to-floor defect).
        var g = new TransferRateGovernor();
        long t = 0;
        long declared = 0;
        g.tick(t, 0, 0, declared, 1, false, NORMAL_PING, true);
        g.tick(t += INTERVAL, 800 * KB, 100, declared += 200, 1, false,
                CONGESTED_PING, true);
        g.tick(t += INTERVAL, 1600 * KB, 200, declared += 200, 1, false,
                CONGESTED_PING, true); // debounce
        long desired = g.getDesiredBytesPerSec(); // 272 KB/s; EWMA 8 KB → R = 34
        assertEquals(34, g.sustainedColumnsPerSecond());
        // A quarter-rate interval (68 KB/s) with almost nothing OFFERED (10 declared
        // vs the ~51 the ¾ term requires): frozen.
        long bytes = 1600 * KB + 68 * KB * 2;
        g.tick(t += INTERVAL, bytes, 217, declared += 10, 1, false,
                CONGESTED_PING, true);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "an under-offered shortfall must freeze desired");
        // The SAME measured rate with a full offer is genuine link evidence: cut.
        bytes += 68 * KB * 2;
        g.tick(t += INTERVAL, bytes, 334, declared += 200, 1, false,
                CONGESTED_PING, true);
        assertEquals(TransferRateGovernor.MIN_RATE_BYTES_PER_SEC,
                g.getDesiredBytesPerSec(),
                "the offer-backed twin of the same interval cuts");
    }

    @Test
    void everyFourthKeptUpIsADrainInterval() {
        // Review M3's second defect + live round 2's retune: an AIMD equilibrium
        // hovers AT capacity (measured ~500 ms standing queue live), so the bleed
        // runs every 4th kept-up at STEP/2 depth.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long bytes = 1600 * KB;
        // Feed exactly-kept-up intervals (measured = desired): 3 step up, the 4th drains.
        for (int keptUp = 1; keptUp <= TransferRateGovernor.DRAIN_EVERY_KEPT_UP; keptUp++) {
            long desiredBefore = g.getDesiredBytesPerSec();
            long measuredBps = desiredBefore;
            bytes += measuredBps * 2;
            tick(g, t += INTERVAL, bytes, 200 + keptUp * 10L, 1, false, CONGESTED_PING);
            if (keptUp < TransferRateGovernor.DRAIN_EVERY_KEPT_UP) {
                assertEquals(desiredBefore + STEP, g.getDesiredBytesPerSec(),
                        "kept-up " + keptUp + " probes up");
            } else {
                assertEquals(measuredBps - STEP / 2, g.getDesiredBytesPerSec(),
                        "the 4th consecutive kept-up bleeds standing queue instead");
            }
        }
    }

    @Test
    void movementIntervalHoldsTheClimb() {
        // Live round 2: the ~1500 ms movement spikes were the governor climbing +STEP
        // into the window where vanilla's own chunk bursts compete for the link. A
        // kept-up interval that saw a chunk crossing HOLDS desired; shortfall during
        // movement still cuts.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long desired = g.getDesiredBytesPerSec();
        long bytes = 1600 * KB;
        // Kept-up interval WITH movement: held.
        bytes += desired * 2;
        g.noteMovement();
        tick(g, t += INTERVAL, bytes, 200, 1, false, CONGESTED_PING);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "a moving kept-up interval must not probe up");
        // The same shape WITHOUT movement climbs — the hold was the movement's doing.
        bytes += desired * 2;
        tick(g, t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "a stationary kept-up interval probes up");
        // Shortfall during movement still cuts (the hold pauses only the up-probe).
        long slow = (desired + STEP) / 2;
        bytes += slow * 2;
        g.noteMovement();
        tick(g, t += INTERVAL, bytes, 400, 1, false, CONGESTED_PING);
        assertEquals(Math.max(slow - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "movement never suppresses a genuine cut");
    }

    @Test
    void drainIntervalNeverRaisesDesired() {
        // Impl review MINOR-3: when the size EWMA lags a regime change, measured can
        // exceed desired — a measured-anchored drain would RAISE desired several-fold
        // in one step, the opposite of a bleed. The drain anchors at
        // min(desired, measured).
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long bytes = 1600 * KB;
        for (int keptUp = 1; keptUp < TransferRateGovernor.DRAIN_EVERY_KEPT_UP; keptUp++) {
            bytes += g.getDesiredBytesPerSec() * 2;
            tick(g, t += INTERVAL, bytes, 200 + keptUp * 10L, 1, false, CONGESTED_PING);
        }
        long desiredBefore = g.getDesiredBytesPerSec();
        // The drain-cadence kept-up measures DOUBLE desired (EWMA-lag shape): must
        // bleed down from desired, never jump up toward measured.
        bytes += desiredBefore * 4;
        tick(g, t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING);
        assertEquals(desiredBefore - STEP / 2, g.getDesiredBytesPerSec(),
                "the drain anchors at min(desired, measured)");
    }

    @Test
    void desiredFloorsAtMinRate() {
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        // Engage at a crawl: 20 KB over 2 s = 10 KB/s (twice — the debounce).
        tick(g, t += INTERVAL, 20 * KB, 4, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 40 * KB, 8, 1, false, CONGESTED_PING);
        assertEquals(TransferRateGovernor.MIN_RATE_BYTES_PER_SEC, g.getDesiredBytesPerSec(),
                "desired floors at MIN_RATE, never below");
    }

    // ---- Disengagement ----

    @Test
    void sustainedRateAboveTheEngageThresholdDisengages() {
        // Disengage path (a): the loop probed clear of ENGAGE_BELOW and stayed there
        // for DISENGAGE_RATE_INTERVALS qualifying intervals — the link recovered.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        // Engage at 3.5 MB/s (just under the 4 MB threshold), twice — the debounce.
        tick(g, t += INTERVAL, 7_168 * KB, 700, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 14_336 * KB, 1400, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged());
        long bytes = 14_336 * KB;
        long cols = 1400;
        // Feed measured = desired: the loop climbs +STEP per kept-up (every 4th
        // drains −STEP/2), crossing 4 MB in a few intervals, then needs 10 more
        // qualifying intervals above it. 20 is comfortably enough.
        int intervals = 0;
        while (g.isEngaged() && intervals++ < 20) {
            bytes += g.getDesiredBytesPerSec() * 2;
            cols += 100;
            tick(g, t += INTERVAL, bytes, cols, 1, false, CONGESTED_PING);
        }
        assertFalse(g.isEngaged(), "sustained desired above the threshold disengages");
        assertEquals(0, g.sustainedColumnsPerSecond(), "the cap drops entirely");
    }

    @Test
    void pingNormalNeverDisengagesWhileTheCapBinds() {
        // Live round 5 INVERTED review m10's ping-normal disengage: normal ping under
        // a binding cap is the governor's own success, not link health. The traced
        // sawtooth — ~1 min of governed calm, path (b) fires, the un-capped link runs
        // away to 4.7 s ping for 25 s (the tab-ping blind spot) — is what this pin
        // prevents. 100 converged normal-ping intervals (~3 min): still engaged.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        long bytes = 600 * KB; // engageAt's cumulative endpoint
        for (int i = 0; i < 100; i++) {
            tick(g, t += INTERVAL, bytes, 30, 0, false, NORMAL_PING);
            assertTrue(g.isEngaged(), "normal ping alone must NEVER disengage (i=" + i + ")");
        }
        assertTrue(g.sustainedColumnsPerSecond() > 0, "the cap stays armed");
    }

    @Test
    void bindingCapWithNormalPingNeverDisengagesWhileServerLimited() {
        // The qualifying-interval variant of the deletion pin (round-5 review m3):
        // bytes flowing at a server-limited 300 KB/s, awaiting > 0, normal ping
        // throughout. The AIMD oscillates desired around the achieved rate (kept-up
        // climb → offer-backed shortfall cut) and never crosses the 4 MB/s rate
        // disengage — ping-normal must not be an exit for this population either.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 600 * KB);
        long bytes = 1200 * KB;
        long cols = 20;
        for (int i = 0; i < 40; i++) {
            bytes += 300 * KB * 2; // measured 300 KB/s every interval
            cols += 30;
            tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
            assertTrue(g.isEngaged(),
                    "a server-limited binding cap must stay engaged (i=" + i + ")");
        }
        assertTrue(g.getDesiredBytesPerSec() < TransferRateGovernor.ENGAGE_BELOW_BYTES_PER_SEC,
                "the oscillation stays below the rate-disengage threshold");
    }

    // ---- Vanilla-first (live round 5: the fly-then-stop desync) ----

    /** Engaged at ~300 KB/s with the missing floor learned at 20 (the view-edge ring). */
    private long engageWithMissingFloor(TransferRateGovernor g) {
        g.noteMissingVanilla(20);
        long t = engageAt(g, 0, 600 * KB);
        return t;
    }

    @Test
    void missingVanillaExcessCutsEvenWhenKeptUp() {
        // The precondition measured live: governed 590 KB/s of a 750 KB/s link, vanilla
        // 20+ chunks behind (miss 41-43 over the 20 floor), silent movement rejections.
        // A kept-up interval (measured = desired, which would otherwise STEP UP) must
        // CUT once the excess crosses the margin.
        var g = new TransferRateGovernor();
        long t = engageWithMissingFloor(g);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(20 + TransferRateGovernor.MISSING_VANILLA_CUT_EXCESS);
        long bytes = 1200 * KB + desired * 2; // measured = desired: kept up
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertTrue(g.getDesiredBytesPerSec() < desired,
                "vanilla-behind must cut, never climb");
        assertEquals(Math.max(desired - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "the cut lands at measured − STEP/2");
    }

    @Test
    void missingVanillaBelowTheMarginStepsNormally() {
        var g = new TransferRateGovernor();
        long t = engageWithMissingFloor(g);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(20 + TransferRateGovernor.MISSING_VANILLA_CUT_EXCESS - 1);
        long bytes = 1200 * KB + desired * 2;
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "below the margin the kept-up climb proceeds");
    }

    @Test
    void permanentEdgeRingFloorNeverReadsAsExcess() {
        // The session MIN learns the server-view-distance edge ring (~20 on the rig):
        // a steady 20 is the floor itself, excess 0 — cutting on it would punish every
        // session with a permanent missing ring.
        var g = new TransferRateGovernor();
        long t = engageWithMissingFloor(g);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(20);
        long bytes = 1200 * KB + desired * 2;
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "the learned floor is not excess");
    }

    @Test
    void spuriouslyLowMissingFloorHealsByDrift() {
        // Round-5 review M2: the floor is a MIN over a SETTINGS-dependent baseline —
        // a render-distance change can leave a stale-low floor reading the new
        // permanent edge ring as perpetual excess. The per-interval drift raises the
        // floor toward the sample, so the spurious cut disarms and the climb resumes
        // within ~a dozen intervals instead of pinning MIN forever.
        var g = new TransferRateGovernor();
        g.noteMissingVanilla(0); // old settings: floor 0
        long t = engageAt(g, 0, 600 * KB);
        g.noteMissingVanilla(20); // new settings: the permanent ring reads "excess 20"
        long bytes = 1200 * KB;
        long cols = 20;
        boolean climbed = false;
        long prev = g.getDesiredBytesPerSec();
        for (int i = 0; i < 20; i++) {
            bytes += g.getDesiredBytesPerSec() * 2; // measured = desired: kept up
            cols += 30;
            tick(g, t += INTERVAL, bytes, cols, 1, false, CONGESTED_PING);
            if (g.getDesiredBytesPerSec() > prev) { climbed = true; break; }
            prev = g.getDesiredBytesPerSec();
        }
        assertTrue(climbed, "the drifted floor must disarm the spurious cut");
    }

    @Test
    void vanillaFirstCutNeverRaisesDesired() {
        // Round-5 review m2: the cut anchors min(desired, measured) — the interval
        // right after a deep cut measures the pre-cut in-flight tail (measured well
        // above the new desired), and a bare measured anchor would RAISE the cap
        // mid-shed.
        var g = new TransferRateGovernor();
        g.noteMissingVanilla(20);
        long t = engageAt(g, 0, 1600 * KB); // measured 800 KB/s → desired 672 KB/s
        g.noteMissingVanilla(40); // deep excess (drift erodes slowly at gap/8)
        long bytes = 3200 * KB;
        bytes += 672 * KB * 2; // measured = desired → first cut lands 544 KB/s
        tick(g, t += INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(544 * KB, g.getDesiredBytesPerSec(), "first cut: anchor − STEP/2");
        bytes += 672 * KB * 2; // the pre-cut tail still delivers ABOVE desired
        tick(g, t += INTERVAL, bytes, 90, 1, false, CONGESTED_PING);
        assertEquals(416 * KB, g.getDesiredBytesPerSec(),
                "a tail above desired keeps the shed moving DOWN (a bare measured"
                        + " anchor would hold at 544)");
    }

    @Test
    void missingVanillaNeverEngagesAnUnengagedSession() {
        // Engaged-only by design: fast links (which never engage) must be untouched —
        // vanilla briefly behind during flight on a LAN is normal, not congestion.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        g.noteMissingVanilla(0);
        g.noteMissingVanilla(200);
        tick(g, INTERVAL, 300 * KB, 30, 1, false, NORMAL_PING);
        assertFalse(g.isEngaged(), "missing-vanilla excess is never an engage trigger");
    }

    @Test
    void missingFloorRelearnsAfterDimensionChange() {
        // A new dimension is a new view: a stale low floor would read the whole fresh
        // view as vanilla-behind and cut for nothing. onDimensionChange clears both
        // samples; a post-change interval at the new dimension's floor steps normally.
        var g = new TransferRateGovernor();
        g.noteMissingVanilla(0); // old dimension's floor: 0
        long t = engageAt(g, 0, 600 * KB);
        g.onDimensionChange(); // disengages (control state dies; measurements survive)
        // Re-engage in the new dimension off the surviving ping baseline (the unseeded
        // interval re-bases the cumulative counters, so absolute values restart fine).
        t = engageAt(g, t + INTERVAL, 600 * KB);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(40); // the new view: first sample seeds the new floor
        long bytes = 1200 * KB + desired * 2;
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "the first post-change sample is the new floor, not excess");
    }

    // ---- Size estimator + conversion ----

    @Test
    void conversionFloorsAtOneColumnPerSecond() {
        // Review m2/m5: MIN_RATE over a >64 KB column EWMA would integer-convert to the
        // <=0 OFF sentinel — and a 0 budget kills the walk (no declaration ever).
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 2 columns of 100 KB each: EWMA seeds at ~100 KB; measured 100 KB/s → engage
        // at MIN_RATE. MIN_RATE/100KB < 1 → floors at 1.
        tick(g, INTERVAL, 200 * KB, 2, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 400 * KB, 4, 1, false, CONGESTED_PING); // debounce
        assertTrue(g.isEngaged());
        assertEquals(1, g.sustainedColumnsPerSecond(), "conversion floors at 1 col/s");
        assertEquals(1, g.burstColumnsPerSecond(), "burst floors at 1 too");
    }

    @Test
    void engagedWithNoSizeSampleSuppliesNoCap() {
        // The pre-first-sample division guard: bytes flowed but no column count moved
        // (deltaColumns 0) — engaged with no size estimate must supply NO cap, never
        // a garbage conversion.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        tick(g, INTERVAL, 200 * KB, 0, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 400 * KB, 0, 1, false, CONGESTED_PING); // debounce
        assertTrue(g.isEngaged(), "byte flow with congested ping engages");
        assertEquals(-1.0, g.getSizeEstimateForTest(), 1e-9, "no sample yet");
        assertEquals(0, g.sustainedColumnsPerSecond(), "no estimate = no cap");
        assertEquals(0, g.burstColumnsPerSecond(), "no estimate = no burst cap");
    }

    @Test
    void burstCapIsAQuarterOfSustainedRoundedUp() {
        // Review M2's seam split: burst = ceil(R/4) so the spacing gate equilibrates
        // at the 5-tick floor — 4 Hz quarter-batches.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 100 columns × 8 KB = 800 KB over 2 s: EWMA ~8 KB, desired 272 KB/s → R = 34.
        tick(g, INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        assertEquals(34, g.sustainedColumnsPerSecond());
        assertEquals(9, g.burstColumnsPerSecond(), "ceil(34/4) = 9");
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
        tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        // Ocean: 100 columns of 1 KB.
        bytes += 100 * KB;
        cols += 100;
        tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
        // Terrain: 50 columns of 16 KB.
        bytes += 50 * 16 * KB;
        cols += 50;
        tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
        // EWMA after fast-up from 1 KB toward 16 KB: 1 + 0.5*(16−1) = 8.5 KB — most
        // of the way up in ONE interval (a symmetric slow alpha would sit at ~1.75 KB
        // and over-burst ~5x on the next batch).
        assertEquals(8.5 * KB, g.getSizeEstimateForTest(), 1.0,
                "terrain after ocean must raise the estimate fast");
        // Ocean again: a 1 KB-mean interval decays SLOWLY (alpha 0.05): 8.5 − 0.05*7.5
        // = 8.125 KB — never a collapse back to ghost-clear size.
        bytes += 40 * KB;
        cols += 40;
        tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
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
        g.tick(t + INTERVAL, 400 * KB, 40, 4_000, 1, false, CONGESTED_PING, false);
        assertFalse(g.isEngaged());
        assertEquals(0, g.sustainedColumnsPerSecond());
    }

    @Test
    void harnessJvmPropertiesGateTheGovernorOff() {
        // Integration review M1: soak configs create sub-threshold qualifying intervals
        // (bandwidth-throttle caps at 256 KB/s; superflat columns keep byte rates low),
        // and a governed want-set breaks premises calibrated to the constant budget.
        // Both harness properties gate, independently.
        for (String prop : new String[] {"lss.soak", "lss.benchmark"}) {
            System.setProperty(prop, "true");
            try {
                var g = new TransferRateGovernor();
                tick(g, 0, 0, 0, 1, false, NORMAL_PING);
                tick(g, INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
                assertFalse(g.isEngaged(), "-D" + prop + " must gate the governor off");
            } finally {
                System.clearProperty(prop);
            }
        }
    }

    @Test
    void resetKillsEverythingIncludingBaselineAndEstimator() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        assertTrue(g.getSizeEstimateForTest() > 0, "engaged run seeded the estimator");
        g.reset();
        assertFalse(g.isEngaged());
        assertEquals(0, g.sustainedColumnsPerSecond());
        assertEquals(-1.0, g.getSizeEstimateForTest(), 1e-9,
                "the size estimator dies with the session");
        // After reset the baseline reseeds — a formerly-congested reading seeds the NEW
        // baseline, so the same ping no longer reads as excess and cannot engage.
        tick(g, t += INTERVAL, 0, 0, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(),
                "post-reset the baseline reseeds from the first sample — no excess");
    }

    @Test
    void dimensionChangeKeepsTheMeasurementsAndDropsTheCap() {
        // Impl review MINOR-2: same connection, same link — a full reset would reseed
        // the ping baseline from the CONGESTED current reading and the engagement
        // conjunct could never fire again. The measurements survive; control drops.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        double estimate = g.getSizeEstimateForTest();
        assertTrue(estimate > 0);
        g.onDimensionChange();
        assertFalse(g.isEngaged(), "the cap drops at a dimension change");
        assertEquals(0, g.sustainedColumnsPerSecond());
        assertEquals(estimate, g.getSizeEstimateForTest(), 1e-9,
                "the size estimate survives");
        // Re-engagement uses the KEPT 50 ms baseline: two congested slow intervals
        // (the debounce) suffice — a reseeded baseline would read excess ~0 here and
        // never engage at all.
        tick(g, t += INTERVAL, 400 * KB, 40, 1, false, CONGESTED_PING); // reseed tick
        tick(g, t += INTERVAL, 700 * KB, 70, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1000 * KB, 100, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the kept baseline re-engages via the debounce");
    }

    @Test
    void baselineDriftsUpwardOneMsPerSecond() {
        // A genuinely changed route must re-baseline in minutes, not never.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, 50);
        // 300 s later the baseline has drifted +300 ms; a 320 ms ping now reads as
        // ~-30 excess (below the engage conjunct) instead of +270.
        t += 300_000;
        tick(g, t, 0, 0, 1, false, 320);
        tick(g, t + INTERVAL, 300 * KB, 30, 1, false, 320);
        assertFalse(g.isEngaged(),
                "the drifted baseline absorbs a modest permanent ping shift");
    }

    // ---- Composition + cross-mechanism constants ----

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

    @Test
    void operatingRegionsStaySeparated() {
        // The A/B composition argument IS this constant relationship: A engages far
        // below B's cut threshold, so a governed session's converged excess never
        // reaches B's trigger. A tuning edit to either constant can silently close
        // the margin — pin it.
        assertTrue(TransferRateGovernor.ENGAGE_PING_EXCESS_MS
                        <= PingBackstop.RECOVER_EXCESS_MS,
                "A must engage no later than the excess B already calls calm");
        assertTrue(TransferRateGovernor.ENGAGE_PING_EXCESS_MS * 3
                        <= PingBackstop.CUT_EXCESS_MS,
                "A's engage threshold needs real margin under B's cut threshold");
        assertTrue(PingBackstop.RECOVER_EXCESS_MS < PingBackstop.CUT_EXCESS_MS,
                "B recovers below where it cuts");
    }

    @Test
    void pacerFloorClearsAGovernedQuarterBatchAtDefaults() {
        // send-pacing-plan.md §4 (the m7 coupling invariant, static over compiled
        // defaults — the runtime allocation is a variable): the send pacer's refill
        // floor at the DEFAULT per-player cap must deliver a governed quarter-batch
        // (≤ ENGAGE_BELOW/4 bytes/s worth) within the client's 5-tick fast-fire
        // floor, or pacing would slow the governor's own loop. Documented
        // NON-guarantee under deep pingf cuts / heavy global dilution — the degrade
        // path there is the offer-backing freeze.
        long defaultCapBytesPerSec =
                new dev.vox.lss.config.LSSServerConfig().bytesPerSecondPerPlayer();
        // Both sides are BYTES WITHIN ONE FAST-FIRE WINDOW (5 ticks = 0.25 s at
        // defaults — the units audit's fix; the old form hardcoded the 4 Hz divisor
        // and would have RELAXED if the fast-fire floor ever widened):
        // LHS = what the pace floor delivers in that window; RHS = the byte size of a
        // governed quarter-batch (desired x window, desired < ENGAGE_BELOW). The RHS
        // understates the disengage-probe overshoot (desired may legally exceed
        // ENGAGE_BELOW by DISENGAGE_RATE_INTERVALS x STEP ≈ 2.5 MB/s for a few
        // intervals) — the ~6x headroom at defaults absorbs it.
        long window = SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS;
        long floorBytesPerWindow =
                (defaultCapBytesPerSec / dev.vox.lss.common.LSSConstants.TICKS_PER_SECOND)
                        * window;
        long quarterBatchBytes = TransferRateGovernor.ENGAGE_BELOW_BYTES_PER_SEC
                * window / dev.vox.lss.common.LSSConstants.TICKS_PER_SECOND;
        assertTrue(floorBytesPerWindow >= quarterBatchBytes,
                "the pace floor must clear a governed quarter-batch inside the fast-fire floor");
    }
}
