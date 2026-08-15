package dev.vox.lss.test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * The gametests' line-neutral chunk-position surface (V-3/T2,
 * version-port-isolation-plan.md — mechanical-rename concentration): every per-MC-line
 * {@code ChunkPos} spelling the gametests touch lives HERE, so a support port flavors
 * this one file instead of sweeping ~100 call sites (the recorded 1.21.1 churn:
 * {@code ChunkPos.containing}→ctor, {@code x()/z()} accessor→field, {@code pack}→
 * {@code asLong}, and — the T2 scope extension found at the port, see the progress-doc
 * decisions log — {@code addTicketWithRadius(PLAYER_LOADING, pos, 0)}→
 * {@code addRegionTicket(PLAYER, pos, 0, pos)} at ~48 hold/release sites).
 */
final class TestPositions {
    private TestPositions() {}

    /**
     * Line-neutral chunk coordinates: RECORD accessors spell {@code x()}/{@code z()} on
     * every MC line (1.21.x's {@code ChunkPos} exposes fields), so accessor sites carry
     * no port churn; {@link #pos()} hands the real {@code ChunkPos} to any site that
     * still needs one.
     */
    record ChunkAt(int x, int z, ChunkPos pos) {}

    /** The chunk containing a world position (per-line factory: 26.x
     *  {@code ChunkPos.containing}; 1.21.1 the {@code BlockPos} ctor). */
    static ChunkAt chunkAt(BlockPos worldPos) {
        return of(ChunkPos.containing(worldPos));
    }

    /** Chunk coords direct (the two-int ctor is line-invariant; routed here so accessor
     *  sites read the record, never the per-line {@code ChunkPos} surface). */
    static ChunkAt chunkAt(int chunkX, int chunkZ) {
        return of(new ChunkPos(chunkX, chunkZ));
    }

    /** MC's own packed chunk key (per-line: 26.x {@code pack}; 1.21.1 {@code asLong}).
     *  Distinct from {@code PositionUtil.packPosition} — the LSS wire packing, which is
     *  line-invariant and never routes through here. */
    static long mcChunkKey(int chunkX, int chunkZ) {
        return ChunkPos.pack(chunkX, chunkZ);
    }

    /** Force-load hold at radius 0 (per-line ticket API: 26.x
     *  {@code addTicketWithRadius(PLAYER_LOADING, pos, 0)}; 1.21.1
     *  {@code addRegionTicket(PLAYER, pos, 0, pos)}). */
    static void holdChunk(ServerChunkCache chunkSource, ChunkAt at) {
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, at.pos(), 0);
    }

    /** Release the {@link #holdChunk} ticket. */
    static void releaseChunk(ServerChunkCache chunkSource, ChunkAt at) {
        chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, at.pos(), 0);
    }

    private static ChunkAt of(ChunkPos p) {
        return new ChunkAt(p.x(), p.z(), p);
    }
}
