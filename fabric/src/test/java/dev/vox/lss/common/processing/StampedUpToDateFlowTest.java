package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.region.ColumnStampsWire;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the stamped-up_to_date server flow (stamped-up-to-date-plan.md §3/§9).
 *
 * <p>SITE pins (the §9.1 narrowing, as re-narrowed by the 3-Opus fold to TWO rungs):
 * only the router's tscache rung and the header-fresh delivery consult the stamp
 * source and produce stamped actions — the header-fresh site live here, the tscache
 * rung via the tscache round-trip case (a store-hit delivery stamps the tscache, the
 * re-declare draws the rung's up_to_date + a frame), each labeled with the RESULT's
 * dimension. The store-stamp conversion is pinned NEVER-STAMP (its own doctrine:
 * delivery honesty, never a fabricated fresh stamp). The never-stamp 3-arg form ships
 * an ordinary up_to_date and no frame — the done-bit rung and every cannot-improve
 * flavor construct it. The site CENSUS (exactly two 5-arg construction sites, in the
 * two named methods) is enforced by {@link #stampedSiteCensusHoldsTheNarrowing}.
 *
 * <p>DRAIN pins (§3/§9.5): stamped actions accumulate per (player, dimension), flush
 * as ColumnStampsWire frames through the sink ONLY for eligible players, SPLIT at the
 * 1024 wire cap (never truncated), while the batch response carries the up_to_date
 * itself unchanged on its own lane.
 *
 * <p>PREDICATE pins (§9.2): the platform predicate's two guards are pinned at their
 * own seams — {@code DirtyColumnTracker.isPending} (marked-but-undrained) here;
 * {@code RegionStampTable.isClaimSuppressed} (the latch) in RegionStampTableTest.
 */
class StampedUpToDateFlowTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;
    private static final long NOW = System.currentTimeMillis() / 1000L;
    private static final long POLL_DEADLINE_NANOS = 30_000_000_000L;

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) {
            super(uuid, 4, 4);
            setFrontierDampingForTest(0, System::nanoTime);
        }
        @Override public String getPlayerName() { return "stamps-test"; }
        void enqueue(IncomingRequest r) {
            offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r}));
        }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader reader) {
            super(players, reader, false, null, 1, 0);
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz,
                                         long order, long clientTimestamp) {
            return true;
        }
        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz,
                                                       String dimension, long columnTimestamp,
                                                       long submissionOrder, ColumnBytes bytes,
                                                       int estimatedBytes, byte source) {
            return true;
        }
    }

    private record SentFrame(UUID player, byte[] frame, int entries) {}

    private static final class Rig {
        final UUID uuid = UUID.randomUUID();
        final ConcurrentHashMap<UUID, TestState> players = new ConcurrentHashMap<>();
        final StubDiskReader reader = new StubDiskReader();
        final TestState state;
        final TestProcessor proc;
        final List<SentFrame> frames = new ArrayList<>();
        final List<Long> responses = new ArrayList<>();
        volatile boolean eligible = true;

        Rig() {
            this.state = new TestState(this.uuid);
            this.state.markHandshakeComplete();
            this.state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            this.players.put(this.uuid, this.state);
            this.reader.registerPlayer(this.uuid);
            this.proc = new TestProcessor(this.players, this.reader);
        }

        OffThreadProcessor.StampsSink<TestState> sink() {
            return new OffThreadProcessor.StampsSink<>() {
                @Override public boolean eligible(UUID u) { return Rig.this.eligible; }
                @Override public void send(TestState s, byte[] frame, int entries) {
                    Rig.this.frames.add(new SentFrame(s.getPlayerUUID(), frame, entries));
                }
            };
        }

        void drainOnce() {
            this.proc.drainSendActions((s, types, positions, count) -> {
                for (int i = 0; i < count; i++) this.responses.add(positions[i]);
            }, sink());
        }

        void inject(ChunkReadResult result) {
            this.reader.getPlayerQueue(result.playerUuid()).add(result);
        }

        void postSnapshot() {
            var dims = new HashMap<UUID, String>();
            for (var u : this.players.keySet()) dims.put(u, DIM);
            this.proc.postSnapshot(new TickSnapshot(dims, Map.of(), 0, false), List.of());
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    private static void drainUntil(Rig rig, Predicate<Rig> done) throws InterruptedException {
        long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
        while (!done.test(rig)) {
            if (System.nanoTime() > deadline) {
                fail("timed out draining; responses=" + rig.responses + " frames=" + rig.frames.size());
            }
            rig.drainOnce();
            Thread.sleep(10);
        }
    }

    // ---- drain pins (enqueue seam — no pipeline needed) ----

    private SendAction.ColumnUpToDate stamped(Rig rig, int cx, int cz, long second) {
        return new SendAction.ColumnUpToDate(rig.uuid,
                PositionUtil.packPosition(cx, cz), rig.state, second, DIM);
    }

    @Test
    void stampedActionFlushesOneDecodableFrameBesideTheBatchResponse() {
        var rig = new Rig();
        try {
            rig.proc.enqueueSendActionForTest(stamped(rig, 3, -4, NOW));
            rig.drainOnce();
            assertEquals(1, rig.responses.size(), "the up_to_date itself still ships");
            assertEquals(1, rig.frames.size());
            var decoded = ColumnStampsWire.decode(rig.frames.get(0).frame(), NOW + 10);
            assertEquals(DIM, decoded.dimension());
            assertArrayEquals(new long[]{PositionUtil.packPosition(3, -4)},
                    decoded.packedPositions());
            assertArrayEquals(new long[]{NOW}, decoded.stampSeconds());
            assertEquals(1, rig.frames.get(0).entries());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void ineligiblePlayerGetsTheUpToDateButNoFrame() {
        var rig = new Rig();
        try {
            rig.eligible = false;
            rig.proc.enqueueSendActionForTest(stamped(rig, 3, -4, NOW));
            rig.drainOnce();
            assertEquals(1, rig.responses.size(), "eligibility gates the FRAME, never the answer");
            assertEquals(0, rig.frames.size());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void neverStampFormProducesNoFrame() {
        var rig = new Rig();
        try {
            // The 3-arg form — the done-bit rung and every cannot-improve flavor.
            rig.proc.enqueueSendActionForTest(new SendAction.ColumnUpToDate(
                    rig.uuid, PositionUtil.packPosition(5, 5), rig.state));
            rig.drainOnce();
            assertEquals(1, rig.responses.size());
            assertEquals(0, rig.frames.size());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void overCapAccumulationSplitsIntoFramesWithoutTruncation() {
        var rig = new Rig();
        try {
            int total = ColumnStampsWire.MAX_STAMP_ENTRIES + 400;
            for (int i = 0; i < total; i++) {
                rig.proc.enqueueSendActionForTest(stamped(rig, i % 64, i / 64, NOW + (i % 5)));
            }
            rig.drainOnce();
            assertEquals(2, rig.frames.size(), "split at the wire cap, never truncated");
            int carried = 0;
            for (var f : rig.frames) {
                var d = ColumnStampsWire.decode(f.frame(), NOW + 100);
                carried += d.packedPositions().length;
            }
            assertEquals(total, carried, "every entry must survive the split");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void dimensionsAreNeverMixedInOneFrame() {
        var rig = new Rig();
        try {
            rig.proc.enqueueSendActionForTest(stamped(rig, 1, 1, NOW));
            rig.proc.enqueueSendActionForTest(new SendAction.ColumnUpToDate(rig.uuid,
                    PositionUtil.packPosition(1, 1), rig.state, NOW, "minecraft:the_end"));
            rig.drainOnce();
            assertEquals(2, rig.frames.size(), "one frame per (player, dimension)");
            var dims = new java.util.HashSet<String>();
            for (var f : rig.frames) dims.add(ColumnStampsWire.decode(f.frame(), NOW + 10).dimension());
            assertEquals(java.util.Set.of(DIM, "minecraft:the_end"), dims);
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void legacyDrainOverloadStillWorksWithoutASink() {
        var rig = new Rig();
        try {
            rig.proc.enqueueSendActionForTest(stamped(rig, 2, 2, NOW));
            var got = new ArrayList<Long>();
            assertDoesNotThrow(() -> rig.proc.drainSendActions((s, types, positions, count) -> {
                for (int i = 0; i < count; i++) got.add(positions[i]);
            }));
            assertEquals(1, got.size());
        } finally {
            rig.proc.shutdown();
        }
    }

    // ---- site pins (live pipeline: the compare-backed rungs consult the source) ----

    @Test
    void headerFreshDeliveryStampsWithTheSourceSecond() throws Exception {
        var rig = new Rig();
        try {
            rig.proc.setUpToDateStampSource((player, dim, packed) -> {
                assertEquals(DIM, dim, "the predicate sees the RESULT's dimension");
                assertEquals(rig.uuid, player, "the predicate sees the recipient (the "
                        + "platform short-circuits on stamps eligibility first)");
                return NOW + 7;
            });
            rig.proc.start();
            rig.state.enqueue(new IncomingRequest(7, 7, NOW - 50));
            rig.postSnapshot();
            waitFor(() -> rig.state.hasPendingRequest(7, 7), "pending admission");
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 7, 7, DIM, 1L, NOW - 100));
            rig.postSnapshot();
            drainUntil(rig, r -> !r.frames.isEmpty());
            var d = ColumnStampsWire.decode(rig.frames.get(0).frame(), NOW + 100);
            assertArrayEquals(new long[]{NOW + 7}, d.stampSeconds());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void defaultNeverSourceShipsUpToDateWithNoFrames() throws Exception {
        var rig = new Rig();
        try {
            // No setUpToDateStampSource call — the NEVER default (every test/harness
            // wiring): bit-identical unstamped behavior.
            rig.proc.start();
            rig.state.enqueue(new IncomingRequest(7, 7, NOW - 50));
            rig.postSnapshot();
            waitFor(() -> rig.state.hasPendingRequest(7, 7), "pending admission");
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 7, 7, DIM, 1L, NOW - 100));
            rig.postSnapshot();
            drainUntil(rig, r -> !r.responses.isEmpty());
            rig.drainOnce();
            assertEquals(0, rig.frames.size());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void predicateRefusalShipsUpToDateWithNoFrame() throws Exception {
        var rig = new Rig();
        try {
            rig.proc.setUpToDateStampSource((player, dim, packed) -> -1L); // pending mark / latch
            rig.proc.start();
            rig.state.enqueue(new IncomingRequest(7, 7, NOW - 50));
            rig.postSnapshot();
            waitFor(() -> rig.state.hasPendingRequest(7, 7), "pending admission");
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 7, 7, DIM, 1L, NOW - 100));
            rig.postSnapshot();
            drainUntil(rig, r -> !r.responses.isEmpty());
            rig.drainOnce();
            assertEquals(0, rig.frames.size(), "a refused stamp is an ordinary up_to_date");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void tscacheRungStampsOnTheReDeclare() throws Exception {
        var rig = new Rig();
        try {
            rig.proc.setUpToDateStampSource((player, dim, packed) -> NOW + 3);
            rig.proc.start();
            // Serve once via a store hit (stamps the tscache at NOW-50)...
            rig.state.enqueue(new IncomingRequest(4, 4, -1L));
            rig.postSnapshot();
            waitFor(() -> rig.state.hasPendingRequest(4, 4), "pending admission");
            rig.inject(new ChunkReadResult(rig.uuid, 4, 4, new byte[]{1, 2, 3, 4}, DIM,
                    64, NOW - 50, false, false, false, true, 1L, 0L));
            rig.postSnapshot();
            waitFor(() -> !rig.state.hasPendingRequest(4, 4), "delivery");
            // The delivery set the done-bit, and the done-bit rung answers a ts>0
            // re-declare BEFORE the tscache rung (and never stamps, by design). The
            // live heal happens on REJOIN — the done-bit dies with the session while
            // the tscache survives — so model that by clearing it through the
            // processor's own mailbox route.
            rig.proc.clearDiskReadDone(rig.uuid, new long[]{PositionUtil.packPosition(4, 4)});
            rig.postSnapshot();
            waitFor(() -> !rig.state.hasDiskReadDone(4, 4), "done-bit clear applied");
            // ...then a ts>0 re-declare at the served stamp draws the ROUTER's tscache
            // rung (cachedTs <= clientTs): up_to_date + a stamped frame. This is the
            // rung that produces essentially all of the live heal.
            rig.state.enqueue(new IncomingRequest(4, 4, NOW - 50));
            rig.postSnapshot();
            drainUntil(rig, r -> !r.frames.isEmpty());
            var d = ColumnStampsWire.decode(rig.frames.get(0).frame(), NOW + 100);
            assertEquals(DIM, d.dimension());
            assertArrayEquals(new long[]{PositionUtil.packPosition(4, 4)}, d.packedPositions());
            assertArrayEquals(new long[]{NOW + 3}, d.stampSeconds());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void storeStampConversionNeverStamps() throws Exception {
        // The 3-Opus re-narrowing: a store hit converting to up_to_date (stored stamp
        // <= the recipient's stamp) must ship NO frame — a re-serve would hand the
        // STORED acquisition second, so a "verified now" stamp here would claim
        // strictly more than the bytes the rung declined to send.
        var rig = new Rig();
        try {
            rig.proc.setUpToDateStampSource((player, dim, packed) -> NOW + 3);
            rig.proc.start();
            rig.state.enqueue(new IncomingRequest(6, 6, NOW - 10));
            rig.postSnapshot();
            waitFor(() -> rig.state.hasPendingRequest(6, 6), "pending admission");
            rig.inject(new ChunkReadResult(rig.uuid, 6, 6, new byte[]{1, 2, 3, 4}, DIM,
                    64, NOW - 50, false, false, false, true, 1L, 0L)); // fromStore, stored <= client
            rig.postSnapshot();
            drainUntil(rig, r -> !r.responses.isEmpty());
            rig.drainOnce();
            assertEquals(0, rig.frames.size(), "the store rung is never-stamp by doctrine");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void stampedSiteCensusHoldsTheNarrowing() throws Exception {
        // The §9.1 narrowing's enforcement (3-Opus fold: "the least-pinned thing in
        // the change"): exactly TWO 5-arg ColumnUpToDate construction sites may exist
        // in the processing package, and only in the two compare-backed methods. A
        // third stamped site (or one moved elsewhere) must red HERE, not in review.
        var dir = java.nio.file.Path.of("../common/src/main/java/dev/vox/lss/common/processing");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Path.of("common/src/main/java/dev/vox/lss/common/processing");
        }
        var pattern = java.util.regex.Pattern.compile(
                "new SendAction\\.ColumnUpToDate\\([^;]*?stampSource\\(\\)", java.util.regex.Pattern.DOTALL);
        int stamped = 0;
        StringBuilder where = new StringBuilder();
        try (var files = java.nio.file.Files.list(dir)) {
            for (var f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = java.nio.file.Files.readString(f);
                var m = pattern.matcher(src);
                while (m.find()) {
                    stamped++;
                    where.append(f.getFileName()).append(' ');
                }
            }
        }
        assertEquals(2, stamped, "exactly the two compare-backed rungs may stamp "
                + "(router tscache + header-fresh delivery); found in: " + where);
    }

    @Test
    void stampsFrameFlushesBeforeTheBatchResponse() {
        // Load-bearing order (3-Opus fold): FIFO delivery applies the ratchet BEFORE
        // the same tick's onUpToDate consumes retry/dirty marks, keeping the client's
        // mark-skip armor live for the same-tick pair. A well-meaning "flush stamps
        // last" refactor must red here.
        var rig = new Rig();
        try {
            var order = new ArrayList<String>();
            rig.proc.enqueueSendActionForTest(stamped(rig, 3, -4, NOW));
            rig.proc.drainSendActions((s, types, positions, count) -> order.add("batch"),
                    new OffThreadProcessor.StampsSink<>() {
                        @Override public boolean eligible(UUID u) { return true; }
                        @Override public void send(TestState s, byte[] frame, int entries) {
                            order.add("stamps");
                        }
                    });
            assertEquals(List.of("stamps", "batch"), order);
        } finally {
            rig.proc.shutdown();
        }
    }

    // ---- predicate pin: the dirty-tracker half (§9.2) ----

    @Test
    void theEventBlindStateStampsByAcceptedDesign() {
        // Plan §9.3, pinned as DELIBERATE (3-Opus fold's middle option — the
        // paper-store-unfired-event canary is structurally impossible, its soak
        // client is summary-gated off): a content change that fires NO dirty mark
        // (Paper's unfired-event class) is invisible to BOTH predicate guards — no
        // pending mark, no armed latch — so the platform predicate STAMPS. This is
        // the accepted residual: the resulting seal holds until the chunk's next
        // save advances the header past the stamp; the store resweep bounds the
        // store-rung arm. If this test starts failing because a guard now catches
        // the shape, update plan §9.3 — the residual shrank.
        var tracker = new DirtyColumnTracker();
        long packed = PositionUtil.packPosition(3, 3);
        // The platform composition (both service lambdas): eligibility short-circuit
        // is modeled true; then isPending, then the latch.
        assertFalse(tracker.isPending(DIM, packed), "no mark — the event never fired");
        // No RegionStampTable entry either (no mark ever bumped it) — the latch half
        // answers false for an unexamined region (pinned in RegionStampTableTest).
        // Composed outcome: the predicate returns a positive second — the seal.
        long second = tracker.isPending(DIM, packed) ? -1L : System.currentTimeMillis() / 1000L;
        assertTrue(second > 0, "the event-blind state stamps — the ACCEPTED residual");
    }

    @Test
    void trackerIsPendingSpansMarkToInvalidationApply() {
        // TWO-PHASE (the 3-Opus fold's honesty MAJOR): the drain hands positions to a
        // mailbox invalidation applied LATER on the processing thread — isPending must
        // stay true across that gap or stamps issued inside it seal permanently at any
        // dirtyBroadcastIntervalSeconds > 15.
        var tracker = new DirtyColumnTracker();
        long packed = PositionUtil.packPosition(9, -2);
        assertFalse(tracker.isPending(DIM, packed));
        tracker.markDirty(DIM, 9, -2);
        assertTrue(tracker.isPending(DIM, packed),
                "phase one: marked-but-undrained refuses to stamp");
        long[] drained = tracker.drainDirty(DIM);
        assertTrue(tracker.isPending(DIM, packed),
                "phase two: drained-but-invalidation-not-applied STILL refuses");
        tracker.confirmInvalidated(DIM, drained);
        assertFalse(tracker.isPending(DIM, packed),
                "the applied invalidation releases the guard — the evidence is gone");
    }
}
