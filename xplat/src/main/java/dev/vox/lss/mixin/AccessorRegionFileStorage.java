package dev.vox.lss.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;

/**
 * Exposes {@code RegionFileStorage.getRegionFile} for the Phase 3 background-read split
 * (perf-round plan R1): the raw-fetch executor task resolves the region file exactly the
 * way vanilla's {@code read(pos)} does — the private method NEVER returns null (it
 * creates the region file on demand); chunk absence is signalled downstream by the
 * offset-0 / missing-stream shapes {@code RegionFileRawRead} mirrors.
 *
 * <p>Pinned by the mixin-config listing leg in {@code ChannelAccessorContractTest} —
 * an unregistered accessor resolves but is never applied, and every raw read would
 * ClassCastException into the per-chunk triage.
 */
@Mixin(RegionFileStorage.class)
public interface AccessorRegionFileStorage {

    @Invoker("getRegionFile")
    RegionFile lss$getRegionFile(ChunkPos pos) throws IOException;
}
