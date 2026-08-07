package dev.vox.lss.trace;

/**
 * Pure math for the move-desync tracer — everything a Tier 1 test can drive without an
 * MC classloader (the hook bodies are a thin MC-typed capture layer over this core; see
 * move-desync-tracer-plan.md §3 "pure-core split").
 *
 * <p>Two packing conventions exist in this codebase and they differ: LSS's
 * {@code PositionUtil} packs {@code (x << 32) | (z & 0xFFFFFFFF)}, while vanilla's
 * {@code ChunkPos.asLong} — the convention of every MC-side chunk set the tracer queries
 * (Moonrise {@code sentChunks}, {@code PlayerChunkSender.isPending}) — packs
 * {@code (z << 32) | (x & 0xFFFFFFFF)}. The tracer only ever talks to MC-side sets, so
 * only {@link #mcChunkKey} exists here; using {@code PositionUtil} for these queries
 * would silently transpose every mask.
 */
public final class MoveEventMath {

    /** 25-bit and 9-bit mask geometry: row-major, dz outer, dx inner, centered. */
    public static final int MASK_5X5_RADIUS = 2;
    public static final int MASK_3X3_RADIUS = 1;

    /** Vanilla's movement-check clamps, replicated from
     *  {@code ServerGamePacketListenerImpl.clampHorizontal/clampVertical} (26.2). */
    public static final double CLAMP_HORIZONTAL = 3.0E7;
    public static final double CLAMP_VERTICAL = 2.0E7;

    private MoveEventMath() {}

    public static double clampHorizontal(double value) {
        return Math.clamp(value, -CLAMP_HORIZONTAL, CLAMP_HORIZONTAL);
    }

    public static double clampVertical(double value) {
        return Math.clamp(value, -CLAMP_VERTICAL, CLAMP_VERTICAL);
    }

    /** Vanilla {@code ChunkPos.asLong} packing — NOT {@code PositionUtil}'s (see class doc). */
    public static long mcChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    /** Vanilla's burst penalty, replicated exactly (review F-8): a raw delta above 5 is
     *  PENALIZED to 1 — the value the too-quickly threshold actually multiplied. */
    public static int usedDeltaPackets(int rawDelta) {
        return rawDelta > 5 ? 1 : rawDelta;
    }

    /** Block coordinate → chunk coordinate (floor divide by 16). */
    public static int chunkCoord(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 16);
    }

    /**
     * Bit index into the 5x5 membership mask for an offset from the anchor, or -1 when
     * outside. Row-major: {@code (dz + 2) * 5 + (dx + 2)} — bit 0 is (-2,-2), bit 12 the
     * anchor, bit 24 (+2,+2).
     */
    public static int maskBit5x5(int dx, int dz) {
        if (dx < -MASK_5X5_RADIUS || dx > MASK_5X5_RADIUS
                || dz < -MASK_5X5_RADIUS || dz > MASK_5X5_RADIUS) return -1;
        return (dz + MASK_5X5_RADIUS) * 5 + (dx + MASK_5X5_RADIUS);
    }

    /** Bit index into the 3x3 mask, or -1 when outside. Row-major like {@link #maskBit5x5}. */
    public static int maskBit3x3(int dx, int dz) {
        if (dx < -MASK_3X3_RADIUS || dx > MASK_3X3_RADIUS
                || dz < -MASK_3X3_RADIUS || dz > MASK_3X3_RADIUS) return -1;
        return (dz + MASK_3X3_RADIUS) * 3 + (dx + MASK_3X3_RADIUS);
    }

    /**
     * Is the chunk {@code (cx, cz)} inside a 5x5 mask anchored at {@code (anchorCx,
     * anchorCz)} set? Used by analysis and the validator fixtures; the capture side sets
     * bits via {@link #maskBit5x5}.
     */
    public static boolean maskContains5x5(int mask, int anchorCx, int anchorCz, int cx, int cz) {
        int bit = maskBit5x5(cx - anchorCx, cz - anchorCz);
        return bit >= 0 && (mask & (1 << bit)) != 0;
    }

    /** Euclidean length of the residual vector (claimed − simulated). */
    public static double residual(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Horizontal-only residual. */
    public static double residualHorizontal(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Where to sample the block that the server thinks stopped the player: one small step
     * beyond the player's bounding-box face in the dominant axis of the residual vector,
     * from the simulated stop. Returns {@code {x, y, z}} block-space doubles (the capture
     * layer floors them into a BlockPos). For a vertical dominant axis the sample sits
     * below the feet / above the head; horizontal, at mid-body height so slabs and
     * carpets at the feet don't shadow the wall that actually stopped the sweep.
     *
     * @param simX/simY/simZ  the simulated stop (entity position: feet center)
     * @param rx/ry/rz        the residual vector (claimed − simulated)
     * @param halfWidth       half the entity AABB width
     * @param height          entity AABB height
     */
    public static double[] stopBlockSamplePoint(double simX, double simY, double simZ,
                                                double rx, double ry, double rz,
                                                double halfWidth, double height) {
        double ax = Math.abs(rx);
        double ay = Math.abs(ry);
        double az = Math.abs(rz);
        final double step = 0.05;
        if (ay >= ax && ay >= az) {
            // Vertical: below the feet or above the head.
            double y = ry <= 0 ? simY - step : simY + height + step;
            return new double[] {simX, y, simZ};
        }
        double midY = simY + height * 0.5;
        if (ax >= az) {
            double x = simX + Math.copySign(halfWidth + step, rx);
            return new double[] {x, midY, simZ};
        }
        double z = simZ + Math.copySign(halfWidth + step, rz);
        return new double[] {simX, midY, z};
    }
}
