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
    /** Ping excess below this counts toward the ping-normal disengage streak. */
    static final int DISENGAGE_PING_EXCESS_MS = 100;
    /** Disengage path (a): consecutive QUALIFYING intervals with desired above the
     *  engage threshold. */
    static final int DISENGAGE_RATE_INTERVALS = 10;
    /** Disengage path (b): consecutive intervals of normal ping (~1 min — closes the
     *  frozen-engaged state, review m10). */
    static final int DISENGAGE_PING_NORMAL_INTERVALS = 30;
    /** The deterministic drain bias (review M3): every Nth consecutive kept-up interval
     *  bleeds standing queue instead of probing up — a rate-matched loop never drains
     *  queue it INHERITED (the pre-engagement burst can bank ~cap/4). */
    static final int DRAIN_EVERY_KEPT_UP = 8;
    /** Asymmetric column-size EWMA (review M4): fast-up so a terrain batch after an
     *  ocean crossing under-counts R instead of over-bursting; slow-down so
     *  ghost-clear runs decay the estimate gently. */
    static final double SIZE_EWMA_UP_ALPHA = 0.5;
    static final double SIZE_EWMA_DOWN_ALPHA = 0.05;
    /** Baseline upward drift: +1 ms/s, so a genuinely changed route re-baselines in
     *  minutes (Mechanism B's rule, mirrored client-side). */
    private static final long BASELINE_DRIFT_MS_PER_SEC = 1;

    /** Injectable clock (millis) — the frontier-damper test pattern. */
    LongSupplier clock = System::currentTimeMillis;

    // ---- Interval accumulation (main client thread) ----
    private boolean intervalSeeded;
    private long intervalStartMillis;
    private long intervalStartWireBytes;
    private long intervalStartColumns;
    private boolean awaitingAtStart;
    private boolean haltSeenThisInterval;

    // ---- Ping baseline ----
    private int pingBaselineMs = -1;       // -1 = unseeded (zero/absent samples ignored)
    private long baselineDriftAnchorMillis;
    private int lastPingMs = -1;

    // ---- AIMD ----
    private boolean engaged;
    private long desiredBytesPerSec;
    private int keptUpStreak;
    private int rateDisengageStreak;
    private int pingNormalStreak;

    // ---- Size estimator (runs from session start, pre-engagement) ----
    private double sizeEwmaBytes = -1;     // -1 = no sample yet (division guarded)

    // ---- Session-scoped receipts ----
    private boolean engageNoted;
    private boolean disengageNoted;

    private static boolean harnessJvm() {
        return Boolean.getBoolean("lss.soak") || Boolean.getBoolean("lss.benchmark");
    }

    /**
     * One observation per client tick. {@code wireBytesCumulative}/{@code columnsCumulative}
     * are the session gate's monotonic arrival counters (they zero at session reset — the
     * negative-delta guard makes such intervals non-qualifying); {@code halted} is this
     * tick's #71 backpressure-halt verdict (a halt anywhere in an interval disqualifies
     * it — the client deliberately stopped ingesting, so the depressed tail rate is not a
     * link measurement); {@code pingMs} is the tab-list latency ({@code <= 0} = no
     * sample); {@code active} is the composed gate (config kill switch on, not a harness
     * JVM, not a legacy-dialect session) — false hard-resets so a mid-session toggle
     * leaves no stale cap behind.
     */
    void tick(long nowMillis, long wireBytesCumulative, long columnsCumulative,
              int awaitingSize, boolean halted, int pingMs, boolean active) {
        if (!active || harnessJvm()) {
            if (this.engaged || this.intervalSeeded) hardReset();
            return;
        }
        updatePingBaseline(nowMillis, pingMs);
        if (!this.intervalSeeded) {
            seedInterval(nowMillis, wireBytesCumulative, columnsCumulative, awaitingSize);
            return;
        }
        if (halted) this.haltSeenThisInterval = true;
        long elapsed = nowMillis - this.intervalStartMillis;
        if (elapsed < INTERVAL_MILLIS) return;

        evaluateInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                awaitingSize, elapsed);
        seedInterval(nowMillis, wireBytesCumulative, columnsCumulative, awaitingSize);
    }

    private void evaluateInterval(long nowMillis, long wireBytes, long columns,
                                  int awaitingSize, long elapsedMillis) {
        long deltaBytes = wireBytes - this.intervalStartWireBytes;
        long deltaColumns = columns - this.intervalStartColumns;
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
                stepEngaged(measured);
            }
            // Disengage (b): sustained normal ping — evaluated on EVERY interval (a
            // converged session produces no qualifying intervals, which is exactly the
            // frozen-engaged state this path exists to close). No signal resets the
            // streak (conservative: never disengage blind).
            if (pingExcess != Integer.MIN_VALUE && pingExcess < DISENGAGE_PING_EXCESS_MS) {
                if (++this.pingNormalStreak >= DISENGAGE_PING_NORMAL_INTERVALS) {
                    disengage("link ping normal for ~1 minute");
                }
            } else {
                this.pingNormalStreak = 0;
            }
        } else if (qualifying
                && pingExcess != Integer.MIN_VALUE
                && pingExcess > ENGAGE_PING_EXCESS_MS
                && measured < ENGAGE_BELOW_BYTES_PER_SEC) {
            engage(measured, pingExcess);
        }
    }

    private void stepEngaged(long measured) {
        boolean shortfall = measured < this.desiredBytesPerSec - STEP_BYTES_PER_SEC / 4;
        if (shortfall) {
            this.desiredBytesPerSec =
                    Math.max(measured - STEP_BYTES_PER_SEC / 2, MIN_RATE_BYTES_PER_SEC);
            this.keptUpStreak = 0;
        } else if (++this.keptUpStreak % DRAIN_EVERY_KEPT_UP == 0) {
            // Deterministic drain interval: bleed standing queue instead of probing up.
            this.desiredBytesPerSec =
                    Math.max(measured - STEP_BYTES_PER_SEC / 4, MIN_RATE_BYTES_PER_SEC);
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
        this.desiredBytesPerSec =
                Math.max(measured - STEP_BYTES_PER_SEC / 2, MIN_RATE_BYTES_PER_SEC);
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        this.pingNormalStreak = 0;
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
        this.pingNormalStreak = 0;
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

    private void seedInterval(long nowMillis, long wireBytes, long columns, int awaitingSize) {
        this.intervalSeeded = true;
        this.intervalStartMillis = nowMillis;
        this.intervalStartWireBytes = wireBytes;
        this.intervalStartColumns = columns;
        this.awaitingAtStart = awaitingSize > 0;
        this.haltSeenThisInterval = false;
    }

    private void hardReset() {
        this.engaged = false;
        this.desiredBytesPerSec = 0;
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        this.pingNormalStreak = 0;
        this.intervalSeeded = false;
        this.intervalStartMillis = 0;
        this.haltSeenThisInterval = false;
        // The size estimator and ping baseline survive a config-toggle reset (they are
        // measurements, not control state) but die with the session in reset().
    }

    /** Session teardown (the reset family): everything dies with the session. */
    void reset() {
        hardReset();
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
