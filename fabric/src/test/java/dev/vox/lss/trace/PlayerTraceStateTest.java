package dev.vox.lss.trace;

import dev.vox.lss.common.processing.ChannelPressureProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arm-window + probe-rebind pins (reviews C-8 / A-10). */
class PlayerTraceStateTest {

    @Test
    void eventArmWindowIsThirtySecondsHalfOpen() {
        var state = new PlayerTraceState();
        assertFalse(state.eventArmed(1_000), "fresh state is unarmed");
        state.armFromEvent(1_000);
        assertTrue(state.eventArmed(1_000));
        assertTrue(state.eventArmed(1_000 + PlayerTraceState.EVENT_ARM_MILLIS - 1));
        assertFalse(state.eventArmed(1_000 + PlayerTraceState.EVENT_ARM_MILLIS),
                "the 30 s window is half-open — armedUntil itself is disarmed");
    }

    @Test
    void probeFollowsItsOwner() {
        var state = new PlayerTraceState();
        ChannelPressureProbe first = () -> 1L;
        Object ownerA = new Object();
        state.setProbe(first, ownerA);
        assertSame(first, state.probe());
        assertSame(ownerA, state.probeOwner());
        // Respawn replaces the ServerPlayer instance (review A-10): the registry rebinds
        // by comparing owner identity — pin the identity accessor pair it relies on.
        ChannelPressureProbe second = () -> 2L;
        Object ownerB = new Object();
        state.setProbe(second, ownerB);
        assertSame(second, state.probe());
        assertSame(ownerB, state.probeOwner());
    }
}
