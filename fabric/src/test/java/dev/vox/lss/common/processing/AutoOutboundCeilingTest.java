package dev.vox.lss.common.processing;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AUTO outbound ceiling suite (auto-outbound-ceiling-design.md v2): the per-player
 * drain-rate estimator, the derived latency ceiling with its DISARM threshold, the
 * in-loop write budget (the v3 lever) with the one-payload presence gate, the AUTO
 * floor with the round-2 reset rules, and the two CI-inertness paths. On the
 * {@code TransportYieldFlushTest} template.
 */
class AutoOutboundCeilingTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;
    private static final long POS_1 = PositionUtil.packPosition(10, 0);
    private static final long POS_2 = PositionUtil.packPosition(11, 0);
    private static final long POS_3 = PositionUtil.packPosition(12, 0);

    private static final class TestState extends AbstractPlayerRequestState<String> {
        TestState() { super(UUID.randomUUID(), 1, 1); }
        @Override public String getPlayerName() { return "auto-ceil-test"; }
    }

    private static ChannelPressureProbe probe(long pending, ChannelPressureProbe.Writability w) {
        return new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return pending; }
            @Override public Snapshot snapshot() {
                return new Snapshot(pending, 65536, w);
            }
        };
    }

    private final SharedBandwidthLimiter limiter = new SharedBandwidthLimiter(BIG_ALLOCATION);
    private final TickDiagnostics diag = new TickDiagnostics();
    private final List<String> sent = new ArrayList<>();
    private final TestState state = new TestState();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private long[] autoFlush() {
        return autoFlush(BIG_ALLOCATION);
    }

    private long[] autoFlush(long allocation) {
        return state.flushSendQueue(allocation, limiter, diag, sent::add,
                0L, true, false, 0);
    }

    private void setPending(long pending) {
        state.setChannelPressureProbe(probe(pending, ChannelPressureProbe.Writability.WRITABLE));
    }

    private void tickClock() {
        clock.addAndGet(50_000_000L); // one 50 ms tick
    }

    /** Train the estimator to ~500 KB/s (the 4 Mbps live-session shape): a run of
     *  pure-drain ticks at 25 KB/50 ms. The windowed MEDIAN (round 4) needs
     *  RING_MIN samples before it arms. Derived ceiling = 250 ms x 500 KB/s =
     *  125 KB. */
    private void trainTo500KBps() {
        state.setCeilClockForTest(clock::get);
        long pending = 700_000;
        setPending(pending);
        autoFlush(); // first read: sets pending_prev, no sample yet
        for (int i = 0; i <= AbstractPlayerRequestState.AUTO_CEILING_RING_MIN; i++) {
            tickClock();
            pending -= 25_000; // 25 KB drained per 50 ms tick = 500 KB/s
            setPending(pending);
            autoFlush();
        }
        assertEquals(125_000, state.getAutoCeilingGauge(),
                "premise: a median of 500 KB/s samples derives the 125 KB ceiling");
    }

    // ---- CI-inertness (both paths — the design's structural soak/gametest argument) ----

    @Test
    void untrainedAutoIsCompletelyInert() throws Exception {
        // Path (a): loopback pending reads 0 at every probe → the busy-period guard
        // never passes → the estimator never trains → no ceiling, no budget, no holds.
        state.setCeilClockForTest(clock::get);
        setPending(0);
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 10, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>("c", 10, 2, POS_3));
        Thread.sleep(50);
        for (int i = 0; i < 3; i++) { autoFlush(); tickClock(); }
        assertEquals(List.of("a", "b", "c"), sent, "untrained AUTO drains like no ceiling");
        assertEquals(-1, state.getAutoCeilingGauge(), "gauge renders off");
        assertEquals(0, state.getSendDeferrals());
    }

    @Test
    void fastLinkDisarmsAboveTheThreshold() throws Exception {
        // Path (b): a stray trained sample on a fast drain computes a ceiling at/past
        // 2 MB → AUTO stands down entirely (the round-2 DISARM decision: a CLAMPED
        // ceiling would silently govern fast clients under a raised bandwidth cap).
        state.setCeilClockForTest(clock::get);
        long pending = 20_000_000;
        setPending(pending);
        autoFlush();
        for (int i = 0; i <= AbstractPlayerRequestState.AUTO_CEILING_RING_MIN; i++) {
            tickClock();
            pending -= 1_000_000; // 1 MB per 50 ms tick = 20 MB/s
            setPending(pending);
            autoFlush();
        }
        assertEquals(-1, state.getAutoCeilingGauge(),
                "a fast link disarms AUTO — the bandwidth cap governs there");

        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 10, 1, POS_2));
        Thread.sleep(50);
        autoFlush();
        assertEquals(List.of("a", "b"), sent, "disarmed AUTO imposes no budget");
    }

    // ---- the estimator ----

    @Test
    void slowLinkTrainsTheLiveSessionShapeAndGaugePublishes() {
        trainTo500KBps(); // asserts the 125 KB derivation
    }

    @Test
    void negativeSamplesAndNoSignalReadsAreSkipped() {
        state.setCeilClockForTest(clock::get);
        // Negative sample: another writer (vanilla burst) grew the queue between reads.
        setPending(50_000);
        autoFlush();
        tickClock();
        setPending(400_000); // grew: drained = 50k − 400k < 0 → skipped
        autoFlush();
        assertEquals(-1, state.getAutoCeilingGauge(), "a negative sample must not train");

        // No-signal read poisons the CURRENT and the NEXT sample.
        tickClock();
        state.setChannelPressureProbe(probe(-1, ChannelPressureProbe.Writability.UNKNOWN));
        autoFlush(); // pending_prev becomes -1
        tickClock();
        setPending(100_000);
        autoFlush(); // prev == -1 → no sample (the poisoned NEXT)
        assertEquals(-1, state.getAutoCeilingGauge(),
                "the read after a no-signal read must not train either");
        // …but sampling resumes after that: a full training run arms normally.
        trainTo500KBps();
    }

    // ---- the actuator (the v3 lever) ----

    @Test
    void wholeTickHoldAtOrOverTheCeilingCountsDeferredAndRetains() throws Exception {
        trainTo500KBps();
        tickClock();
        setPending(200_000); // over the 125 KB ceiling → budget 0
        state.addReadyPayload(new QueuedPayload<>("a", 30_000, 0, POS_1));
        Thread.sleep(50);
        long[] dropped = autoFlush();
        assertTrue(sent.isEmpty(), "budget 0 withholds the whole flush");
        assertEquals(0, dropped.length, "retention, never drops");
        assertEquals(1, state.getSendQueueSize());
        assertEquals(1, state.getSendDeferrals(),
                "a whole-tick AUTO hold books deferred= (the round-2 attribution rule)");
        assertEquals(0, state.getYieldedTicks(), "the channel was writable — never yielded=");
    }

    @Test
    void inLoopBudgetStopsTheFlushMidDrainAndRetainsTheRest() throws Exception {
        trainTo500KBps();
        tickClock();
        setPending(75_000); // budget = 125k − 75k = 50 KB
        state.addReadyPayload(new QueuedPayload<>("a", 30_000, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 30_000, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>("c", 30_000, 2, POS_3));
        Thread.sleep(50);
        autoFlush();
        assertEquals(List.of("a", "b"), sent,
                "the loop stops once written wire bytes reach the budget (30k < 50k "
                        + "admits the second send; 60k >= 50k stops the third) — the "
                        + "banked-token burst can never ride through the one open tick");
        assertEquals(1, state.getSendQueueSize(), "the rest is retained, not dropped");
        assertEquals(0, state.getSendDeferrals(),
                "a budget-stopped PARTIAL flush is not a whole-tick hold (round-2 rule)");
    }

    @Test
    void presenceGateShipsExactlyOneOversizedPayload() throws Exception {
        trainTo500KBps();
        tickClock();
        setPending(0); // budget = full 125 KB ceiling
        state.addReadyPayload(new QueuedPayload<>("big", 500_000, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("next", 500_000, 1, POS_2));
        Thread.sleep(50);
        autoFlush();
        assertEquals(List.of("big"), sent,
                "an oversized payload ships WHOLE past the budget (the presence gate — "
                        + "a legal worst-case column must never wedge) and the next waits");
        assertEquals(1, state.getSendQueueSize());
    }

    // ---- the AUTO floor + round-2 reset rules ----

    @Test
    void hundredConsecutiveHoldsShipExactlyOneFloorPayload() throws Exception {
        trainTo500KBps();
        state.addReadyPayload(new QueuedPayload<>("floor", 30_000, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("rest", 30_000, 1, POS_2));
        Thread.sleep(50);
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            tickClock();
            setPending(200_000); // over-ceiling; 0-drain samples keep it trained/armed
            autoFlush();
        }
        assertTrue(sent.isEmpty(), "99 held ticks ship nothing");
        tickClock();
        setPending(200_000);
        autoFlush(); // the 100th consecutive hold fires the floor
        assertEquals(List.of("floor"), sent,
                "the AUTO floor ships EXACTLY ONE payload after 100 consecutive holds — "
                        + "an estimator collapsed on a dying link degrades LOD to the "
                        + "floor rate, never to silence");
        assertEquals(1, state.getSendQueueSize(), "one payload only — the hold resumes");
    }

    @Test
    void sendSuccessResetsTheFloorCounterButARefusedTickDoesNot() throws Exception {
        trainTo500KBps();
        state.addReadyPayload(new QueuedPayload<>("a", 10_000, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 10_000, 1, POS_2));
        Thread.sleep(50);
        // 50 holds, then one successful send tick: the counter must restart.
        for (int i = 0; i < 50; i++) { tickClock(); setPending(200_000); autoFlush(); }
        tickClock();
        setPending(0); // budget opens
        autoFlush();
        assertFalse(sent.isEmpty(), "premise: a payload left (send-success reset)");
        sent.clear();
        // Re-seed: the open tick drained the queue, and held ticks require queued work
        // (an empty queue resets the counters — the round-2 empty-queue rule).
        state.addReadyPayload(new QueuedPayload<>("c", 10_000, 2, POS_1));
        state.addReadyPayload(new QueuedPayload<>("d", 10_000, 3, POS_2));
        Thread.sleep(50);
        // 99 more holds: NO floor (a fresh 99, not 149 — the reset took).
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            tickClock();
            setPending(200_000);
            autoFlush();
        }
        assertTrue(sent.isEmpty(),
                "99 holds after a successful send must not fire the floor — send-success "
                        + "reset the counter");
        // A REFUSED tick (budget open, zero allocation → nothing can send) resets
        // nothing: the very next hold is the 100th and fires.
        tickClock();
        setPending(0);
        autoFlush(0L); // zero allocation: canSend refuses, no payload leaves
        assertTrue(sent.isEmpty(), "premise: the refused tick sent nothing");
        tickClock();
        setPending(600_000); // negative sample (skipped), budget 0 → the 100th hold
        autoFlush();
        assertEquals(1, sent.size(),
                "a refused/zero-allocation tick must NOT reset the floor counter "
                        + "(the round-1 unbounded-starvation interleave)");
    }

    // ---- ordering + mode isolation ----

    @Test
    void unwritableTickBooksYieldedNotDeferredEvenWithAutoArmed() throws Exception {
        trainTo500KBps();
        tickClock();
        state.setChannelPressureProbe(
                probe(200_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 30_000, 0, POS_1));
        Thread.sleep(50);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, 0L, true, true, 0);
        assertTrue(sent.isEmpty());
        assertEquals(1, state.getYieldedTicks(),
                "the yield gate evaluates FIRST — an unwritable tick is yielded=, "
                        + "never deferred= (the design's ordering decision)");
        assertEquals(0, state.getSendDeferrals());
    }

    @Test
    void writtenIntervalsNeverSampleTheAsyncLagRegression() throws Exception {
        // Round-3 amendment (found LIVE on the rig): netty writes are ASYNC, so a
        // burst tick's written bytes may not be in the pending gauge yet — the old
        // written-inclusive sample read a phantom multi-MB/s drain (measured: EWMA
        // 6 MB/s on a 500 KB/s link, ceil=1.5 MB, the ceiling never bound). An
        // interval with ANY written bytes must not sample, however drain-shaped its
        // arithmetic looks.
        trainTo500KBps(); // ceil 125 KB from pure-drain samples
        tickClock();
        setPending(75_000); // budget = 50 KB; this tick's sample enters the ring but
                            // cannot move a median of a full 500 KB/s window
        state.addReadyPayload(new QueuedPayload<>("burst", 100_000, 0, POS_1));
        Thread.sleep(50);
        autoFlush(); // probe first (gauge settles), THEN writes 100 KB
        assertEquals(List.of("burst"), sent, "premise: the burst left (presence gate)");
        long gaugeAfterWrite = state.getAutoCeilingGauge();
        tickClock();
        setPending(0); // the phantom shape: prev 75K + written 100K − now 0 would
                       // have read 3.5 MB/s under the old written-inclusive sample
        autoFlush();
        assertEquals(gaugeAfterWrite, state.getAutoCeilingGauge(),
                "a written interval must NOT train — the async hand-off makes its "
                        + "arithmetic read phantom drain (3.5 MB/s here, 28 MB/s live)");
    }

    @Test
    void fastStreakClearsTheRingForRetrain() throws Exception {
        // Pure-drain sampling cannot observe an IMPROVED link (a converged flush
        // writes every tick) — the round-4 up-recovery: 40 consecutive intervals of
        // "wrote >= 32 KB, gauge still ~empty" CLEAR the sample ring. A genuinely
        // improved link retrains at its true rate (or stays inert if it never queues
        // again — correct); a false streak retrains right back within a second of
        // holds. Visibility lag cannot sustain a 2 s streak of empty reads.
        trainTo500KBps(); // median ~500 KB/s, ceil ~125 KB
        for (int i = 0; i <= AbstractPlayerRequestState.AUTO_CEILING_UP_STREAK_TICKS + 1; i++) {
            tickClock();
            setPending(0); // gauge empty every read
            state.addReadyPayload(new QueuedPayload<>("w" + i, 40_000, 100 + i, POS_1));
            Thread.sleep(1);
            autoFlush(); // writes 40 KB; next probe sees pending 0 → streak++
        }
        assertEquals(-1, state.getAutoCeilingGauge(),
                "sustained wrote-and-vanished evidence clears the ring — the stale "
                        + "slow-link ceiling is gone and the estimator retrains from "
                        + "scratch instead of clipping an improved link");
    }

    @Test
    void composedYieldPlusAutoSawtoothBoundsTheOpenTickBurst() throws Exception {
        // The SHIPPED default regime (post-hoc review MINOR-2: yield ON + AUTO): armed
        // ceilings sit at/above netty's 64 KiB high water, so holds book yielded= (the
        // yield gate runs first) and deferred= stays 0 — the budget's work is visible
        // ONLY as the bounded open-tick burst. This walks one full sawtooth period:
        // unwritable holds (sampling), the writable flip, and the budget-stopped open
        // tick that would have shipped the whole banked queue pre-feature.
        trainTo500KBps();
        // Deep queue: five 30 KB payloads ready (150 KB > the 125 KB ceiling).
        for (int i = 0; i < 5; i++) {
            state.addReadyPayload(new QueuedPayload<>("p" + i, 30_000, i, POS_1));
        }
        Thread.sleep(50);
        // Unwritable stretch: yield holds, deferred stays 0, samples keep flowing.
        for (int i = 0; i < 3; i++) {
            tickClock();
            state.setChannelPressureProbe(
                    probe(90_000 - i * 25_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
            state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, 0L, true, true, 0);
        }
        assertTrue(sent.isEmpty(), "unwritable ticks hold everything");
        assertEquals(3, state.getYieldedTicks(), "holds book yielded= in the default regime");
        assertEquals(0, state.getSendDeferrals(), "deferred= stays 0 — the healthy signature");

        // The writable flip: budget = ceiling − pending bounds the open tick to ~three
        // payloads (30k+30k+30k >= 110k budget stops the fourth) instead of the whole
        // queue — the banked-token burst amplitude is the ceiling, not the bank.
        tickClock();
        state.setChannelPressureProbe(
                probe(15_000, ChannelPressureProbe.Writability.WRITABLE));
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, 0L, true, true, 0);
        assertEquals(List.of("p0", "p1", "p2", "p3"), sent,
                "the open tick ships only up to the budget (110 KB admits four 30 KB "
                        + "payloads via the stop-check-before-send rule) — never the "
                        + "whole banked queue");
        assertEquals(1, state.getSendQueueSize(), "the tail is retained for the next cycle");
    }

    @Test
    void nonAutoOverloadsNeverTrainOrGate() throws Exception {
        // The S-9a defaults pin, extended: the 7-arg overload (auto=false) must keep 0
        // meaning "no ceiling at all" — busy probe reads must not train anything.
        state.setCeilClockForTest(clock::get);
        setPending(100_000);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, 0L, false, 0);
        tickClock();
        setPending(50_000);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, 0L, false, 0);
        assertEquals(-1, state.getAutoCeilingGauge(),
                "non-AUTO overloads never run the estimator (S-9a: only the platform "
                        + "services arm mechanisms, with live config)");

        state.addReadyPayload(new QueuedPayload<>("a", 30_000, 0, POS_1));
        Thread.sleep(50);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, 0L, false, 0);
        assertEquals(List.of("a"), sent, "and impose no budget");
    }
}
