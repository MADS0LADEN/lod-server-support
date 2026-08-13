package dev.vox.lss.common.processing;

import dev.vox.lss.common.SharedBandwidthLimiter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The send pacer's pins (send-pacing-plan.md v2 — the refill-floored proportional
 * drain): budget truth table, presence gate, retention, limiter composition incl. the
 * pingf-cut case, RAW denomination, the paced= attribution rule, and the kill-switch/
 * short-overload OFF pins (S-9a). Rig follows {@link TransportYieldFlushTest}: real
 * states, real bandwidth trackers (token accrual via wall-clock sleep), a permissive
 * writable probe so the yield gate never interferes.
 */
class SendPacingFlushTest {

    private static final long POS_1 = 11L;

    private static final class TestState extends AbstractPlayerRequestState<String> {
        TestState() { super(UUID.randomUUID(), 1, 1); }
        @Override public String getPlayerName() { return "pace-test"; }
    }

    private static ChannelPressureProbe writableProbe() {
        return new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return 0; }
            @Override public Snapshot snapshot() {
                return new Snapshot(0, 65536, Writability.WRITABLE);
            }
        };
    }

    private final SharedBandwidthLimiter limiter = new SharedBandwidthLimiter(1L << 40);
    private final TickDiagnostics diag = new TickDiagnostics();
    private final List<String> sent = new ArrayList<>();
    private final TestState state = new TestState();

    /** Queue {@code count} payloads of {@code rawBytes} raw / {@code wireBytes} wire. */
    private void queue(int count, int rawBytes, int wireBytes) {
        for (int i = 0; i < count; i++) {
            state.addReadyPayload(new QueuedPayload<>("p" + i, rawBytes, wireBytes,
                    POS_1 + i));
        }
    }

    /** Full-overload flush with pacing armed and the yield gate off. */
    private void flushPaced(long allocation) {
        state.flushSendQueue(allocation, limiter, diag, sent::add, 0L, false, 0, true);
    }

    /** Fill the per-player bank (allocation/4) — the token bucket refills on real
     *  elapsed time, capped at one second of credit. */
    private static void fillBank() throws Exception {
        Thread.sleep(300);
    }

    @Test
    void refillFloorBindsWhenTheQueueIsSmall() throws Exception {
        // Q = 10 x 30 KB = 300 KB; alloc 2 MB/s -> share 100 KB > Q/10 = 30 KB. The
        // floor admits sends until 100 KB is written (payloads 1-4: 120 KB >= budget
        // stops #5) — never fewer than the cap's own per-tick rate.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 30_000, 30_000);
        fillBank();
        flushPaced(2_000_000);
        assertEquals(4, sent.size(), "the refill floor admits ~one share per tick");
        assertEquals(6, state.getSendQueueSize(), "leftover is retained, never dropped");
        assertEquals(1, state.getPacedTicks(), "a budget-stopped PARTIAL tick books paced=");
    }

    @Test
    void proportionalTermBindsWhenTheQueueIsLarge() throws Exception {
        // Q = 100 x 30 KB = 3 MB; alloc 2 MB/s -> Q/10 = 300 KB > share 100 KB. The
        // backlog drains ABOVE the floor (the bank-as-slope half): 10 payloads ship
        // before the 300 KB budget stops #11.
        state.setChannelPressureProbe(writableProbe());
        queue(100, 30_000, 30_000);
        fillBank();
        flushPaced(2_000_000);
        assertEquals(10, sent.size(), "a big backlog drains at Q/HORIZON, above the floor");
        assertEquals(1, state.getPacedTicks());
    }

    @Test
    void oversizedPayloadShipsWholeThroughThePresenceGate() throws Exception {
        // One 5 MB payload vs a 500 KB budget: the budget never vetoes the FIRST send,
        // and an emptied queue is not a paced stop.
        state.setChannelPressureProbe(writableProbe());
        queue(1, 5_000_000, 5_000_000);
        fillBank();
        flushPaced(2_000_000);
        assertEquals(1, sent.size(), "a legal oversized payload ships whole");
        assertEquals(0, state.getPacedTicks(), "an emptied queue books nothing");
    }

    @Test
    void limiterStaysTheAuthorityUnderAPingfCutAllocation() throws Exception {
        // The delta-round property: a pingf-cut allocation (200 KB/s) with a big
        // backlog makes Q/10 (300 KB) exceed the cut share (10 KB) — the budget is
        // merely NON-BINDING; canSend's bank (cut/4 = 50 KB) stops the loop first,
        // and paced= honestly books nothing (limiter-stopped, not budget-stopped).
        state.setChannelPressureProbe(writableProbe());
        queue(100, 30_000, 30_000);
        fillBank();
        flushPaced(200_000);
        assertEquals(2, sent.size(), "the cut bank (50 KB) admits two 30 KB payloads");
        assertEquals(0, state.getPacedTicks(),
                "a limiter-stopped tick must not book paced=");
    }

    @Test
    void budgetIsRawDenominated() throws Exception {
        // Raw 30 KB / wire 2 B payloads at alloc 400 KB/s: the raw-denominated budget
        // (max(20 KB, 30 KB) = 30 KB) vetoes after payload #1. A wire-denominated
        // budget would read paceWritten=2 B and never veto (the limiter would then
        // admit 4) — so exactly-one-sent pins the denomination.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 30_000, 2);
        fillBank();
        flushPaced(400_000);
        assertEquals(1, sent.size(), "the budget counts RAW bytes");
        assertEquals(1, state.getPacedTicks());
    }

    @Test
    void killSwitchOffRestoresTheUnpacedFlush() throws Exception {
        // The 7-arg overload (pacing false): the same queue ships limiter-bound only —
        // the bank (500 KB) admits 5 x 100 KB, and paced= never moves.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 100_000, 100_000);
        fillBank();
        state.flushSendQueue(2_000_000, limiter, diag, sent::add, 0L, false, 0);
        assertEquals(5, sent.size(), "unpaced: the bank dumps five shares in one tick");
        assertEquals(0, state.getPacedTicks(), "pacing off books nothing");
    }

    @Test
    void shortOverloadsPinPacingOff() throws Exception {
        // S-9a: only the fullest overload can arm pacing, and only the platform
        // services call it with live config.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 100_000, 100_000);
        fillBank();
        state.flushSendQueue(2_000_000, limiter, diag, sent::add);
        assertEquals(5, sent.size(), "the 4-arg overload never paces");
        assertEquals(0, state.getPacedTicks());
    }

    @Test
    void starvationFloorTickIsExemptFromTheBudget() throws Exception {
        // A yield-floor tick ships exactly one payload by its own contract; the pace
        // budget must neither veto it nor book it.
        state.setChannelPressureProbe(new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return 500_000; }
            @Override public Snapshot snapshot() {
                return new Snapshot(500_000, 65536, Writability.NOT_WRITABLE);
            }
        });
        queue(3, 100_000, 100_000);
        fillBank();
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            state.flushSendQueue(2_000_000, limiter, diag, sent::add, 0L, true, 0, true);
        }
        assertTrue(sent.isEmpty(), "yielding until the floor");
        state.flushSendQueue(2_000_000, limiter, diag, sent::add, 0L, true, 0, true);
        assertEquals(1, sent.size(), "the floor tick ships its one payload");
        assertEquals(0, state.getPacedTicks(), "the floor tick is structurally exempt");
    }
}
