package dev.vox.lss.common.store;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Opt-in background store backfill (plan §Population 3, Phase 4 — ships DEFAULT OFF):
 * walks every region file of every store-known dimension, Chebyshev-nearest-to-spawn
 * first, reads each present chunk through the PLATFORM's normal NBT serve path (same
 * serializers as live serves, so deposited bytes match serve bytes) and deposits it —
 * warming the store for terrain no client has asked for yet.
 *
 * <p><b>Self-restraint</b> (the plan's explicit anti-goal is v1's "pause while any
 * player backlog is nonempty", which never releases under 1 Hz re-declaration):
 * <ul>
 *   <li>one MIN_PRIORITY thread, ONE synchronous read at a time;</li>
 *   <li>{@code readerHeadroom} — pauses while the player-serving reader pool has no
 *       spare capacity (the same self-restraint gate the want-set router obeys);</li>
 *   <li>{@code tickHealthy} — pauses while the server tick is over the ceiling (the
 *       plan's MSPT gate; the platform wires its own tick-time source);</li>
 *   <li>a column-rate cap ({@value #MAX_COLUMNS_PER_SECOND}/s) so an idle server
 *       still trickles rather than bursts.</li>
 * </ul>
 *
 * <p><b>Resumability:</b> finished regions are marked in the store's {@code backfill}
 * progress table (batcher-written, dropped with the DB like all derived data); a
 * restart re-enumerates and skips done regions. Columns whose store row already exists
 * are skipped without a read ({@code get()} — the row's freshness is the sweep's job).
 *
 * <p>All failure shapes are contained per column/region (counted {@code
 * store.errors} via the store's own paths where applicable); the driver never throws
 * out of its thread.
 */
public final class StoreBackfill {

    /** Rate cap; also the pause-check cadence denominator. */
    static final int MAX_COLUMNS_PER_SECOND = 100;
    private static final long PAUSE_POLL_MILLIS = 500;

    /** Platform seam: synchronously read + serialize one column's wire bytes from
     *  region NBT (the SAME path serves use); null = not servable (absent/all-air is
     *  byte[0] where servable-empty). Throws = contained, counted, skipped. */
    @FunctionalInterface
    public interface ColumnReader {
        byte[] read(String dimension, int cx, int cz) throws Exception;
    }

    private final SqliteLodStore store;
    private final Function<String, Path> regionDirResolver;
    private final Function<String, long[]> spawnChunkResolver; // dim -> {cx, cz}
    private final List<String> dimensions;
    private final ColumnReader columnReader;
    private final BooleanSupplier readerHeadroom;
    private final BooleanSupplier tickHealthy;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private volatile Thread worker;
    private volatile String statusLine = "idle";

    public StoreBackfill(SqliteLodStore store,
                         Function<String, Path> regionDirResolver,
                         Function<String, long[]> spawnChunkResolver,
                         List<String> dimensions,
                         ColumnReader columnReader,
                         BooleanSupplier readerHeadroom,
                         BooleanSupplier tickHealthy) {
        this.store = store;
        this.regionDirResolver = regionDirResolver;
        this.spawnChunkResolver = spawnChunkResolver;
        this.dimensions = List.copyOf(dimensions);
        this.columnReader = columnReader;
        this.readerHeadroom = readerHeadroom;
        this.tickHealthy = tickHealthy;
    }

    /** Idempotent start; returns false if already running. */
    public boolean start() {
        if (!this.running.compareAndSet(false, true)) return false;
        this.stopRequested.set(false);
        var t = new Thread(this::run, "LSS Store Backfill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        this.worker = t;
        t.start();
        return true;
    }

    /** Requests stop; the worker exits at the next column boundary. */
    public boolean stop() {
        if (!this.running.get()) return false;
        this.stopRequested.set(true);
        return true;
    }

    public boolean isRunning() {
        return this.running.get();
    }

    public String statusLine() {
        return this.statusLine;
    }

    public void shutdown() {
        stop();
        var t = this.worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record RegionRef(String dim, int rx, int rz, Path mca, int chebFromSpawn) {}

    private void run() {
        long deposited = 0, skipped = 0, errors = 0, regionsDone = 0, pauses = 0;
        try {
            // The store serves nothing until its startup sweep completes, and get()/
            // hasRow() read as misses in that window — starting the walk early would
            // re-read and re-deposit everything already present (review MAJOR: the
            // config auto-start races the sweep).
            if (!this.store.awaitSweep(TimeUnit.MINUTES.toMillis(5))) {
                this.statusLine = "failed: store sweep never completed";
                return;
            }
            List<RegionRef> plan = enumerate();
            LSSLogger.info("Store backfill: " + plan.size() + " region(s) to process");
            long windowStartNanos = System.nanoTime();
            int windowCols = 0;
            for (RegionRef region : plan) {
                if (this.stopRequested.get()) break;
                if (!this.store.isHealthy()) {
                    // A latched store no-ops every write while counters would keep
                    // claiming progress — burning a world's worth of IO for nothing.
                    this.statusLine = "aborted: store unhealthy (latched off?)";
                    LSSLogger.warn("Store backfill aborted — store no longer healthy");
                    return;
                }
                int[] present = presentChunks(region.mca());
                if (present == null) continue; // unreadable header: skip, sweep owns it
                long regionErrors = 0;
                long dropsBefore = this.store.diagnostics().getDepositDrops();
                for (int idx = 0; idx < 1024; idx++) {
                    if (this.stopRequested.get()) break;
                    if (present[idx] == 0) continue;
                    // Restraint + rate cap cover EVERY visited column — the skip rung
                    // included (review: a warm region walk was 1024 unpaced full-row
                    // reads; hasRow is the cheap existence probe, but even it is paced).
                    pauses += pauseWhileRestrained();
                    if (this.stopRequested.get()) break;
                    int cx = (region.rx() << 5) + (idx & 31);
                    int cz = (region.rz() << 5) + (idx >> 5);
                    long packed = PositionUtil.packPosition(cx, cz);
                    if (this.store.hasRow(region.dim(), packed)) {
                        skipped++;
                        this.store.diagnostics().recordBackfillSkip();
                    } else {
                        try {
                            byte[] bytes = this.columnReader.read(region.dim(), cx, cz);
                            this.store.diagnostics().recordBackfillRead();
                            if (bytes != null) {
                                this.store.deposit(region.dim(), packed, bytes,
                                        System.currentTimeMillis() / 1000L);
                                deposited++;
                                this.store.diagnostics().recordBackfillDeposit();
                            } else {
                                skipped++;
                                this.store.diagnostics().recordBackfillSkip();
                            }
                        } catch (Exception e) {
                            errors++;
                            regionErrors++;
                            // Visible to operators: backfill failures count store.errors
                            // (review: the exact A7 failure mode this feature re-enters
                            // was invisible to every exporter).
                            this.store.diagnostics().recordError();
                            if (errors <= 3) {
                                LSSLogger.warn("Store backfill: read failed at " + region.dim()
                                        + " [" + cx + "," + cz + "] — skipping", e);
                            }
                        }
                    }
                    // Rate cap: MAX_COLUMNS_PER_SECOND visited columns per 1 s window.
                    windowCols++;
                    if (windowCols >= MAX_COLUMNS_PER_SECOND) {
                        long elapsed = System.nanoTime() - windowStartNanos;
                        long remain = 1_000_000_000L - elapsed;
                        if (remain > 0) Thread.sleep(remain / 1_000_000L + 1);
                        windowStartNanos = System.nanoTime();
                        windowCols = 0;
                    }
                    this.statusLine = "running: " + regionsDone + " regions done, "
                            + deposited + " deposited, " + skipped + " skipped, "
                            + errors + " errors, " + pauses + " pauses";
                }
                // Done-mark ONLY a cleanly processed region: errors or shed deposits
                // would otherwise turn transient IO trouble into permanent warm-holes
                // that resumability never revisits (review MAJOR). An unmarked region
                // re-walks cheaply (hasRow skips).
                boolean depositsShed =
                        this.store.diagnostics().getDepositDrops() > dropsBefore;
                if (!this.stopRequested.get() && regionErrors == 0 && !depositsShed) {
                    this.store.markBackfillRegionDone(region.dim(), region.rx(), region.rz());
                    regionsDone++;
                }
            }
            this.statusLine = (this.stopRequested.get() ? "stopped: " : "complete: ")
                    + regionsDone + " regions, " + deposited + " deposited, "
                    + skipped + " skipped, " + errors + " errors, " + pauses + " pauses";
        } catch (InterruptedException e) {
            this.statusLine = "stopped (shutdown): " + regionsDone + " regions, "
                    + deposited + " deposited, " + skipped + " skipped, "
                    + errors + " errors";
        } catch (Throwable t) {
            this.statusLine = "failed: " + t;
            LSSLogger.warn("Store backfill aborted", t);
        } finally {
            // The summary must print on EVERY exit path (the interrupt path used to
            // swallow it, so the one line carrying the error count never appeared).
            LSSLogger.info("Store backfill " + this.statusLine);
            this.running.set(false);
        }
    }

    /** All not-yet-done regions of all dims, Chebyshev-nearest-to-spawn first
     *  (players cluster spawn-side — the plan's traversal order). */
    private List<RegionRef> enumerate() {
        List<RegionRef> plan = new ArrayList<>();
        for (String dim : this.dimensions) {
            Path dir;
            try {
                dir = this.regionDirResolver.apply(dim);
            } catch (Throwable t) {
                continue;
            }
            if (dir == null || !Files.isDirectory(dir)) continue;
            long[] spawn = this.spawnChunkResolver.apply(dim);
            int spawnRx = (int) spawn[0] >> 5;
            int spawnRz = (int) spawn[1] >> 5;
            try (var stream = Files.list(dir)) {
                for (Path mca : (Iterable<Path>) stream::iterator) {
                    String name = mca.getFileName().toString();
                    if (!name.startsWith("r.") || !name.endsWith(".mca")) continue;
                    String[] parts = name.split("\\.");
                    if (parts.length != 4) continue;
                    int rx, rz;
                    try {
                        rx = Integer.parseInt(parts[1]);
                        rz = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (this.store.isBackfillRegionDone(dim, rx, rz)) continue;
                    int cheb = Math.max(Math.abs(rx - spawnRx), Math.abs(rz - spawnRz));
                    plan.add(new RegionRef(dim, rx, rz, mca, cheb));
                }
            } catch (Exception e) {
                LSSLogger.warn("Store backfill: cannot list region dir for " + dim, e);
            }
        }
        plan.sort(Comparator.comparingInt(RegionRef::chebFromSpawn));
        return plan;
    }

    /** The region header's location table: nonzero = chunk present. Null = unreadable. */
    private int[] presentChunks(Path mca) {
        try (FileChannel ch = FileChannel.open(mca)) {
            ByteBuffer buf = ByteBuffer.allocate(4096);
            int read = 0;
            while (read < 4096) {
                int n = ch.read(buf, read);
                if (n < 0) return null;
                read += n;
            }
            buf.flip();
            int[] loc = new int[1024];
            for (int i = 0; i < 1024; i++) loc[i] = buf.getInt(i * 4);
            return loc;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the number of pause intervals slept (observability: the status line and
     *  gate evidence need to show restraint actually FIRING, not just existing). */
    private long pauseWhileRestrained() throws InterruptedException {
        long pauses = 0;
        while (!this.stopRequested.get()
                && (!this.readerHeadroom.getAsBoolean() || !this.tickHealthy.getAsBoolean())) {
            this.statusLine = "paused (yielding to players/tick)";
            Thread.sleep(PAUSE_POLL_MILLIS);
            pauses++;
        }
        return pauses;
    }
}
