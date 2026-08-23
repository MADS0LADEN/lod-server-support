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

    /** The platform's send hook, called on the TICK thread (drain). {@code SENT} =
     *  the frame left the wire — {@code summary.frames}/{@code summary.bytes} count
     *  only these, so the counters mean "put on the wire", not "assembled" (P2 review
     *  I-m7). {@code DROP} = permanently unsendable (the player vanished) — discard.
     *  {@code RETRY} = a transient refusal (the channel is momentarily unwritable —
     *  the F2 writability guard): the frame is RETAINED and re-tried next drain, up
     *  to {@link #FRAME_RETRY_TTL_NANOS}. Retention is load-bearing (live-diagnosed
     *  2026-08-20 on the rig: reqs=7/frames=5): the client's request is
     *  fire-and-forget — one per dimension entry, no retry — and the frame drains
     *  right at the join/portal moment, exactly when the serve flood makes the
     *  channel most likely to be unwritable, so a drop-on-unwritable loses the whole
     *  exchange for the session it matters most to. */
    public enum SendOutcome { SENT, RETRY, DROP }

    @FunctionalInterface
    public interface FrameSender {
        SendOutcome send(UUID player, byte[] frame);
    }

    /** How long a RETRY-refused frame is retained before it is dropped as stale —
     *  generous next to the ~seconds an unwritable join burst lasts, tiny next to
     *  the stamps' freshness margin, and the belt against a sender that answers
     *  RETRY forever for a player the vanish path somehow misses. */
    static final long FRAME_RETRY_TTL_NANOS = 10_000_000_000L;

    private record Pending(RegionSummaryWire.Request request, long expiresAtNanos) {}
    private record SweepJob(String dimension, int centerTileX, int centerTileZ, int tileRadius) {}
    private record ReadyFrame(byte[] frame, long expiresAtNanos) {}

    private final TileStampSource source;
    private final RegionSummaryDiagnostics diag = new RegionSummaryDiagnostics();
    private final LongSupplier nanoClock;
    /** The SERVER's live lodDistanceChunks — the admission window derives from it. */
    private final java.util.function.IntSupplier lodDistanceChunks;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SweepJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastSweepNanos = new ConcurrentHashMap<>();
    /** Stamps eligibility (plan §9.4): players that sent a summary request this session. */
    private final java.util.Set<UUID> requestedThisSession =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Ready frames, PER-PLAYER FIFO (final-panel MAJOR, 2026-08-20): the client's
     *  apply has no recency guard — its correctness argument is that a stale frame
     *  can only be corrected by a later fresh one, never applied after it. A single
     *  shared queue broke that under RETRY (the retained frame re-added at the TAIL
     *  could send AFTER a fresher frame for the same player, re-validating positions
     *  the fresh frame's revocation just cleared). Per-player queues keep each
     *  player's frames in assembly order unconditionally: RETRY leaves the frame at
     *  its queue's HEAD and stops only THAT player's drain (no head-of-line blocking
     *  across players). Entries are swept at {@link #removePlayer}; an empty queue
     *  left behind by a drain is retained until then (removing it would race the
     *  sweeper's computeIfAbsent-add and orphan a frame). The sweeper's own post-add
     *  liveness re-check in {@link #assemble} covers the one ordering removePlayer
     *  cannot: a quit landing while its frame is still assembling. */
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ReadyFrame>> ready =
            new ConcurrentHashMap<>();
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
        // LAZY start on the first request (final review F3): a config-disabled server
        // and every throwaway test wiring never pay an idle daemon, and the thread can
        // only observe a fully-constructed instance (no ctor this-escape).
    }

    private final java.util.concurrent.atomic.AtomicBoolean sweeperStarted =
            new java.util.concurrent.atomic.AtomicBoolean();

    public RegionSummaryDiagnostics diagnostics() {
        return this.diag;
    }

    /**
     * Ingress (any thread): latest-wins per player. Decode/config gating is the
     * CALLER's job (the handler-checked kill switch); this only stores pure data.
     */
    public void offerRequest(UUID player, RegionSummaryWire.Request request) {
        if (this.sweeperStarted.compareAndSet(false, true) && !this.shutdown) {
            this.sweeper.start();
        }
        this.diag.recordRequest();
        this.pending.put(player, new Pending(request, this.nanoClock.getAsLong() + PENDING_TTL_NANOS));
        // The stamps-eligibility mark (stamped-up-to-date-plan.md §9.4): the summary
        // request IS the capability declaration — a session that requested this
        // server life receives stamped-up_to_date frames. Survives dimension change
        // (matching the per-dimension request re-fire), swept at NETWORK disconnect.
        // Marked AFTER the pending slot (panel fold 2026-08-22): the pump's anchor-less
        // sweep requires no-pending before stripping a mark, so a sweep iteration
        // straddling these two writes must never see the mark without its protecting
        // slot — mark-first let a mid-registration straddle strip stamps eligibility
        // for the player's whole dimension visit (the client requests only at entry).
        this.requestedThisSession.add(player);
    }

    /** True once this session sent a summary request — the stamped-up_to_date send
     *  gate (any-thread safe: concurrent set). */
    public boolean hasRequestedThisSession(UUID player) {
        return this.requestedThisSession.contains(player);
    }

    /** NETWORK-disconnect sweep (never the dimension-change remove+register cycle —
     *  that would race the client's at-entry request): pending/queued work and the
     *  cooldown mark die with the connection. The TTL/vanished-send belts keep a
     *  missed call harmless except for the cooldown entry, which THIS is the sweep for. */
    public void removePlayer(UUID player) {
        this.pending.remove(player);
        this.jobs.remove(player);
        this.lastSweepNanos.remove(player);
        this.requestedThisSession.remove(player);
        this.ready.remove(player); // retained frames die with the connection
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
            // Clamp the WINDOW EDGES, not just the center (final panel): the emitted
            // span is [c - radius, c + radius], so the center's bound is
            // maxTiles - radius — a crafted off-center max-radius request must not
            // reach ~2x as far as an honest one (the class-javadoc invariant).
            int centerBound = maxTiles - radius;
            int cx = Math.clamp(p.request().centerTileX(),
                    playerTileX - centerBound, playerTileX + centerBound);
            int cz = Math.clamp(p.request().centerTileZ(),
                    playerTileZ - centerBound, playerTileZ + centerBound);
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
        // Anchor-less eligibility sweep (final panel — the Folia quit race): on Folia
        // a region-thread offerRequest can land AFTER the tick thread's removePlayer,
        // resurrecting requestedThisSession with no other sweep — the mark (and any
        // orphaned ready queue) would persist for the server's life. A marked UUID
        // with no anchor AND no pending/queued work is either quit (sweep correct) or
        // mid-portal (dimension briefly null — safe: the client re-requests AT entry,
        // which re-arms the mark before it matters); a mid-registration request is
        // protected by its pending entry.
        for (UUID marked : this.requestedThisSession) {
            if (anchors.apply(marked) == null && !this.pending.containsKey(marked)
                    && !this.jobs.containsKey(marked)) {
                this.requestedThisSession.remove(marked);
                this.lastSweepNanos.remove(marked);
                this.ready.remove(marked);
            }
        }
        for (var entry : this.ready.entrySet()) {
            UUID player = entry.getKey();
            var queue = entry.getValue();
            ReadyFrame frame;
            while ((frame = queue.peek()) != null) {
                if (now - frame.expiresAtNanos() >= 0) { // retried past the TTL — stale
                    queue.poll();
                    continue;
                }
                SendOutcome outcome;
                try {
                    outcome = sender.send(player, frame.frame());
                } catch (Throwable t) {
                    // A torn-down connection can throw between the sender's own
                    // player-null check and the send — treat as the vanished belt
                    // (DROP) rather than losing every later frame in the drain.
                    outcome = SendOutcome.DROP;
                }
                if (outcome == SendOutcome.SENT) {
                    queue.poll();
                    this.diag.recordFrameSent(frame.frame().length);
                } else if (outcome == SendOutcome.RETRY) {
                    // Transient refusal (unwritable channel): the frame stays at the
                    // HEAD of this player's queue — assembly order preserved (the
                    // client-side stale-then-fresh invariant) — and only THIS
                    // player's drain stops; other players' channels are independent.
                    break;
                } else {
                    queue.poll(); // DROP: the player vanished — discard.
                }
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
        this.ready.computeIfAbsent(player, k -> new ConcurrentLinkedQueue<>())
                .add(new ReadyFrame(frame, this.nanoClock.getAsLong() + FRAME_RETRY_TTL_NANOS));
        // Post-add liveness re-check (panel fold 2026-08-22): a removePlayer landing
        // between the pump's jobs hand-off and the add above resurrects the ready
        // queue for a departed UUID — the vanished-send belt would drop the frame,
        // but the EMPTY queue would then leak for the server's life (the anchor-less
        // sweep iterates requestedThisSession, which no longer holds the UUID).
        // Re-checking AFTER the add closes both orderings: a removePlayer that
        // completed before this check left the mark gone (swept here); one that runs
        // after the add sweeps the queue itself. Residual: a same-UUID rejoin whose
        // re-request lands inside this assembly's window keeps the frame — it is at
        // most assembly-old, dimension-bound at the client, and per-player FIFO keeps
        // it ahead of the new session's fresher frame (the stale-then-fresh order the
        // client apply requires).
        if (!this.requestedThisSession.contains(player)) {
            this.ready.remove(player);
        }
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

    boolean hasReadyEntryForTest(UUID player) {
        return this.ready.containsKey(player);
    }

    int readyCountForTest() {
        int n = 0;
        for (var q : this.ready.values()) {
            n += q.size();
        }
        return n;
    }
}
