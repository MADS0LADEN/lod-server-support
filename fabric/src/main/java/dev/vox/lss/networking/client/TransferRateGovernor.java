package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;

import java.util.function.LongSupplier;

/**
 * The client transfer governor (adaptive-transfer-rate-plan.md, Mechanism A): an AIMD
 * loop over the client's own RECEIVED wire-byte rate that, on a congested slow link,
 * caps the want-set through the same machinery as the manual
 * {@code lodColumnsPerSecondLimit} knob. Client-measured arrival bytes are
 * post-bottleneck ground truth — every artifact class that falsified the deleted AUTO
 * outbound ceiling (async netty writes, kernel-buffer absorption, vanilla write
 * interleaving) is structurally invisible to it. Latency comes from pacing UNDER link
 * capacity, never from bounding one queue's depth (the program's structural finding).
 *
 * <p><b>Engagement is congestion-gated</b>: a measured-rate shortfall alone can never
 * engage, because the measured rate equals the demand or serve rate whenever the link
 * is not the bottleneck (review M1 — without the ping conjunct every walking player on
 * a healthy link would engage fleet-wide). The congestion signal is the client's own
 * tab-list ping against a rolling-min baseline; 26.2 broadcasts UPDATE_LATENCY every
 * 600 ticks (30 s) and smooths {@code (3·old+new)/4}, so the signal is coarse —
 * staleness only delays engagement (the safe direction), and the drain bias is the
 * DETERMINISTIC every-8th-kept-up variant because a ping-driven one would over-cut
 * for up to one refresh period after the queue actually drained.
 *
 * <p>All state is main-client-thread confined and dies with the session
 * ({@link #reset()} — the adaptive-cadence reset-family precedent). Intervals whose
 * byte counters ran backwards (a session reset zeroed them mid-interval) are
 * NON-QUALIFYING and re-seed. Harness JVMs ({@code -Dlss.soak} / {@code -Dlss.benchmark})
 * gate the governor OFF (the far-player precedent): soak configs themselves create
 * sub-threshold qualifying intervals, and a governed want-set would break premises
 * calibrated to the constant budget.
 */
final class TransferRateGovernor {

    /** 2 s measurement intervals (review m6): 1 s aliases against the ~4 Hz batch
     *  cadence (0 or 2 bursts per window); 2 s bounds the burst-count error to ~±12.5%,
     *  comparable to the kept-up band. */
    static final long INTERVAL_MILLIS = 2_000L;
    /** Additive up-step per kept-up interval; also sizes the cut margin. */
    static final long STEP_BYTES_PER_SEC = 256L * 1024;
    /** The governed floor — the cap never converts to the {@code <= 0} OFF sentinel
     *  (review m2/m5: the conversion below also floors at 1 column/s). */
    static final long MIN_RATE_BYTES_PER_SEC = 64L * 1024;
    /** Sessions measuring at/above this never engage — the disarm posture. */
    static final long ENGAGE_BELOW_BYTES_PER_SEC = 4L * 1024 * 1024;
    /** The congestion conjunct: ping excess over baseline required to ENGAGE, and the
     *  per-interval drain trigger while engaged. Far below Mechanism B's 750 ms cut. */
    static final int ENGAGE_PING_EXCESS_MS = 250;
    /** Consecutive qualifying congested intervals required to ENGAGE (round-5 review
     *  M1): the ping input is now a raw 1 Hz probe sample, not the tab value vanilla
     *  both refreshed at 30 s and smoothed (3l+s)/4 — the 250 ms threshold was
     *  calibrated against that damped signal, and with the ping-normal escape deleted
     *  a single GC pause or WiFi retransmit burst must not buy a sticky engagement.
     *  Real congestion sustains across intervals; transients do not. */
    static final int ENGAGE_CONSECUTIVE_INTERVALS = 2;
    /** The ONLY disengage path: consecutive QUALIFYING intervals with desired above
     *  the engage threshold — rate evidence that the loop probed clear of the slow
     *  regime. There is deliberately NO ping-normal disengage (live round 5 deleted
     *  review m10's path (b)): while the cap binds, normal ping is the governor's own
     *  SUCCESS, not link health — the traced sawtooth was exactly that path firing
     *  after ~1 min of the calm it was causing, un-capping a 750 KB/s link into a
     *  25 s line-rate runaway (ping 4.7 s, vanilla 20+ chunks behind, movement
     *  rejections) until the 30 s tab-ping refresh let it re-engage. A frozen
     *  (demand-limited) engaged session now stays engaged, which is harmless: the
     *  cap sits above actual demand, and the kept-up climb resumes with demand. */
    static final int DISENGAGE_RATE_INTERVALS = 10;
    /** The deterministic drain bias (review M3; retuned at live round 2): every Nth
     *  consecutive kept-up interval bleeds standing queue instead of probing up — a
     *  rate-matched loop never drains queue it INHERITED, and an AIMD equilibrium
     *  HOVERS AT capacity (the first live session measured ~500 ms standing queue at
     *  convergence — the climb/overshoot cycle keeps the link full). 8→4 with a
     *  deeper bleed halves the time spent at/above capacity. */
    static final int DRAIN_EVERY_KEPT_UP = 4;
    /** Asymmetric column-size EWMA (review M4): fast-up so a terrain batch after an
     *  ocean crossing under-counts R instead of over-bursting; slow-down so
     *  ghost-clear runs decay the estimate gently. */
    static final double SIZE_EWMA_UP_ALPHA = 0.5;
    static final double SIZE_EWMA_DOWN_ALPHA = 0.05;
    /** Baseline upward drift: +1 ms/s, so a genuinely changed route re-baselines in
     *  minutes (Mechanism B's rule, mirrored client-side). */
    private static final long BASELINE_DRIFT_MS_PER_SEC = 1;
    /** Live round 5 (the fly-then-stop desync): missing vanilla chunks IN VIEW above
     *  the session's observed floor mean vanilla delivery is BEHIND the player — the
     *  moved-wrongly precondition (26.2 clients walk into unreceived terrain and the
     *  server rejects the move). An engaged interval seeing at least this much excess
     *  CUTS regardless of kept-up rate: the governor's fair-share equilibrium
     *  otherwise holds its rate while vanilla's catch-up burst crawls (measured live:
     *  governed 590 KB/s of a 750 KB/s link with silent movement rejections). The
     *  floor is learned as a session MIN (the server-view-distance edge ring is a
     *  permanent nonzero baseline — ~20 on the rig). Unengaged sessions never
     *  evaluate this: fast links are untouched. */
    static final int MISSING_VANILLA_CUT_EXCESS = 8;

    /** Injectable MONOTONIC clock in millis (impl review m2: interval math on the
     *  wall clock misreads an NTP step or VM resume as a rate collapse — a spurious
     *  cut to the floor). Arbitrary epoch; only deltas are used. */
    LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

    // ---- Interval accumulation (main client thread) ----
    private boolean intervalSeeded;
    private long intervalStartMillis;
    private long intervalStartWireBytes;
    private long intervalStartColumns;
    private long intervalStartDeclared;
    private boolean awaitingAtStart;
    private boolean haltSeenThisInterval;
    private boolean movementSeenThisInterval;
    private int lastMissingVanilla = -1;   // newest sample (<0 = none yet)
    private int minMissingVanilla = -1;    // session floor (the permanent edge ring)

    // ---- Ping baseline ----
    private int pingBaselineMs = -1;       // -1 = unseeded (zero/absent samples ignored)
    private long baselineDriftAnchorMillis;
    private int lastPingMs = -1;

    // ---- AIMD ----
    private boolean engaged;
    private long desiredBytesPerSec;
    private int keptUpStreak;
    private int rateDisengageStreak;
    private int engagePendingStreak;

    // ---- Size estimator (runs from session start, pre-engagement) ----
    private double sizeEwmaBytes = -1;     // -1 = no sample yet (division guarded)

    // ---- Session-scoped receipts ----
    private boolean engageNoted;
    private boolean disengageNoted;

    static boolean harnessJvm() { // package-private: the manager's probe gate shares it
        return Boolean.getBoolean("lss.soak") || Boolean.getBoolean("lss.benchmark");
    }

    /**
     * One observation per client tick. {@code wireBytesCumulative}/{@code columnsCumulative}
     * are the session gate's monotonic arrival counters (they zero at session reset — the
     * negative-delta guard makes such intervals non-qualifying);
     * {@code declaredCumulative} counts want-set columns actually OFFERED to the wire
     * (the manager's post-send counter — the impl review MAJOR-1 offer-backing input:
     * a downward step is link evidence only when the actuator genuinely offered the
     * governed rate, because the actuator is a stop-and-wait WINDOW whose achieved
     * rate collapses with answer latency — cadence holds, the walk-cost coverage
     * limit, gen-bound serves, high base RTT — none of which is link shortfall);
     * {@code halted} is this tick's #71 backpressure-halt verdict (a halt anywhere in
     * an interval disqualifies it — the client deliberately stopped ingesting, so the
     * depressed tail rate is not a link measurement); {@code pingMs} is the tab-list
     * latency ({@code <= 0} = no sample); {@code active} is the composed gate (config
     * kill switch on, not a harness JVM, not a legacy-dialect session) — false
     * hard-resets so a mid-session toggle leaves no stale cap behind.
     */
    void tick(long nowMillis, long wireBytesCumulative, long columnsCumulative,
              long declaredCumulative, int awaitingSize, boolean halted, int pingMs,
              boolean active) {
        if (!active || harnessJvm()) {
            if (this.engaged || this.intervalSeeded) hardReset();
            return;
        }
        updatePingBaseline(nowMillis, pingMs);
        if (!this.intervalSeeded) {
            seedInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                    declaredCumulative, awaitingSize);
            return;
        }
        if (halted) this.haltSeenThisInterval = true;
        // movementSeenThisInterval is latched by noteMovement() (the manager's
        // chunk-crossing hook) and cleared at each interval seed.
        long elapsed = nowMillis - this.intervalStartMillis;
        if (elapsed < INTERVAL_MILLIS) return;

        evaluateInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                declaredCumulative, awaitingSize, elapsed);
        seedInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                declaredCumulative, awaitingSize);
    }

    private void evaluateInterval(long nowMillis, long wireBytes, long columns,
                                  long declared, int awaitingSize, long elapsedMillis) {
        long deltaBytes = wireBytes - this.intervalStartWireBytes;
        long deltaColumns = columns - this.intervalStartColumns;
        long deltaDeclared = declared - this.intervalStartDeclared;
        // A reset zeroed the gate counters mid-interval (review m3): non-qualifying, and
        // the estimator takes no sample from garbage.
        boolean invalid = deltaBytes < 0 || deltaColumns < 0;
        if (!invalid && deltaColumns > 0) {
            recordSizeSample((double) deltaBytes / deltaColumns);
        }
        boolean qualifying = !invalid && deltaBytes > 0
                && this.awaitingAtStart && awaitingSize > 0
                && !this.haltSeenThisInterval;
        long measured = invalid ? 0 : deltaBytes * 1000L / Math.max(1L, elapsedMillis);
        // NEGATIVE excess is legitimate and NORMAL (ping below the drifted baseline —
        // the steady state, since the baseline min-snaps down then drifts up between
        // samples); only Integer.MIN_VALUE means "no signal".
        int pingExcess = (this.pingBaselineMs >= 0 && this.lastPingMs > 0)
                ? this.lastPingMs - this.pingBaselineMs : Integer.MIN_VALUE;

        if (this.engaged) {
            if (qualifying) {
                // Offer-backing (impl review MAJOR-1): the interval must have OFFERED
                // ≥ ¾ of the governed rate for a downward step to be link evidence.
                long expectedDeclared =
                        (long) sustainedColumnsPerSecond() * elapsedMillis / 1000L;
                boolean offerBacked = deltaDeclared >= 0
                        && deltaDeclared * 4L >= expectedDeclared * 3L;
                stepEngaged(measured, offerBacked);
            }
        } else if (qualifying
                && pingExcess != Integer.MIN_VALUE
                && pingExcess > ENGAGE_PING_EXCESS_MS
                && measured < ENGAGE_BELOW_BYTES_PER_SEC) {
            if (++this.engagePendingStreak >= ENGAGE_CONSECUTIVE_INTERVALS) {
                engage(measured, pingExcess);
            }
        } else {
            this.engagePendingStreak = 0;
        }
        driftMissingFloor();
    }

    /** Round-5 review M2: the missing floor is a session MIN over a SETTINGS-dependent
     *  baseline (client render distance vs server view distance — either side can
     *  change mid-session), so a stale-low floor would read the new permanent edge
     *  ring as perpetual excess and pin an engaged session at MIN forever. The ping
     *  baseline's own answer, mirrored: drift the floor UP toward the newest sample
     *  once per evaluated interval (never past it — a genuine vanilla-behind episode
     *  keeps its gap while the view keeps falling behind, and a settings shift heals
     *  in ~a minute). The min-snap in noteMissingVanilla restores a genuinely lower
     *  floor instantly. */
    private void driftMissingFloor() {
        if (this.minMissingVanilla >= 0 && this.lastMissingVanilla > this.minMissingVanilla) {
            int gap = this.lastMissingVanilla - this.minMissingVanilla;
            this.minMissingVanilla += Math.max(1, gap / 8);
        }
    }

    private void stepEngaged(long measured, boolean offerBacked) {
        if (vanillaBehind()) {
            // Vanilla-first (live round 5): the world around the player is missing —
            // whatever LSS's own rate looks like, its link share is starving vanilla's
            // catch-up and prolonging the desync window. Cut unconditionally (not
            // gated on offer-backing: this is a PRIORITY decision, not rate evidence)
            // and reset the kept-up streak; the loop re-probes once the view heals.
            // Anchored min(desired, measured) — the MINOR-3 drain rule: the interval
            // right after a deep cut measures the pre-cut in-flight tail, and a bare
            // measured anchor would RAISE the cap mid-shed (round-5 review m2).
            this.desiredBytesPerSec = Math.max(
                    Math.min(this.desiredBytesPerSec, measured) - STEP_BYTES_PER_SEC / 2,
                    MIN_RATE_BYTES_PER_SEC);
            this.keptUpStreak = 0;
            this.rateDisengageStreak = 0;
            return;
        }
        boolean shortfall = measured < this.desiredBytesPerSec - STEP_BYTES_PER_SEC / 4;
        if (shortfall && !offerBacked) {
            // The actuator under-offered (a cadence hold, the walk-cost coverage
            // limit, actionable retries, an RTT-stretched window) — the low measured
            // rate is the WINDOW's doing, not the link's. FREEZE: adjusting nothing
            // is safe; cutting here is the MAJOR-1 ratchet-to-floor defect.
            return;
        }
        if (shortfall) {
            this.desiredBytesPerSec =
                    Math.max(measured - STEP_BYTES_PER_SEC / 2, MIN_RATE_BYTES_PER_SEC);
            this.keptUpStreak = 0;
        } else if (this.movementSeenThisInterval) {
            // Live round 2: a kept-up interval that saw chunk crossings HOLDS — the
            // climb must not probe into the window where vanilla's own chunk bursts
            // are about to compete for the link (the measured movement spikes:
            // ~1500 ms while climbing +STEP into a moving player's vanilla traffic).
            // Shortfall above still cuts normally during movement; only the up-probe
            // pauses. The streak is untouched (movement neither earns nor forfeits
            // drain cadence).
        } else if (++this.keptUpStreak % DRAIN_EVERY_KEPT_UP == 0) {
            // Deterministic drain interval: bleed standing queue instead of probing
            // up. Anchored at min(desired, measured) (impl review MINOR-3): when the
            // size EWMA lags a regime change, measured can exceed desired, and a
            // measured-anchored drain would RAISE desired several-fold in one step —
            // the opposite of a bleed. Depth STEP/2 since live round 2 (with STEP/4
            // the bleed barely outran the climb's overshoot).
            this.desiredBytesPerSec = Math.max(
                    Math.min(this.desiredBytesPerSec, measured) - STEP_BYTES_PER_SEC / 2,
                    MIN_RATE_BYTES_PER_SEC);
        } else {
            this.desiredBytesPerSec += STEP_BYTES_PER_SEC;
        }
        // Disengage (a): the loop probed clear of the engage threshold and stayed there.
        if (this.desiredBytesPerSec > ENGAGE_BELOW_BYTES_PER_SEC) {
            if (++this.rateDisengageStreak >= DISENGAGE_RATE_INTERVALS) {
                disengage("sustained rate above the engage threshold");
            }
        } else {
            this.rateDisengageStreak = 0;
        }
    }

    private void engage(long measured, int pingExcess) {
        this.engaged = true;
        this.engagePendingStreak = 0;
        this.desiredBytesPerSec =
                Math.max(measured - STEP_BYTES_PER_SEC / 2, MIN_RATE_BYTES_PER_SEC);
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        if (!this.engageNoted) {
            this.engageNoted = true;
            LSSLogger.info("LOD transfer governor engaged at "
                    + (this.desiredBytesPerSec / 1024) + " KB/s (link congestion: ping +"
                    + pingExcess + " ms over baseline; LOD downloads now pace themselves"
                    + " below link capacity; logged once per session)");
        }
    }

    private void disengage(String reason) {
        this.engaged = false;
        this.desiredBytesPerSec = 0;
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        if (!this.disengageNoted) {
            this.disengageNoted = true;
            LSSLogger.info("LOD transfer governor disengaged (" + reason
                    + "; logged once per session)");
        }
    }

    private void recordSizeSample(double meanColumnBytes) {
        if (this.sizeEwmaBytes < 0) {
            this.sizeEwmaBytes = meanColumnBytes;
            return;
        }
        double alpha = meanColumnBytes > this.sizeEwmaBytes
                ? SIZE_EWMA_UP_ALPHA : SIZE_EWMA_DOWN_ALPHA;
        this.sizeEwmaBytes += alpha * (meanColumnBytes - this.sizeEwmaBytes);
    }

    private void updatePingBaseline(long nowMillis, int pingMs) {
        if (pingMs > 0) {
            this.lastPingMs = pingMs;
            if (this.pingBaselineMs < 0) {
                // Seed from the first NONZERO sample (review m9): a ~0 anchor would read
                // a distant player's natural ping as permanent excess.
                this.pingBaselineMs = pingMs;
                this.baselineDriftAnchorMillis = nowMillis;
            } else if (pingMs < this.pingBaselineMs) {
                this.pingBaselineMs = pingMs;
            }
        }
        if (this.pingBaselineMs >= 0) {
            long driftSec = (nowMillis - this.baselineDriftAnchorMillis) / 1000L;
            if (driftSec > 0) {
                this.pingBaselineMs += (int) Math.min(Integer.MAX_VALUE,
                        driftSec * BASELINE_DRIFT_MS_PER_SEC);
                this.baselineDriftAnchorMillis += driftSec * 1000L;
            }
        }
    }

    private void seedInterval(long nowMillis, long wireBytes, long columns,
                              long declared, int awaitingSize) {
        this.intervalSeeded = true;
        this.intervalStartMillis = nowMillis;
        this.intervalStartWireBytes = wireBytes;
        this.intervalStartColumns = columns;
        this.intervalStartDeclared = declared;
        this.awaitingAtStart = awaitingSize > 0;
        this.haltSeenThisInterval = false;
        this.movementSeenThisInterval = false;
    }

    private void hardReset() {
        this.engaged = false;
        this.desiredBytesPerSec = 0;
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        this.engagePendingStreak = 0;
        this.intervalSeeded = false;
        this.intervalStartMillis = 0;
        this.haltSeenThisInterval = false;
        this.movementSeenThisInterval = false;
        // The size estimator and ping baseline survive a config-toggle reset (they are
        // measurements, not control state) but die with the session in reset().
    }

    /** Chunk-crossing hook (live round 2): latches the movement hold for the current
     *  measurement interval — see the kept-up branch. Main client thread. */
    void noteMovement() {
        this.movementSeenThisInterval = true;
    }

    /** Missing-vanilla-chunks sample (live round 5) — the scanner's 1 Hz periodic
     *  count; the session MIN learns the permanent view-edge ring so only EXCESS
     *  reads as vanilla-behind. Main client thread. */
    void noteMissingVanilla(int missing) {
        if (missing < 0) return;
        this.lastMissingVanilla = missing;
        if (this.minMissingVanilla < 0 || missing < this.minMissingVanilla) {
            this.minMissingVanilla = missing;
        }
    }

    private boolean vanillaBehind() {
        return this.lastMissingVanilla >= 0 && this.minMissingVanilla >= 0
                && this.lastMissingVanilla - this.minMissingVanilla
                        >= MISSING_VANILLA_CUT_EXCESS;
    }

    /** Dimension change (impl review MINOR-2): same connection, same link — the
     *  MEASUREMENTS (ping baseline, size estimate) survive so a still-congested link
     *  re-engages in 1-2 intervals off the kept baseline; only control state drops
     *  (a full reset would reseed the baseline from the CONGESTED current ping and
     *  the engagement conjunct could never fire again). */
    void onDimensionChange() {
        hardReset();
        // A new dimension is a new view — the missing floor re-learns (the edge ring
        // differs per dimension; a stale low floor would read the whole fresh view
        // as vanilla-behind and cut for nothing).
        this.lastMissingVanilla = -1;
        this.minMissingVanilla = -1;
    }

    /** Session teardown (the reset family): everything dies with the session. */
    void reset() {
        hardReset();
        this.lastMissingVanilla = -1;
        this.minMissingVanilla = -1;
        this.sizeEwmaBytes = -1;
        this.pingBaselineMs = -1;
        this.lastPingMs = -1;
        this.baselineDriftAnchorMillis = 0;
        this.engageNoted = false;
        this.disengageNoted = false;
    }

    boolean isEngaged() { return this.engaged; }

    /** Test accessor: the asymmetric size estimator's current value (-1 = no sample). */
    double getSizeEstimateForTest() { return this.sizeEwmaBytes; }

    /** Desired rate in bytes/s while engaged, 0 while not — diag only. */
    long getDesiredBytesPerSec() { return this.engaged ? this.desiredBytesPerSec : 0; }

    /**
     * The SUSTAINED-rate cap in columns/s for the scanner's spacing-gate site, or 0 =
     * no governed cap. Floors at 1 (review m2: {@code columnRateCap}'s contract is
     * {@code <= 0} = OFF, and MIN_RATE over a >64 KB column EWMA would integer-convert
     * to the off sentinel exactly when the cap must bind hardest). Division guarded:
     * engaged-with-no-size-sample supplies no cap rather than garbage.
     */
    int sustainedColumnsPerSecond() {
        if (!this.engaged || this.sizeEwmaBytes <= 0) return 0;
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                (long) (this.desiredBytesPerSec / this.sizeEwmaBytes)));
    }

    /**
     * The BURST cap for the scanner's budget-clamp site: {@code ceil(R/4)}, floored at
     * 1 — the seam split (review M2). One value at both sites would declare 1 Hz
     * FULL-second batches (the spacing gate is {@code ticks × cap < 20 × lastSent} and
     * the walk fills the clamped budget), a burst 4× the plan's target that grazes
     * Mechanism B's threshold; quarter-batches equilibrate the spacing gate at its
     * 5-tick floor — 4 Hz, burst ≈ desired/4.
     */
    int burstColumnsPerSecond() {
        int sustained = sustainedColumnsPerSecond();
        if (sustained <= 0) return 0;
        return Math.max(1, (sustained + 3) / 4);
    }
}
