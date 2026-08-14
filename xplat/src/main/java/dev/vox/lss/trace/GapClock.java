package dev.vox.lss.trace;

/**
 * Per-player move-packet gap clock (move-desync-tracer-plan.md §1.2, review U-5): the
 * only server-side client-stall measurement, and it works identically in the LOD-off
 * control arms. {@link #record} is called from the {@code handleMovePlayer} HEAD hook;
 * both reads are cheap enough for the 5 Hz ring.
 *
 * <p>The trailing-window max is kept in coarse buckets (8 x 640 ms ≈ the 5 s window)
 * rather than a sample list — O(1) per record, no allocation, and the ±one-bucket edge
 * blur is irrelevant for a diagnostic whose interesting values are hundreds of ms.
 *
 * <p>Single-threaded by construction (only the server thread touches a player's clock);
 * not thread-safe.
 */
final class GapClock {

    private static final int BUCKETS = 8;
    private static final long BUCKET_MILLIS = 640;

    private final long[] bucketMax = new long[BUCKETS];
    private long currentBucket = Long.MIN_VALUE;
    private long lastMoveMs = Long.MIN_VALUE;
    private long lastGapMs;

    /** Records a move packet at {@code nowMs}; returns the gap since the previous one
     *  (0 for the first packet of a connection). */
    long record(long nowMs) {
        long gap = lastMoveMs == Long.MIN_VALUE ? 0 : Math.max(0, nowMs - lastMoveMs);
        lastMoveMs = nowMs;
        lastGapMs = gap;
        long bucket = Math.floorDiv(nowMs, BUCKET_MILLIS);
        if (currentBucket != Long.MIN_VALUE && bucket < currentBucket) {
            // Backwards wall-clock step (NTP): a stale "future" maximum would otherwise
            // be re-admitted by the window scan for up to one window (review A-9). Full
            // reset — the clock re-learns in one bucket.
            java.util.Arrays.fill(bucketMax, 0);
            currentBucket = Long.MIN_VALUE;
        }
        if (bucket != currentBucket) {
            // Zero every bucket the clock skipped over (idle players), then move in.
            long skipped = currentBucket == Long.MIN_VALUE ? BUCKETS : bucket - currentBucket;
            for (long i = 0; i < Math.min(skipped, BUCKETS); i++) {
                bucketMax[(int) Math.floorMod(bucket - i, BUCKETS)] = 0;
            }
            currentBucket = bucket;
        }
        int idx = (int) Math.floorMod(bucket, BUCKETS);
        if (gap > bucketMax[idx]) bucketMax[idx] = gap;
        return gap;
    }

    /** The most recent recorded gap. */
    long lastGapMs() {
        return lastGapMs;
    }

    /**
     * Max gap recorded in roughly the trailing 5 s. Also folds in the CURRENTLY OPEN gap
     * (time since the last packet) — a client that stopped sending 3 s ago has a 3 s
     * stall in progress that no recorded gap shows yet.
     */
    long maxGapWindowMs(long nowMs) {
        long max = lastMoveMs == Long.MIN_VALUE ? 0 : Math.max(0, nowMs - lastMoveMs);
        if (currentBucket == Long.MIN_VALUE) return max;
        long bucket = Math.floorDiv(nowMs, BUCKET_MILLIS);
        for (int i = 0; i < BUCKETS; i++) {
            long b = bucket - i;
            if (b < 0 || bucket - b >= BUCKETS) break;
            // Buckets older than the clock's current position hold stale data from a
            // previous wrap only if the clock never wrote in between — record() zeroes
            // skipped buckets, so a bucket is trustworthy iff it is not ahead of
            // currentBucket.
            if (b > currentBucket) continue;
            long v = bucketMax[(int) Math.floorMod(b, BUCKETS)];
            if (v > max) max = v;
        }
        return max;
    }
}
