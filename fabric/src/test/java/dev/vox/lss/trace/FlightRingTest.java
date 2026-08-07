package dev.vox.lss.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wrap-around pins for the 5 Hz ring (review C-3): a mis-ordered flush silently
 * mis-retro-dates every chunk-send time in the §1.6 REFUTE evidence, and nothing
 * downstream can detect it.
 */
class FlightRingTest {

    @Test
    void oldestFirstAcrossTheWrapBoundary() {
        var ring = new FlightRing();
        // 41 adds into a 40-slot ring: sample 0 is overwritten; iteration starts at 1.
        for (int i = 0; i <= FlightRing.CAPACITY; i++) {
            ring.add(1_000 + i, i, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0);
        }
        assertEquals(FlightRing.CAPACITY, ring.size());
        var seen = new ArrayList<Long>();
        ring.forEachOldestFirst((wallMs, x, y, z, speed, obuf, gapMs, hasSendState,
                                 anchorCx, anchorCz, mask, loaderCx, loaderCz) -> seen.add(wallMs));
        assertEquals(FlightRing.CAPACITY, seen.size());
        assertEquals(1_001L, seen.get(0), "the overwritten sample 0 must be gone");
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) > seen.get(i - 1), "samples must be strictly oldest-first: " + seen);
        }
        assertEquals(1_000L + FlightRing.CAPACITY, seen.get(seen.size() - 1));
    }

    @Test
    void partialFillIteratesFromZero() {
        var ring = new FlightRing();
        ring.add(5, 1, 2, 3, 4, 5, 6, true, 7, 8, 9, 10, 11);
        ring.addNoSendState(6, 1, 2, 3, 4, 5, 6);
        assertEquals(2, ring.size());
        var sendFlags = new ArrayList<Boolean>();
        ring.forEachOldestFirst((wallMs, x, y, z, speed, obuf, gapMs, hasSendState,
                                 anchorCx, anchorCz, mask, loaderCx, loaderCz) -> sendFlags.add(hasSendState));
        assertEquals(2, sendFlags.size());
        assertTrue(sendFlags.get(0), "the full-add sample carries send state");
        assertFalse(sendFlags.get(1), "addNoSendState must flag its sample send-state-less");
    }

    @Test
    void clearResetsBothCursors() {
        var ring = new FlightRing();
        for (int i = 0; i < 50; i++) {
            ring.add(i, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0);
        }
        ring.clear();
        assertEquals(0, ring.size());
        ring.add(99, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0);
        var seen = new ArrayList<Long>();
        ring.forEachOldestFirst((wallMs, x, y, z, speed, obuf, gapMs, hasSendState,
                                 anchorCx, anchorCz, mask, loaderCx, loaderCz) -> seen.add(wallMs));
        assertEquals(1, seen.size());
        assertEquals(99L, seen.get(0), "post-clear iteration must not resurrect old samples");
    }
}
