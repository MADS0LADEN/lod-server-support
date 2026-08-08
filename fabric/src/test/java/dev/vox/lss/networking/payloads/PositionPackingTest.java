package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionPackingTest {

    @Test
    void positiveCoords() {
        long packed = PositionUtil.packPosition(100, 200);
        assertEquals(100, PositionUtil.unpackX(packed));
        assertEquals(200, PositionUtil.unpackZ(packed));
    }

    @Test
    void negativeCoords() {
        long packed = PositionUtil.packPosition(-50, -75);
        assertEquals(-50, PositionUtil.unpackX(packed));
        assertEquals(-75, PositionUtil.unpackZ(packed));
    }

    @Test
    void mixedSigns() {
        long packed = PositionUtil.packPosition(-10, 20);
        assertEquals(-10, PositionUtil.unpackX(packed));
        assertEquals(20, PositionUtil.unpackZ(packed));

        long packed2 = PositionUtil.packPosition(10, -20);
        assertEquals(10, PositionUtil.unpackX(packed2));
        assertEquals(-20, PositionUtil.unpackZ(packed2));
    }

    @Test
    void zeroCoords() {
        long packed = PositionUtil.packPosition(0, 0);
        assertEquals(0, PositionUtil.unpackX(packed));
        assertEquals(0, PositionUtil.unpackZ(packed));
    }

    @Test
    void extremeValues() {
        long packed = PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, PositionUtil.unpackX(packed));
        assertEquals(Integer.MIN_VALUE, PositionUtil.unpackZ(packed));

        long packed2 = PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, PositionUtil.unpackX(packed2));
        assertEquals(Integer.MAX_VALUE, PositionUtil.unpackZ(packed2));
    }

    @Test
    void distinctness() {
        long packed12 = PositionUtil.packPosition(1, 2);
        long packed21 = PositionUtil.packPosition(2, 1);
        assertNotEquals(packed12, packed21);
    }

    // ---- chebyshevDistance / isOutOfRange (the request distance gate) ----

    @Test
    void chebyshevDistanceBasics() {
        assertEquals(0, PositionUtil.chebyshevDistance(5, -5, 5, -5));
        assertEquals(4, PositionUtil.chebyshevDistance(0, 0, 3, -4));
        assertEquals(4, PositionUtil.chebyshevDistance(3, -4, 0, 0), "distance must be symmetric");
    }

    @Test
    void chebyshevDistanceExtremeCoordsClampInsteadOfOverflowing() {
        // int math would wrap: MIN_VALUE - MAX_VALUE == 1, and Math.abs(MIN_VALUE) stays negative
        assertEquals(Integer.MAX_VALUE,
                PositionUtil.chebyshevDistance(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
        assertEquals(Integer.MAX_VALUE,
                PositionUtil.chebyshevDistance(0, Integer.MIN_VALUE, 0, 0));
    }

    @Test
    void isOutOfRangeBoundaryIsInclusive() {
        assertFalse(PositionUtil.isOutOfRange(PositionUtil.packPosition(64, -64), 0, 0, 64),
                "exactly at the distance limit is still in range");
        assertTrue(PositionUtil.isOutOfRange(PositionUtil.packPosition(65, 0), 0, 0, 64));
        assertTrue(PositionUtil.isOutOfRange(PositionUtil.packPosition(0, -65), 0, 0, 64));
    }

    // ---- D0 tile-cache region/slot math (timestamp-cache-tile-redesign.md §3) ----

    @Test
    void regionAndSlotAgreeWithAFloorDivReferenceIncludingNegativesAndExtremes() {
        // The tile cache buckets by these two functions; a sign error on negatives
        // splits one region's columns across two tiles (silent stamp loss on eviction).
        int[] probes = {0, 1, 31, 32, -1, -31, -32, -33, 1000, -1000,
                Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE - 31, Integer.MIN_VALUE + 31};
        for (int cx : probes) {
            for (int cz : probes) {
                long packed = PositionUtil.packPosition(cx, cz);
                long region = PositionUtil.packRegionOf(packed);
                int slot = PositionUtil.tileSlotOf(packed);
                int wantRx = Math.floorDiv(cx, 32);
                int wantRz = Math.floorDiv(cz, 32);
                assertEquals(wantRx, (int) (region >> 32), cx + "," + cz + " region x");
                assertEquals(wantRz, (int) region, cx + "," + cz + " region z");
                int wantSlot = Math.floorMod(cx, 32) * 32 + Math.floorMod(cz, 32);
                assertEquals(wantSlot, slot, cx + "," + cz + " slot");
                assertTrue(slot >= 0 && slot < 1024, cx + "," + cz + " slot bounds");
            }
        }
    }

    @Test
    void everySlotOfARegionRoundTripsUniquely() {
        // 1024 distinct slots per region, and (region, slot) recovers the column —
        // the tile's whole addressing contract in one sweep, on a negative region.
        var seen = new java.util.HashSet<Integer>();
        for (int cx = -64; cx < -32; cx++) {
            for (int cz = 32; cz < 64; cz++) {
                long packed = PositionUtil.packPosition(cx, cz);
                assertEquals(PositionUtil.packRegionOf(PositionUtil.packPosition(-64, 32)),
                        PositionUtil.packRegionOf(packed), "same region");
                assertTrue(seen.add(PositionUtil.tileSlotOf(packed)), "unique slot");
            }
        }
        assertEquals(1024, seen.size());
    }

    @Test
    void hostileExtremeCoordinatesCannotSlipUnderTheGate() {
        // Overflowed int math reports these as near the player (negative or wrapped-small
        // distance) and the server would serve them. 2048 = MAX_LOD_DISTANCE.
        assertTrue(PositionUtil.isOutOfRange(
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MIN_VALUE), 0, 0, 2048));
        // Player near the world border, request wraps around: int distance would be 21
        assertTrue(PositionUtil.isOutOfRange(
                PositionUtil.packPosition(Integer.MIN_VALUE + 10, 0), Integer.MAX_VALUE - 10, 0, 2048));
    }
}
