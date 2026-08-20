package dev.vox.lss.common.region;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the region-summary server core (region-summary-sync-plan.md §5): latest-wins
 * request coalescing, dimension-matched admission with the raced-registration TTL
 * retention, the center clamp (+ its range_filtered count), sweeper assembly →
 * ready-frame drain on the pump, per-job failure containment, and disconnect sweep.
 */
class RegionSummaryServiceTest {

    private static final String DIM = "minecraft:overworld";
    private static final long NOW = System.currentTimeMillis() / 1000L;
    private static final long POLL_DEADLINE_NANOS = 30_000_000_000L;

    private record Sent(UUID player, byte[] frame) {}

    private static final class Rig {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final ConcurrentLinkedQueue<Sent> sent = new ConcurrentLinkedQueue<>();
        final RegionSummaryService service;
        volatile RegionSummaryService.SendOutcome senderOutcome =
                RegionSummaryService.SendOutcome.SENT;

        Rig(RegionSummaryService.TileStampSource source) {
            // Max distance: the server window equals the protocol max, so wire-cap
            // pins stay meaningful. Clamp tests use the explicit-distance ctor.
            this(source, 2048);
        }

        Rig(RegionSummaryService.TileStampSource source, int lodDistanceChunks) {
            this.service = new RegionSummaryService(source, () -> lodDistanceChunks,
                    this.clock::get);
        }

        Function<UUID, RegionSummaryService.PlayerAnchor> anchorsAt(UUID player, String dim,
                                                                     int cx, int cz) {
            return u -> u.equals(player) ? new RegionSummaryService.PlayerAnchor(dim, cx, cz) : null;
        }

        void pump(Function<UUID, RegionSummaryService.PlayerAnchor> anchors) {
            this.service.pump(anchors, (p, f) -> {
                var out = this.senderOutcome;
                if (out == RegionSummaryService.SendOutcome.SENT) this.sent.add(new Sent(p, f));
                return out;
            });
        }

        Sent pumpUntilFrame(Function<UUID, RegionSummaryService.PlayerAnchor> anchors)
                throws InterruptedException {
            long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
            while (true) {
                pump(anchors);
                var s = this.sent.poll();
                if (s != null) return s;
                if (System.nanoTime() > deadline) fail("timed out waiting for a summary frame");
                Thread.sleep(10);
            }
        }
    }

    @Test
    void admittedRequestProducesADecodableWindow() throws Exception {
        // Stamp formula keyed on tile coords so the row-major order is pinned too.
        var rig = new Rig((dim, tx, tz) -> NOW - 1000 + tx * 10L + tz);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 2, 3, 1));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 2 << 5, 3 << 5));
            assertEquals(player, sent.player());
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(DIM, summary.dimension());
            assertEquals(2, summary.centerTileX());
            assertEquals(3, summary.centerTileZ());
            assertEquals(1, summary.tileRadius());
            // Row-major, x fastest within z rows: first entry is (tx=1, tz=2). Real
            // stamps carry the shared serve-latency margin (the header rung doctrine).
            long margin = RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS;
            assertEquals(NOW - 1000 + 10 + 2 + margin, summary.stampSeconds()[0]);
            assertEquals(NOW - 1000 + 30 + 4 + margin, summary.stampSeconds()[8]);
            assertEquals(9, rig.service.diagnostics().getTilesKnown());
            assertEquals(1, rig.service.diagnostics().getFrames());
            assertEquals(sent.frame().length, rig.service.diagnostics().getBytes());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void latestRequestWinsBeforeAdmission() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 2));
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 5, 5, 1));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 5 << 5, 5 << 5));
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(5, summary.centerTileX());
            assertEquals(1, summary.tileRadius());
            assertNull(rig.sent.poll(), "exactly one frame — the first request was coalesced");
            assertEquals(2, rig.service.diagnostics().getRequests());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void hostileCenterClampsToThePlayerWindowAndCounts() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player,
                    new RegionSummaryWire.Request(DIM, 1_000_000, -1_000_000, 0));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 0, 0));
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS, summary.centerTileX());
            assertEquals(-RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS, summary.centerTileZ());
            assertEquals(1, rig.service.diagnostics().getRangeFiltered());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void dimensionMismatchRetainsUntilTtlThenDrops() {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            // The player is still registered under the OLD dimension: retained.
            rig.pump(rig.anchorsAt(player, "minecraft:the_end", 0, 0));
            assertTrue(rig.service.hasPendingForTest(player), "raced request must be retained");
            // Past the TTL the stale request drops instead of lingering forever.
            rig.clock.addAndGet(RegionSummaryService.PENDING_TTL_NANOS + 1);
            rig.pump(rig.anchorsAt(player, "minecraft:the_end", 0, 0));
            assertFalse(rig.service.hasPendingForTest(player), "expired request must drop");
            assertNull(rig.sent.poll());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void unknownPlayerRetainsThenExpires() {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.pump(u -> null); // not registered yet
            assertTrue(rig.service.hasPendingForTest(player));
            rig.clock.addAndGet(RegionSummaryService.PENDING_TTL_NANOS + 1);
            rig.pump(u -> null);
            assertFalse(rig.service.hasPendingForTest(player));
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void removePlayerSweepsPendingWork() {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.service.removePlayer(player);
            assertFalse(rig.service.hasPendingForTest(player));
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void radiusAndCenterClampToTheServerWindowNotTheProtocolMax() throws Exception {
        // The I-M1 amplification fix: lodDistance 64 -> ceil(64/32)+1 = 3 tiles. A
        // radius-65 protocol-max request must shrink to the server's own window, and
        // the hostile center clamps against the SAME bound.
        var rig = new Rig((dim, tx, tz) -> NOW, 64);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(
                    DIM, 1_000_000, -1_000_000, RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 0, 0));
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(3, summary.tileRadius(), "radius clamped to the server window");
            assertEquals(3, summary.centerTileX(), "center clamped to playerTile + window");
            assertEquals(-3, summary.centerTileZ());
            assertEquals(49, summary.stampSeconds().length, "(2*3+1)^2 tiles, not 131^2");
            assertEquals(1, rig.service.diagnostics().getRangeFiltered());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void resweepCooldownRetainsThenAdmits() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            var anchors = rig.anchorsAt(player, DIM, 0, 0);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pumpUntilFrame(anchors);
            // A second request inside the cooldown is RETAINED (not dropped, not swept):
            // the stamp table's memos cannot have changed inside its stat horizon.
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(anchors);
            assertTrue(rig.service.hasPendingForTest(player),
                    "cooldown-held request must be retained");
            assertNull(rig.sent.poll(), "no second frame inside the cooldown");
            // Past the cooldown the retained request admits without a re-send.
            rig.clock.addAndGet(RegionSummaryService.RESWEEP_COOLDOWN_NANOS + 1);
            var sent = rig.pumpUntilFrame(anchors);
            assertEquals(player, sent.player());
            assertEquals(2, rig.service.diagnostics().getFrames());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void removePlayerClearsTheCooldownMark() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            var anchors = rig.anchorsAt(player, DIM, 0, 0);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pumpUntilFrame(anchors);
            // Disconnect sweeps the cooldown: a same-UUID rejoin's at-entry request
            // must not inherit the dead session's rate mark.
            rig.service.removePlayer(player);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            var sent = rig.pumpUntilFrame(anchors);
            assertEquals(player, sent.player(), "post-disconnect request admits immediately");
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void aDeclinedSendIsNotCountedAsAFrame() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.DROP;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0));
            assertEquals(0, rig.service.readyCountForTest(), "the frame was drained");
            assertEquals(0, rig.service.diagnostics().getFrames(),
                    "frames/bytes mean PUT ON THE WIRE — a vanished-player drop is uncounted");
            assertEquals(0, rig.service.diagnostics().getBytes());
        } finally {
            rig.service.shutdown();
        }
    }

    /** The retention half of the F2 writability guard (live-diagnosed 2026-08-20:
     *  rig reqs=7/frames=5 — frames drained at the join flood's unwritable instant
     *  were being discarded, and the fire-and-forget client never re-asks): a RETRY
     *  outcome must RETAIN the frame, uncounted, and the next writable drain must
     *  deliver it. */
    @Test
    void aRetriedFrameIsRetainedAndSentByTheNextWritableDrain() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.RETRY;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // unwritable drain
            assertEquals(1, rig.service.readyCountForTest(),
                    "a RETRY frame must be retained, not discarded");
            assertEquals(0, rig.service.diagnostics().getFrames(), "retention is uncounted");
            rig.senderOutcome = RegionSummaryService.SendOutcome.SENT;
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // writable again
            var s = rig.sent.poll();
            assertNotNull(s, "the retained frame must go out on the next writable drain");
            assertEquals(player, s.player());
            assertEquals(0, rig.service.readyCountForTest());
            assertEquals(1, rig.service.diagnostics().getFrames());
        } finally {
            rig.service.shutdown();
        }
    }

    /** The retention belt: a frame that stays RETRY past FRAME_RETRY_TTL_NANOS is
     *  dropped as stale (the sender is not even consulted for it) — a permanently
     *  unwritable channel must not accumulate frames forever. */
    @Test
    void aRetriedFrameExpiresAtTheTtl() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.RETRY;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // unwritable — retained
            assertEquals(1, rig.service.readyCountForTest());
            rig.clock.addAndGet(RegionSummaryService.FRAME_RETRY_TTL_NANOS + 1);
            rig.senderOutcome = RegionSummaryService.SendOutcome.SENT; // channel healed too late
            rig.pump(rig.anchorsAt(player, DIM, 0, 0));
            assertEquals(0, rig.service.readyCountForTest(), "the expired frame is gone");
            assertEquals(0, rig.service.diagnostics().getFrames(),
                    "an expired frame is never sent");
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void eligibilityMarkArmsOnRequestAndSweepsAtDisconnect() {
        // Stamped up_to_date (stamped-up-to-date-plan.md §9.4): the summary request IS
        // the stamps-eligibility declaration; the mark survives admission (unlike the
        // pending mailbox) and dies ONLY at the network-disconnect sweep.
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            assertFalse(rig.service.hasRequestedThisSession(player));
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            assertTrue(rig.service.hasRequestedThisSession(player), "armed by the request");
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admission consumes the mailbox...
            assertTrue(rig.service.hasRequestedThisSession(player), "...but never the mark");
            rig.service.removePlayer(player);
            assertFalse(rig.service.hasRequestedThisSession(player), "swept at disconnect");
        } finally {
            rig.service.shutdown();
        }
    }

    private static void awaitAssembly(Rig rig) throws InterruptedException {
        long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
        while (rig.service.readyCountForTest() == 0) {
            if (System.nanoTime() > deadline) fail("timed out waiting for assembly");
            Thread.sleep(10);
        }
    }

    @Test
    void noRegionTilesCountApartFromKnown() throws Exception {
        // H-m3: "everything is clean" and "no region files at all" must be
        // distinguishable — a whole-window no_region count is the resolver signal.
        var rig = new Rig((dim, tx, tz) -> tx == 0 && tz == 0
                ? NOW : RegionSummaryWire.STAMP_NO_REGION);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 0, 0));
            assertEquals(1, rig.service.diagnostics().getTilesKnown());
            assertEquals(8, rig.service.diagnostics().getTilesNoRegion());
            assertEquals(0, rig.service.diagnostics().getTilesNeverClean());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void assemblyFailureIsContainedAndLaterJobsStillServe() throws Exception {
        var rig = new Rig((dim, tx, tz) -> {
            if ("minecraft:the_end".equals(dim)) throw new IllegalStateException("boom");
            return NOW;
        });
        try {
            var bad = UUID.randomUUID();
            rig.service.offerRequest(bad, new RegionSummaryWire.Request("minecraft:the_end", 0, 0, 1));
            rig.pump(rig.anchorsAt(bad, "minecraft:the_end", 0, 0));
            // Give the sweeper a moment to consume (and contain) the failing job.
            Thread.sleep(50);
            var good = UUID.randomUUID();
            rig.service.offerRequest(good, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(good, DIM, 0, 0));
            assertEquals(good, sent.player(), "the sweeper survived the failing job");
        } finally {
            rig.service.shutdown();
        }
    }
}
