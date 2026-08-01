package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The codec-1 decode drain (compressed-columns plan §2): decompression happens inside
 * the drain's containment — a valid frame dispatches identically to raw, and every
 * hostile shape (unknown codec, bomb-guard rejection, truncated frame) reports exactly
 * one ingest failure and the drain SURVIVES to the next column. Plus the charge
 * symmetry pin: every release path returns exactly the stored offer charge (§0.5).
 */
class ClientColumnDecompressTest {

    private static PalettedContainerFactory FACTORY;
    private static final int LEVEL_SECTIONS = 4;
    private static final int MIN_SECTION_Y = -4;
    private static StoreCodec codec;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FACTORY = PalettedContainerFactory.create(buildRegistryAccess());
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable on this platform");
    }

    private static RegistryAccess buildRegistryAccess() {
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, src.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    private record Report(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}
    private record Dispatched(int chunkX, int chunkZ, int sectionCount) {}

    private final List<Report> reports = new ArrayList<>();
    private final List<Dispatched> dispatches = new ArrayList<>();
    private final ClientColumnProcessor.ColumnDispatcher recordingDispatcher =
            (d, cx, cz, data) -> dispatches.add(new Dispatched(cx, cz, data.sections().length));

    private ClientColumnProcessor processor;
    private ResourceKey<Level> dim;

    @BeforeEach
    void setUp() {
        processor = new ClientColumnProcessor(
                (d, cx, cz) -> reports.add(new Report(d, cx, cz)),
                () -> null);
        dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:decompress"));
    }

    private void drainNow() {
        processor.drainColumnQueue(dim, LEVEL_SECTIONS, MIN_SECTION_Y, false, FACTORY,
                recordingDispatcher, processor.sessionEpochForTest());
    }

    /** One real (empty, lightless) section as wire bytes — decodes to 1 section. */
    private static byte[] oneSectionWire() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(1);
            buf.writeByte(0);
            new LevelChunkSection(FACTORY).write(buf);
            buf.writeBoolean(false);
            buf.writeBoolean(false);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** A codec-1 payload as the read path would produce it (honest declared charge). */
    private VoxelColumnS2CPayload framed(int cx, int cz, byte[] raw) {
        byte[] frame = codec.compress(raw);
        int charge = (int) Math.max(frame.length,
                Math.min(codec.declaredContentSize(frame), LSSConstants.MAX_SECTIONS_SIZE));
        return new VoxelColumnS2CPayload(cx, cz, dim, 1L, (byte) 0,
                LSSConstants.COLUMN_CODEC_ZSTD, frame, charge);
    }

    @Test
    void validFrameDecompressesAndDispatches() {
        byte[] raw = oneSectionWire();
        processor.offer(framed(3, 4, raw), false);
        drainNow();
        assertEquals(List.of(new Dispatched(3, 4, 1)), dispatches);
        assertEquals(List.of(), reports);
        assertEquals(0, processor.getQueuedCount());
        assertEquals(0, processor.getQueuedBytes(), "charge released exactly once");
    }

    @Test
    void unknownCodecReportsAndDrainContinues() {
        processor.offer(new VoxelColumnS2CPayload(1, 1, dim, 1L, (byte) 0,
                (byte) 9, oneSectionWire(), oneSectionWire().length), false);
        processor.offer(framed(2, 2, oneSectionWire()), false);
        drainNow();
        assertEquals(List.of(new Report(dim, 1, 1)), reports,
                "codec outside {0,1} is a decode failure, NOT a pass-through");
        assertEquals(List.of(new Dispatched(2, 2, 1)), dispatches,
                "the drain survives to the next column");
        assertEquals(0, processor.getQueuedBytes());
    }

    @Test
    void bombGuardRejectsOverCapDeclarationBeforeAllocation() {
        // A REAL frame whose declared content exceeds MAX_SECTIONS_SIZE: the guard rejects
        // on the declaration alone — the 2 MiB+ decode buffer is never allocated.
        byte[] huge = new byte[LSSConstants.MAX_SECTIONS_SIZE + 8];
        byte[] frame = codec.compress(huge);
        processor.offer(new VoxelColumnS2CPayload(5, 6, dim, 1L, (byte) 0,
                LSSConstants.COLUMN_CODEC_ZSTD, frame, LSSConstants.MAX_SECTIONS_SIZE), false);
        drainNow();
        assertEquals(List.of(new Report(dim, 5, 6)), reports);
        assertEquals(List.of(), dispatches);
        assertEquals(0, processor.getQueuedBytes());
    }

    @Test
    void truncatedFrameReportsThroughIngestFailure() {
        byte[] raw = oneSectionWire();
        byte[] frame = codec.compress(raw);
        byte[] truncated = java.util.Arrays.copyOf(frame, frame.length - 4);
        processor.offer(new VoxelColumnS2CPayload(7, 8, dim, 1L, (byte) 0,
                LSSConstants.COLUMN_CODEC_ZSTD, truncated, raw.length), false);
        drainNow();
        assertEquals(List.of(new Report(dim, 7, 8)), reports,
                "a zstd throw is contained as a per-column decode failure");
        assertEquals(List.of(), dispatches);
        assertEquals(0, processor.getQueuedBytes());
    }

    @Test
    void chargeSymmetryAcrossClearPaths() {
        var p = framed(1, 2, oneSectionWire());
        processor.offer(p, false);
        assertEquals(p.rawSize(), processor.getQueuedBytes(),
                "offer charges the payload's read-memoized raw size");
        processor.shutdown();
        assertEquals(0, processor.getQueuedBytes(),
                "shutdown releases exactly the stored charge — never re-derived");
        assertEquals(0, processor.getQueuedCount());
    }
}
