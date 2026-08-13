package dev.vox.lss.common.processing;

/**
 * The vanilla-ping backstop (adaptive-transfer-rate-plan.md, Mechanism B): a coarse,
 * server-side, all-clients governor on the one signal that sees every buffer LSS
 * cannot — the player's vanilla keepalive latency (26.2: smoothed
 * {@code (3·old+new)/4} on a 15 s send cadence, which reads Mechanism A's burst
 * sawtooth near its MEAN — silently load-bearing for the two mechanisms' operating-
 * region separation). It multiplies the per-player bandwidth allocation by a factor
 * that cuts when ping excess over a rolling-min baseline crosses
 * {@link #CUT_EXCESS_MS} while LSS was actually sending (the attribution guard), and
 * recovers slowly once excess normalizes.
 *
 * <p>The FIRST cut BINDS (review m7): {@code factor = min(0.5·factor,
 * 0.5·recentSendRate/cap)} — blind halvings from 1.0 would need ~6 adjustments ×
 * ~15 s keepalive cadence before landing below a slow link's capacity. Documented
 * over-cut (review m8): after a binding cut the standing queue keeps excess elevated
 * for many keepalives while it drains, so congestion events may drive the factor to
 * its floor with recovery over minutes — acceptable for a backstop; keepalive latency
 * also includes server MSPT stalls, so a lag spike mass-cuts every active player (the
 * safe direction).
 *
 * <p>State is per player per session (dies with the request state), touched only on
 * the service pump thread; {@link #factor()} is volatile for Paper's off-pump diag
 * read. On Folia a stale-int latency read off the pump is benign.
 */
public final class PingBackstop {

    /** Ping excess that triggers a cut — the timeout-and-multi-second-lag class, not
     *  fine tuning (Mechanism A converges far below this — the operating-region
     *  separation). */
    public static final int CUT_EXCESS_MS = 750;
    /** Excess below this counts toward recovery. */
    public static final int RECOVER_EXCESS_MS = 250;
    /** Adjustments run at most once per this interval (keepalive cadence ~15 s — the
     *  loop is deliberately coarse). */
    public static final long ADJUST_INTERVAL_MILLIS = 5_000L;
    /** Attribution guard: never punish an LSS-idle session for someone else's
     *  congestion — a cut requires at least this many LSS bytes sent since the last
     *  adjustment. */
    public static final long ATTRIBUTION_MIN_BYTES = 64L * 1024;
    /** The factor floors at whatever yields this effective rate. */
    public static final long FLOOR_BYTES_PER_SEC = 64L * 1024;
    /** Consecutive calm adjustments before a recovery step. */
    static final int RECOVER_STREAK = 3;
    static final double RECOVER_MULTIPLIER = 1.25;
    /** Baseline upward drift: +1 ms/s — a genuinely changed route re-baselines in
     *  minutes; a distant player's natural ping must never read as congestion. */
    private static final long BASELINE_DRIFT_MS_PER_SEC = 1;

    private volatile double factor = 1.0;

    private int baselineMs = -1;             // -1 = unseeded; zero/absent samples ignored
    private long baselineDriftAnchorMillis;
    private boolean windowSeeded;            // first observe() only anchors the window
    private long lastAdjustMillis;
    private int lastAdjustPing = Integer.MIN_VALUE;
    private long lastAdjustBytesSent;
    private int calmStreak;

    /**
     * One observation per service tick. {@code pingMs <= 0} = no sample (ignored for
     * the baseline; no adjustment runs blind). {@code totalBytesSent} is the state's
     * cumulative sent counter — the 5 s window's send rate and the attribution guard
     * both derive from its deltas. {@code capBytesPerSec} is the per-player cap the
     * factor multiplies (the binding-cut anchor).
     */
    public void observe(long nowMillis, int pingMs, long totalBytesSent, long capBytesPerSec) {
        if (pingMs > 0) {
            if (this.baselineMs < 0) {
                this.baselineMs = pingMs;
                this.baselineDriftAnchorMillis = nowMillis;
            } else if (pingMs < this.baselineMs) {
                this.baselineMs = pingMs;
            }
        }
        if (this.baselineMs >= 0) {
            long driftSec = (nowMillis - this.baselineDriftAnchorMillis) / 1000L;
            if (driftSec > 0) {
                this.baselineMs += (int) Math.min(Integer.MAX_VALUE,
                        driftSec * BASELINE_DRIFT_MS_PER_SEC);
                this.baselineDriftAnchorMillis += driftSec * 1000L;
            }
        }
        if (!this.windowSeeded) {
            // The first observation only ANCHORS the send-bytes window — an adjustment
            // needs a full window behind it before attribution means anything.
            this.windowSeeded = true;
            this.lastAdjustMillis = nowMillis;
            this.lastAdjustBytesSent = totalBytesSent;
            return;
        }
        if (nowMillis - this.lastAdjustMillis < ADJUST_INTERVAL_MILLIS) return;
        if (pingMs <= 0 || this.baselineMs < 0) return;
        // "Only when the latency value has changed since the last adjustment": the
        // keepalive updates ~every 15 s, so most 5 s windows see the same smoothed
        // value — re-adjusting on it would triple the loop gain for free.
        if (pingMs == this.lastAdjustPing) return;

        long sentSinceLast = Math.max(0, totalBytesSent - this.lastAdjustBytesSent);
        long windowMillis = nowMillis - this.lastAdjustMillis;
        this.lastAdjustMillis = nowMillis;
        this.lastAdjustPing = pingMs;
        this.lastAdjustBytesSent = totalBytesSent;

        int excess = pingMs - this.baselineMs;
        if (excess > CUT_EXCESS_MS && sentSinceLast > ATTRIBUTION_MIN_BYTES
                && capBytesPerSec > 0) {
            double sendRate = sentSinceLast * 1000.0 / Math.max(1L, windowMillis);
            // The binding first cut (m7): land below the OBSERVED send rate at once.
            double cut = Math.min(0.5 * this.factor, 0.5 * sendRate / capBytesPerSec);
            double floor = Math.min(1.0, (double) FLOOR_BYTES_PER_SEC / capBytesPerSec);
            this.factor = Math.max(floor, cut);
            this.calmStreak = 0;
        } else if (excess < RECOVER_EXCESS_MS && this.factor < 1.0) {
            if (++this.calmStreak >= RECOVER_STREAK) {
                this.calmStreak = 0;
                this.factor = Math.min(1.0, this.factor * RECOVER_MULTIPLIER);
            }
        }
    }

    /** Multiplies the per-player allocation — the m12 plumbing: this MUST ride the
     *  {@code allocationBytes} argument into {@code flushSendQueue}, where the
     *  per-player bucket's bank clamp ({@code burstCap = allocation/4}) shrinks the
     *  banked burst on the first post-cut tick. */
    public long apply(long allocationBytes) {
        double f = this.factor;
        return f >= 1.0 ? allocationBytes : (long) (allocationBytes * f);
    }

    /** The `pingf=` diag gauge (volatile — Paper reads diag off-pump). */
    public double factor() {
        return this.factor;
    }

    /** Kill-switch hygiene: a disabled backstop must not leave a stale cut applied. */
    public void resetFactor() {
        this.factor = 1.0;
        this.calmStreak = 0;
    }
}
