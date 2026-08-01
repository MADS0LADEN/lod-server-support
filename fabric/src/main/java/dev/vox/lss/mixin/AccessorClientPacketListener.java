package dev.vox.lss.mixin;

import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the vanilla client's own chunk-batch rate calculator (private field) for the
 * {@code net} trace event — see docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.2.
 *
 * <p>{@code getDesiredChunksPerTick()} is vanilla's measurement of how fast THIS client can
 * apply vanilla chunks (derived from {@code aggregatedNanosPerChunk}, sampled per chunk batch).
 * It is the cleanest available discriminator between "the client is too busy to keep up"
 * (the value collapses) and "the client is starved of chunks by the transport" (it stays
 * high while chunks stop arriving) — a split no server-side signal can make, because the
 * server's send queue backs up identically in both cases.
 *
 * <p>Methods are {@code lss$}-prefixed because mixin adds them to the target class: unprefixed
 * names could collide with vanilla methods.
 */
@Mixin(ClientPacketListener.class)
public interface AccessorClientPacketListener {
    @Accessor("chunkBatchSizeCalculator")
    ChunkBatchSizeCalculator lss$getChunkBatchSizeCalculator();
}
