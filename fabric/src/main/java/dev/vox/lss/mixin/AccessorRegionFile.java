package dev.vox.lss.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Exposes the {@code RegionFile} internals the Phase 3 raw record fetch needs
 * ({@code RegionFileRawRead} — perf-round plan R1). Everything here SHADOWS vanilla's
 * own private helpers instead of re-deriving the region record format: the header and
 * sector arithmetic are a per-MC-version reimplementation risk, and shadowed members
 * fail LOUDLY on rename ({@code defaultRequire: 1} in lss.mixins.json) where hand-rolled
 * parsing would silently misread.
 *
 * <p>Pinned by the mixin-config listing leg in {@code ChannelAccessorContractTest}.
 */
@Mixin(RegionFile.class)
public interface AccessorRegionFile {

    @Accessor("file")
    FileChannel lss$getFile();

    @Invoker("getOffset")
    int lss$getOffset(ChunkPos pos);

    @Invoker("getExternalChunkPath")
    Path lss$getExternalChunkPath(ChunkPos pos);

    @Accessor("SECTOR_BYTES")
    static int lss$sectorBytes() {
        throw new AssertionError("mixin not applied");
    }

    @Accessor("CHUNK_HEADER_SIZE")
    static int lss$chunkHeaderSize() {
        throw new AssertionError("mixin not applied");
    }

    @Invoker("getSectorNumber")
    static int lss$getSectorNumber(int offset) {
        throw new AssertionError("mixin not applied");
    }

    @Invoker("getNumSectors")
    static int lss$getNumSectors(int offset) {
        throw new AssertionError("mixin not applied");
    }

    @Invoker("isExternalStreamChunk")
    static boolean lss$isExternalStreamChunk(byte version) {
        throw new AssertionError("mixin not applied");
    }

    @Invoker("getExternalChunkVersion")
    static byte lss$getExternalChunkVersion(byte version) {
        throw new AssertionError("mixin not applied");
    }
}
