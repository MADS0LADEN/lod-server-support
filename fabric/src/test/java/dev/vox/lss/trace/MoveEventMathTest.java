package dev.vox.lss.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-core pins for the tracer math (move-desync-tracer-plan.md §3). The packing pin is
 * the load-bearing one: the tracer queries MC-side chunk sets, whose key convention is
 * vanilla {@code ChunkPos.asLong} — TRANSPOSED from LSS's own {@code PositionUtil}.
 */
class MoveEventMathTest {

    @Test
    void mcChunkKeyUsesVanillaAsLongPackingNotPositionUtil() {
        // Vanilla: (z << 32) | (x & 0xFFFFFFFF).
        assertEquals(((long) 7 << 32) | 3L, MoveEventMath.mcChunkKey(3, 7));
        assertEquals(((long) -2 << 32) | (5L & 0xFFFFFFFFL), MoveEventMath.mcChunkKey(5, -2));
        // LSS PositionUtil packs (x << 32) | z — the two must NOT agree off the diagonal,
        // or every mask the tracer captures is silently transposed.
        assertTrue(MoveEventMath.mcChunkKey(3, 7) != (((long) 3 << 32) | 7L));
    }

    @Test
    void clampsReplicateVanillaMovementCheckBounds() {
        assertEquals(3.0E7, MoveEventMath.clampHorizontal(4.0E7));
        assertEquals(-3.0E7, MoveEventMath.clampHorizontal(-4.0E7));
        assertEquals(123.5, MoveEventMath.clampHorizontal(123.5));
        assertEquals(2.0E7, MoveEventMath.clampVertical(9.9E7));
        assertEquals(-2.0E7, MoveEventMath.clampVertical(-9.9E7));
    }

    @Test
    void chunkCoordFloors() {
        assertEquals(0, MoveEventMath.chunkCoord(0.5));
        assertEquals(0, MoveEventMath.chunkCoord(15.99));
        assertEquals(1, MoveEventMath.chunkCoord(16.0));
        assertEquals(-1, MoveEventMath.chunkCoord(-0.01));
        assertEquals(-2, MoveEventMath.chunkCoord(-16.01));
    }

    @Test
    void maskGeometryIsRowMajorCentered() {
        assertEquals(0, MoveEventMath.maskBit5x5(-2, -2));
        assertEquals(12, MoveEventMath.maskBit5x5(0, 0));
        assertEquals(24, MoveEventMath.maskBit5x5(2, 2));
        assertEquals(-1, MoveEventMath.maskBit5x5(3, 0));
        assertEquals(-1, MoveEventMath.maskBit5x5(0, -3));
        assertEquals(0, MoveEventMath.maskBit3x3(-1, -1));
        assertEquals(4, MoveEventMath.maskBit3x3(0, 0));
        assertEquals(8, MoveEventMath.maskBit3x3(1, 1));
        assertEquals(-1, MoveEventMath.maskBit3x3(2, 0));
    }

    @Test
    void maskContainsAnswersRetroDatingQueries() {
        int mask = (1 << MoveEventMath.maskBit5x5(0, 0)) | (1 << MoveEventMath.maskBit5x5(2, -1));
        assertTrue(MoveEventMath.maskContains5x5(mask, 10, 20, 10, 20));
        assertTrue(MoveEventMath.maskContains5x5(mask, 10, 20, 12, 19));
        assertFalse(MoveEventMath.maskContains5x5(mask, 10, 20, 11, 20));
        // Outside the ±2 window: never contained, regardless of bits.
        assertFalse(MoveEventMath.maskContains5x5(-1, 10, 20, 13, 20));
    }

    @Test
    void residualMath() {
        assertEquals(5.0, MoveEventMath.residual(3, 0, 4));
        assertEquals(5.0, MoveEventMath.residualHorizontal(3, 4));
        assertEquals(0.0, MoveEventMath.residual(0, 0, 0));
    }

    @Test
    void stopBlockSampleUsesDominantAxisBeyondTheFace() {
        // Horizontal-x dominant: sample beyond the +x face at mid-body height.
        double[] px = MoveEventMath.stopBlockSamplePoint(0, 64, 0, 2.0, 0.1, 0.5, 0.3, 1.8);
        assertArrayEquals(new double[] {0.35, 64.9, 0}, px, 1e-9);
        // Negative-z dominant.
        double[] pz = MoveEventMath.stopBlockSamplePoint(0, 64, 0, 0.1, 0.0, -3.0, 0.3, 1.8);
        assertArrayEquals(new double[] {0, 64.9, -0.35}, pz, 1e-9);
        // Vertical down: below the feet.
        double[] down = MoveEventMath.stopBlockSamplePoint(0, 64, 0, 0.0, -1.0, 0.0, 0.3, 1.8);
        assertArrayEquals(new double[] {0, 63.95, 0}, down, 1e-9);
        // Vertical up: above the head.
        double[] up = MoveEventMath.stopBlockSamplePoint(0, 64, 0, 0.0, 2.0, 0.1, 0.3, 1.8);
        assertArrayEquals(new double[] {0, 65.85, 0}, up, 1e-9);
    }

    @Test
    void gapClockRecordsGapsAndTrailingMax() {
        var clock = new GapClock();
        assertEquals(0, clock.record(1_000));
        assertEquals(50, clock.record(1_050));
        assertEquals(400, clock.record(1_450));
        assertEquals(400, clock.lastGapMs());
        // Trailing-window max sees the 400 gap.
        assertEquals(400, clock.maxGapWindowMs(1_500));
    }

    @Test
    void gapClockFoldsInTheCurrentlyOpenGap() {
        var clock = new GapClock();
        clock.record(1_000);
        clock.record(1_050);
        // Client went silent 3 s ago: the open gap IS the stall, no recorded gap shows it.
        assertEquals(3_000, clock.maxGapWindowMs(4_050));
    }

    @Test
    void gapClockExpiresGapsOlderThanTheWindow() {
        var clock = new GapClock();
        clock.record(1_000);
        clock.record(2_000);   // 1000 ms gap
        // Keep the clock live with tight packets so the big gap ages out of the buckets.
        for (long t = 2_050; t <= 9_000; t += 50) {
            clock.record(t);
        }
        long max = clock.maxGapWindowMs(9_000);
        assertTrue(max < 1000, "a 1000 ms gap from 7 s ago must have aged out, got " + max);
    }

    @Test
    void gapClockFirstPacketHasZeroGap() {
        var clock = new GapClock();
        assertEquals(0, clock.record(5_000));
        assertEquals(0, clock.lastGapMs());
    }
}
