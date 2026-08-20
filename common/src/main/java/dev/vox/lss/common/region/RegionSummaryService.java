package dev.vox.lss.common.region;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.common.PositionUtil;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The region-summary server core (region-summary-sync-plan.md §5): per-player
 * latest-wins request mailboxes, a dedicated MIN_PRIORITY sweeper daemon that windows
 * the stamp table and assembles frames (all filesystem work OFF the tick and OFF the
 * reader pool — the StoreBackfill restraint precedent), and a ready-frame queue the
 * platform tick drains onto its dedicated send lane.
 *
 * <p>Thread shape: {@link #offerRequest} from any ingress thread (Fabric network
 * thread, Paper messenger thread, a Folia region thread — pure data, no entity
 * access); {@link #pump} from the platform's tick thread (the only place player state
 * is consulted — dimension, chunk anchor — so Folia thread-legality holds); the
 * sweeper thread runs {@code TileStampSource} lookups (stat + 8 KiB header IO) and
 * encodes. Latest-wins everywhere: portal spam collapses to one pending request per
 * player, a re-entered dimension's fresh request replaces the stale one.
 *
 * <p>Admission (pump): a request is admitted only when the player's registered
 * dimension MATCHES the request's declared dimension and the player anchor is known —
 * until then it is RETAINED (the request can race the server-side re-registration by
 * a tick or two) up to {@link #PENDING_TTL_NANOS}, then dropped (the stale-portal
 * shape; the client's next dimension entry re-requests). The declared RADIUS and
 * CENTER are clamped to the SERVER's own window — {@code ceil(lodDistanceChunks/32)+1}
 * tiles around the player's tile, counted {@code summary.range_filtered} — never to
 * the protocol max (P2 review I-M1: the protocol max is a wire-domain cap, not a prune
 * radius; unclamped, a ~30-byte request buys a 17k-tile sweep). A hostile center/radius
 * can only select the same window an honest client gets.
 *
 * <p>The flood bound is admission-side, in three layers (P2 review I-M1 — mailbox
 * coalescing bounds CONCURRENCY, not rate): latest-wins pending (one in-flight request
 * per player), the window clamp above (bounds one sweep's cost to the server's own
 * configured disc), and {@link #RESWEEP_COOLDOWN_NANOS} (bounds the rate — a re-sweep
 * inside the stamp table's stat horizon reads identical memos, zero information). A
 * cooldown-held request is RETAINED, not dropped, and admits when the cooldown lapses.
 * Frame bytes are deliberately outside SharedBandwidthLimiter: the clamped, cooled
 * bound is ~a few KB per player per cooldown — counted in their own
 * {@code summary.bytes} lane so cross-identity audits stay exact.
 */
public final class RegionSummaryService {

    /** The window a raced request waits for its registration before dropping. */
    static final long PENDING_TTL_NANOS = 5_000_000_000L;
    /** Per-player minimum interval between admitted sweeps — the stamp table's stat
     *  horizon: inside it a re-sweep reads identical memos (zero information), so
     *  holding the request costs the client nothing but staleness it already has. */
    static final long RESWEEP_COOLDOWN_NANOS = 5_000_000_000L;

    /** The stamp oracle — {@code RegionStampTable} in production; a seam for tests. */
    @FunctionalInterface
    public interface TileStampSource {
        /** Epoch second at/before which the tile's content last changed;
         *  {@link RegionSummaryWire#STAMP_NO_REGION} when no region file exists;
         *  {@link RegionSummaryWire#STAMP_NEVER_CLEAN} on any doubt. */
        long tileStampSeconds(String dimension, int tileX, int tileZ);
    }

    /** Tick-thread view of one registered player: its CURRENT dimension and its
     *  packed chunk anchor. Null anchor = not registered / not yet stamped — retry. */
    public record PlayerAnchor(String dimension, int chunkX, int chunkZ) {}

    /** The platform's send hook, called on the TICK thread (drain). Returns whether
     *  the frame actually left (a vanished player drops it) — {@code summary.frames}/
     *  {@code summary.bytes} count only true returns, so the counters mean "put on
     *  the wire", not "assembled" (P2 review I-m7). */
    @FunctionalInterface
    public interface FrameSender {
        boolean send(UUID player, byte[] frame);
    }

    private record Pending(RegionSummaryWire.Request request, long expiresAtNanos) {}
    private record SweepJob(String dimension, int centerTileX, int centerTileZ, int tileRadius) {}
    private record ReadyFrame(UUID player, byte[] frame) {}

    private final TileStampSource source;
    private final RegionSummaryDiagnostics diag = new RegionSummaryDiagnostics();
    private final LongSupplier nanoClock;
    /** The SERVER's live lodDistanceChunks — the admission window derives from it. */
    private final java.util.function.IntSupplier lodDistanceChunks;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SweepJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastSweepNanos = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ReadyFrame> ready = new ConcurrentLinkedQueue<>();
    private final Object sweeperWake = new Object();
    private final Thread sweeper;
    private volatile boolean shutdown;
    private final LogThrottle assembleFailWarn = new LogThrottle(60_000);

    public RegionSummaryService(TileStampSource source,
                                java.util.function.IntSupplier lodDistanceChunks) {
        this(source, lodDistanceChunks, System::nanoTime);
    }

    RegionSummaryService(TileStampSource source,
                         java.util.function.IntSupplier lodDistanceChunks,
                         LongSupplier nanoClock) {
        this.source = source;
        this.lodDistanceChunks = lodDistanceChunks;
        this.nanoClock = nanoClock;
        this.sweeper = new Thread(this::sweeperLoop, Brand.shortName() + " Region Summary Sweeper");
        this.sweeper.setDaemon(true);
        this.sweeper.setPriority(Thread.MIN_PRIORITY);
        this.sweeper.start();
    }

    public RegionSummaryDiagnostics diagnostics() {
        return this.diag;
    }

    /**
     * Ingress (any thread): latest-wins per player. Decode/config gating is the
     * CALLER's job (the handler-checked kill switch); this only stores pure data.
     */
    public void offerRequest(UUID player, RegionSummaryWire.Request request) {
        this.diag.recordRequest();
        this.pending.put(player, new Pending(request, this.nanoClock.getAsLong() + PENDING_TTL_NANOS));
    }

    /** NETWORK-disconnect sweep (never the dimension-change remove+register cycle —
     *  that would race the client's at-entry request): pending/queued work and the
     *  cooldown mark die with the connection. The TTL/vanished-send belts keep a
     *  missed call harmless except for the cooldown entry, which THIS is the sweep for. */
    public void removePlayer(UUID player) {
        this.pending.remove(player);
        this.jobs.remove(player);
        this.lastSweepNanos.remove(player);
    }

    /**
     * The tick-thread half: admit dimension-matched pending requests into sweep jobs
     * (clamping centers to the player's window), and drain ready frames to the sender.
     */
    public void pump(Function<UUID, PlayerAnchor> anchors, FrameSender sender) {
        long now = this.nanoClock.getAsLong();
        // The server-derived window (class javadoc): one ring of margin over the
        // server's OWN configured disc, never the protocol max.
        int maxTiles = Math.clamp((this.lodDistanceChunks.getAsInt() + 31) / 32 + 1,
                0, RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS);
        boolean admitted = false;
        for (UUID player : this.pending.keySet()) {
            Pending p = this.pending.get(player);
            if (p == null) continue;
            PlayerAnchor anchor = anchors.apply(player);
            if (anchor == null || !anchor.dimension().equals(p.request().dimension())) {
                // Registration race (the request can beat the server-side dimension
                // swap by a tick or two): retain until the TTL, then drop — a truly
                // stale request (player moved on) must not linger forever.
                if (now - p.expiresAtNanos() > 0) {
                    this.pending.remove(player, p);
                }
                continue;
            }
            Long last = this.lastSweepNanos.get(player);
            if (last != null && now - last < RESWEEP_COOLDOWN_NANOS) {
                // Rate bound (class javadoc): RETAINED, admitted when the cooldown
                // lapses — latest-wins keeps only the newest request meanwhile.
                continue;
            }
            this.pending.remove(player, p); // keyed remove: a newer put survives
            int playerTileX = anchor.chunkX() >> 5;
            int playerTileZ = anchor.chunkZ() >> 5;
            int radius = Math.min(p.request().tileRadius(), maxTiles);
            int cx = Math.clamp(p.request().centerTileX(),
                    playerTileX - maxTiles, playerTileX + maxTiles);
            int cz = Math.clamp(p.request().centerTileZ(),
                    playerTileZ - maxTiles, playerTileZ + maxTiles);
            if (radius != p.request().tileRadius()
                    || cx != p.request().centerTileX() || cz != p.request().centerTileZ()) {
                this.diag.recordRangeFiltered();
            }
            this.lastSweepNanos.put(player, now);
            this.jobs.put(player, new SweepJob(p.request().dimension(), cx, cz, radius));
            admitted = true;
        }
        if (admitted) {
            synchronized (this.sweeperWake) {
                this.sweeperWake.notifyAll();
            }
        }
        ReadyFrame frame;
        while ((frame = this.ready.poll()) != null) {
            if (sender.send(frame.player(), frame.frame())) {
                this.diag.recordFrameSent(frame.frame().length);
            }
        }
    }

    private void sweeperLoop() {
        while (!this.shutdown) {
            try {
                if (this.jobs.isEmpty()) {
                    synchronized (this.sweeperWake) {
                        if (this.jobs.isEmpty() && !this.shutdown) {
                            this.sweeperWake.wait(200);
                        }
                    }
                    continue;
                }
                for (UUID player : this.jobs.keySet()) {
                    SweepJob job = this.jobs.remove(player);
                    if (job == null) continue;
                    try {
                        assemble(player, job);
                    } catch (Throwable t) {
                        // One job's failure (IO surprise, encode bug) must not take the
                        // sweeper down — the client simply never receives this frame
                        // and falls back to per-column revalidation (today's behavior).
                        long n = this.assembleFailWarn.recordAndTryAcquire(
                                System.nanoTime() / 1_000_000);
                        if (n > 0) {
                            LSSLogger.warn("Region summary assembly failed for "
                                    + job.dimension() + " (" + n
                                    + " failure(s) since the last report)", t);
                        }
                    }
                }
            } catch (InterruptedException e) {
                if (this.shutdown) return;
            }
        }
    }

    private void assemble(UUID player, SweepJob job) {
        long t0 = System.nanoTime();
        int r = job.tileRadius();
        int side = 2 * r + 1;
        long[] stamps = new long[side * side];
        long known = 0, neverClean = 0, noRegion = 0;
        int i = 0;
        for (int tz = job.centerTileZ() - r; tz <= job.centerTileZ() + r; tz++) {
            for (int tx = job.centerTileX() - r; tx <= job.centerTileX() + r; tx++) {
                long stamp = this.source.tileStampSeconds(job.dimension(), tx, tz);
                if (stamp == RegionSummaryWire.STAMP_NEVER_CLEAN) {
                    neverClean++;
                } else if (stamp == RegionSummaryWire.STAMP_NO_REGION) {
                    // Counted apart from known (P2 review H-m3): "everything is clean"
                    // and "we found no region files at all" must be distinguishable in
                    // diag — a whole-window no_region count is the resolver-gone-wrong
                    // signal (the table already degrades those shapes to NEVER_CLEAN;
                    // this disposition is genuine never-observed absence only).
                    noRegion++;
                } else {
                    known++;
                    // The shared serve-latency margin (the header rung's doctrine,
                    // RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS): reported stamps
                    // are MARGINED bounds, so the client's strict compare inherits
                    // the raced-read protection without a second constant.
                    stamp += RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS;
                }
                stamps[i++] = stamp;
            }
        }
        byte[] frame = RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                job.dimension(), job.centerTileX(), job.centerTileZ(), r, stamps));
        this.diag.recordTiles(known, neverClean, noRegion);
        this.diag.recordRefreshMillis((System.nanoTime() - t0) / 1_000_000);
        this.ready.add(new ReadyFrame(player, frame));
    }

    public void shutdown() {
        this.shutdown = true;
        synchronized (this.sweeperWake) {
            this.sweeperWake.notifyAll();
        }
        this.sweeper.interrupt();
        try {
            this.sweeper.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- test seams ----

    boolean hasPendingForTest(UUID player) {
        return this.pending.containsKey(player);
    }

    int readyCountForTest() {
        return this.ready.size();
    }
}
