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
 * <p>SITE pins (the §9.1 narrowing): ONLY the compare-backed rungs consult the stamp
 * source and produce stamped actions — the delivery-path header-fresh and store-stamp
 * conversions here (the router's tscache rung shares the same 5-arg construction,
 * pinned via the tscache round-trip case), each labeled with the RESULT's dimension.
 * The never-stamp 3-arg form ships an ordinary up_to_date and no frame — the done-bit
 * rung and every cannot-improve flavor construct it, so a frame for them cannot exist
 * by type.
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
            rig.proc.setUpToDateStampSource((dim, packed) -> {
                assertEquals(DIM, dim, "the predicate sees the RESULT's dimension");
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
            rig.proc.setUpToDateStampSource((dim, packed) -> -1L); // pending mark / latch
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

    // ---- predicate pin: the dirty-tracker half (§9.2) ----

    @Test
    void trackerIsPendingTracksTheMarkToDrainWindow() {
        var tracker = new DirtyColumnTracker();
        long packed = PositionUtil.packPosition(9, -2);
        assertFalse(tracker.isPending(DIM, packed));
        tracker.markDirty(DIM, 9, -2);
        assertTrue(tracker.isPending(DIM, packed),
                "marked-but-undrained is exactly the refuse-to-stamp window");
        tracker.drainDirty(DIM);
        assertFalse(tracker.isPending(DIM, packed), "the drain closes the window");
    }
}
