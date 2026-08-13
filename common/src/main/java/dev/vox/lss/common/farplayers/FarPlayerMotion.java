package dev.vox.lss.common.farplayers;

/**
 * Per-player motion interpolation for the E2 renderer (FARP §3.3, pure math — no MC
 * types, so the Tier 1 suite owns it). The lerp window derives from the SERVER-DECLARED
 * cadence, not a measured EWMA (review MAJOR: measurement is mutually hostile with
 * delta suppression — a suppressed-stationary player's measured gap grows unbounded,
 * so their first movement would slow-motion glide over an inflated window, and tier
 * migration shifts the gap under any filter). Measurement survives only as a
 * correction clamped to ≤{@link #MEASURED_WINDOW_CAP}× declared — which is exactly
 * what absorbs the tier multiplier (a half-rate target legitimately measures ~2×).
 *
 * <p>Past the window, position dead-reckons from the server's velocity hint
 * (the elytra case: at 4+ blocks/tick a pure hold teleport-lags), capped at
 * {@link #EXTRAPOLATION_CAP_WINDOWS} windows beyond the last snapshot; angles hold.
 *
 * <p>Single-threaded (the render thread), clock injected for tests.
 */
public final class FarPlayerMotion {

    /** Declared-window margin: cadence jitter (server tick time, network) means frames
     *  land slightly late; +20% keeps the common case inside the lerp. */
    public static final double WINDOW_MARGIN = 1.2;
    /** Measured-gap correction ceiling, in multiples of the declared window. */
    public static final double MEASURED_WINDOW_CAP = 2.0;
    /** Extrapolation ceiling, in windows past the snapshot. */
    public static final double EXTRAPOLATION_CAP_WINDOWS = 1.5;

    /** One sampled render state (positions in blocks, angles in degrees). */
    public record Sample(double x, double y, double z, float yaw, float headYaw, float pitch) {}

    private double fromX, fromY, fromZ;
    private double toX, toY, toZ;
    private float fromYaw, toYaw, fromHeadYaw, toHeadYaw, fromPitch, toPitch;
    private double velX, velY, velZ; // blocks/second (the wire hint)
    private long snapshotMillis;
    private long windowMillis;

    public FarPlayerMotion(FarPlayerWire.UpdateEntry e, int cadenceTicks, long nowMillis) {
        this.toX = FarPlayerWire.dequantizePos(e.quantX());
        this.toY = FarPlayerWire.dequantizePos(e.quantY());
        this.toZ = FarPlayerWire.dequantizePos(e.quantZ());
        this.fromX = toX;
        this.fromY = toY;
        this.fromZ = toZ;
        this.toYaw = FarPlayerWire.byteToAngle(e.yaw());
        this.toHeadYaw = FarPlayerWire.byteToAngle(e.headYaw());
        this.toPitch = FarPlayerWire.byteToAngle(e.pitch());
        this.fromYaw = toYaw;
        this.fromHeadYaw = toHeadYaw;
        this.fromPitch = toPitch;
        this.velX = FarPlayerWire.shortToVelocity(e.velX());
        this.velY = FarPlayerWire.shortToVelocity(e.velY());
        this.velZ = FarPlayerWire.shortToVelocity(e.velZ());
        this.snapshotMillis = nowMillis;
        this.windowMillis = declaredWindowMillis(cadenceTicks);
    }

    /** Vehicle-motion seed (R-10 v1.3): the mount's own wire position/angles with the
     *  RIDER's velocity hint FROM CREATION (E3 review m3 — a zero-velocity seed held
     *  the mount still for one full window while the rider dead-reckoned ahead:
     *  exactly the shear the rider-velocity design exists to prevent; rider and
     *  mount share a velocity by definition). Vehicles have yaw+pitch only; headYaw
     *  mirrors yaw. */
    public FarPlayerMotion(double x, double y, double z, float yaw, float pitch,
                           double riderVelX, double riderVelY, double riderVelZ,
                           int cadenceTicks, long nowMillis) {
        this.toX = x;
        this.toY = y;
        this.toZ = z;
        this.fromX = x;
        this.fromY = y;
        this.fromZ = z;
        this.toYaw = yaw;
        this.toHeadYaw = yaw;
        this.toPitch = pitch;
        this.fromYaw = yaw;
        this.fromHeadYaw = yaw;
        this.fromPitch = pitch;
        this.velX = riderVelX;
        this.velY = riderVelY;
        this.velZ = riderVelZ;
        this.snapshotMillis = nowMillis;
        this.windowMillis = declaredWindowMillis(cadenceTicks);
    }

    /** A new frame: the CURRENT sampled state becomes the lerp origin (never the raw
     *  previous target — a frame landing mid-lerp must not snap). */
    public void apply(FarPlayerWire.UpdateEntry e, int cadenceTicks, long nowMillis) {
        applyRaw(FarPlayerWire.dequantizePos(e.quantX()),
                FarPlayerWire.dequantizePos(e.quantY()),
                FarPlayerWire.dequantizePos(e.quantZ()),
                FarPlayerWire.byteToAngle(e.yaw()),
                FarPlayerWire.byteToAngle(e.headYaw()),
                FarPlayerWire.byteToAngle(e.pitch()),
                FarPlayerWire.shortToVelocity(e.velX()),
                FarPlayerWire.shortToVelocity(e.velY()),
                FarPlayerWire.shortToVelocity(e.velZ()),
                cadenceTicks, nowMillis);
    }

    /** The raw-value form of {@link #apply} — the vehicle-motion entry point (mount
     *  position + RIDER velocity), same window/anchoring semantics. */
    public void applyRaw(double x, double y, double z, float yaw, float headYaw,
                         float pitch, double velXPerSec, double velYPerSec,
                         double velZPerSec, int cadenceTicks, long nowMillis) {
        Sample cur = sample(nowMillis);
        this.fromX = cur.x();
        this.fromY = cur.y();
        this.fromZ = cur.z();
        this.fromYaw = cur.yaw();
        this.fromHeadYaw = cur.headYaw();
        this.fromPitch = cur.pitch();
        this.toX = x;
        this.toY = y;
        this.toZ = z;
        this.toYaw = yaw;
        this.toHeadYaw = headYaw;
        this.toPitch = pitch;
        this.velX = velXPerSec;
        this.velY = velYPerSec;
        this.velZ = velZPerSec;
        long declared = declaredWindowMillis(cadenceTicks);
        long measured = nowMillis - this.snapshotMillis;
        // Declared+margin is the floor; the measured gap corrects upward (the tier
        // multiplier) but never past the cap (the suppressed-stationary armor).
        this.windowMillis = Math.min(Math.max(declared, measured),
                (long) (MEASURED_WINDOW_CAP * declaredBaseMillis(cadenceTicks)));
        this.snapshotMillis = nowMillis;
    }

    public Sample sample(long nowMillis) {
        long dt = nowMillis - snapshotMillis;
        if (dt <= 0) {
            return new Sample(fromX, fromY, fromZ, fromYaw, fromHeadYaw, fromPitch);
        }
        if (dt <= windowMillis) {
            double t = dt / (double) windowMillis;
            return new Sample(
                    fromX + (toX - fromX) * t,
                    fromY + (toY - fromY) * t,
                    fromZ + (toZ - fromZ) * t,
                    rotLerp((float) t, fromYaw, toYaw),
                    rotLerp((float) t, fromHeadYaw, toHeadYaw),
                    rotLerp((float) t, fromPitch, toPitch));
        }
        // Past the window: dead-reckon from the velocity hint, capped; angles hold.
        double extraSeconds = Math.min(dt - windowMillis,
                (long) (EXTRAPOLATION_CAP_WINDOWS * windowMillis)) / 1000.0;
        return new Sample(
                toX + velX * extraSeconds,
                toY + velY * extraSeconds,
                toZ + velZ * extraSeconds,
                toYaw, toHeadYaw, toPitch);
    }

    /** True while the sample is a moving one (lerp in progress or extrapolating with
     *  a nonzero hint). Diagnostic/cadence input — the renderer derives its walk
     *  animation from positional deltas, not from this (E2 review n8). */
    public boolean isMoving(long nowMillis) {
        long dt = nowMillis - snapshotMillis;
        if (dt <= windowMillis) {
            return fromX != toX || fromY != toY || fromZ != toZ;
        }
        return (velX != 0 || velY != 0 || velZ != 0)
                && dt - windowMillis < (long) (EXTRAPOLATION_CAP_WINDOWS * windowMillis);
    }

    public long windowMillis() {
        return windowMillis;
    }

    private static long declaredBaseMillis(int cadenceTicks) {
        return Math.max(1, cadenceTicks) * 50L;
    }

    private static long declaredWindowMillis(int cadenceTicks) {
        return (long) (declaredBaseMillis(cadenceTicks) * WINDOW_MARGIN);
    }

    /** Shortest-path degree lerp (the Mth.rotLerp contract, MC-free). */
    static float rotLerp(float t, float from, float to) {
        float d = ((to - from) % 360f + 540f) % 360f - 180f;
        return from + t * d;
    }
}
