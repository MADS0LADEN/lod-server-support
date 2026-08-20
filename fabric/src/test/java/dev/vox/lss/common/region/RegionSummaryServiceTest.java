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

        Rig(RegionSummaryService.TileStampSource source) {
            this.service = new RegionSummaryService(source, this.clock::get);
        }

        Function<UUID, RegionSummaryService.PlayerAnchor> anchorsAt(UUID player, String dim,
                                                                     int cx, int cz) {
            return u -> u.equals(player) ? new RegionSummaryService.PlayerAnchor(dim, cx, cz) : null;
        }

        void pump(Function<UUID, RegionSummaryService.PlayerAnchor> anchors) {
            this.service.pump(anchors, (p, f) -> this.sent.add(new Sent(p, f)));
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
