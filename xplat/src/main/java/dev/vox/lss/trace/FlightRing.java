package dev.vox.lss.trace;

/**
 * 5 Hz in-memory trailing ring (move-desync-tracer-plan.md §1.5): 40 samples captured
 * every 4 ticks for armed players, primitive fields only — no JSON assembly happens on
 * the capture path. Flushed as one {@code flight_ring} row ahead of any event row, giving
 * 8 s of 5 Hz context that the 1 Hz flight rows structurally cannot (Fable F2-4: at
 * elytra speed the 1 Hz anchors trail 1.5–2 chunks behind the event position, so the
 * collision chunk exits the ±2 mask exactly when it matters).
 *
 * <p>Single-threaded by construction (server thread only); not thread-safe.
 */
final class FlightRing {

    static final int CAPACITY = 40;

    private final long[] wallMs = new long[CAPACITY];
    private final double[] x = new double[CAPACITY];
    private final double[] y = new double[CAPACITY];
    private final double[] z = new double[CAPACITY];
    private final double[] speed = new double[CAPACITY];
    private final long[] obuf = new long[CAPACITY];
    private final long[] gapMs = new long[CAPACITY];
    private final boolean[] hasSendState = new boolean[CAPACITY];
    private final int[] anchorCx = new int[CAPACITY];
    private final int[] anchorCz = new int[CAPACITY];
    private final int[] mask5x5 = new int[CAPACITY];
    private final int[] loaderCx = new int[CAPACITY];
    private final int[] loaderCz = new int[CAPACITY];

    private int next;
    private int size;

    void addNoSendState(long wallMs, double x, double y, double z,
                        double speed, long obuf, long gapMs) {
        add(wallMs, x, y, z, speed, obuf, gapMs, false, 0, 0, 0, 0, 0);
    }

    void add(long wallMs, double x, double y, double z, double speed, long obuf, long gapMs,
             boolean hasSendState, int anchorCx, int anchorCz, int mask5x5,
             int loaderCx, int loaderCz) {
        int i = next;
        this.wallMs[i] = wallMs;
        this.x[i] = x;
        this.y[i] = y;
        this.z[i] = z;
        this.speed[i] = speed;
        this.obuf[i] = obuf;
        this.gapMs[i] = gapMs;
        this.hasSendState[i] = hasSendState;
        this.anchorCx[i] = anchorCx;
        this.anchorCz[i] = anchorCz;
        this.mask5x5[i] = mask5x5;
        this.loaderCx[i] = loaderCx;
        this.loaderCz[i] = loaderCz;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    int size() {
        return size;
    }

    void clear() {
        next = 0;
        size = 0;
    }

    /** Visits samples oldest-first. */
    interface SampleVisitor {
        void sample(long wallMs, double x, double y, double z, double speed, long obuf,
                    long gapMs, boolean hasSendState, int anchorCx, int anchorCz,
                    int mask5x5, int loaderCx, int loaderCz);
    }

    void forEachOldestFirst(SampleVisitor visitor) {
        int start = size < CAPACITY ? 0 : next;
        for (int k = 0; k < size; k++) {
            int i = (start + k) % CAPACITY;
            visitor.sample(wallMs[i], x[i], y[i], z[i], speed[i], obuf[i], gapMs[i],
                    hasSendState[i], anchorCx[i], anchorCz[i], mask5x5[i],
                    loaderCx[i], loaderCz[i]);
        }
    }
}
