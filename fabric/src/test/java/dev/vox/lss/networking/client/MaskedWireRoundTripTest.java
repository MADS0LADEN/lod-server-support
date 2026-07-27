package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.config.ServerConfigBase;
import dev.vox.lss.networking.server.XrayMaskFilter;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full wire round-trip of a masked serve — the exact SectionSerializer write sequence into
 * the exact client decode — diffed against the unmasked serve of the same sections. Pins
 * the invariants every masking change must keep END TO END: light-layer presence and bytes
 * stay aligned after the (size-changing) masked section payload, section pairing survives,
 * only mask-hidden cells change, and no cell becomes air. Born as the diagnostic that
 * CLEARED the wire during the 2026-07-27 black-LOD investigation (the real cause was the
 * mode-2/3 engine-list adoption, fixed in AntiXrayCompat) — kept so a future wire
 * asymmetry in masked serves cannot masquerade as a renderer bug again.
 */
class MaskedWireRoundTripTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegistryAccess REGISTRY_ACCESS;
    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        for (var key : List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.JUNGLE, Biomes.SNOWY_TAIGA)) {
            biomes.register(key, src.getOrThrow(key).value(), RegistrationInfo.BUILT_IN);
        }
        biomes.freeze();
        REGISTRY_ACCESS = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        FACTORY = PalettedContainerFactory.create(REGISTRY_ACCESS);
    }

    private static LevelChunkSection buildTerrainSection() {
        var s = new LevelChunkSection(FACTORY);
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    BlockState st;
                    if (y < 10) st = Blocks.STONE.defaultBlockState();
                    else if (y < 12) st = Blocks.DIRT.defaultBlockState();
                    else if (y == 12) st = Blocks.GRASS_BLOCK.defaultBlockState();
                    else st = Blocks.AIR.defaultBlockState();
                    s.setBlockState(x, y, z, st);
                }
        // ores in the stone band
        for (int i = 0; i < 4096; i += 31) {
            int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
            if (y < 10) s.setBlockState(x, y, z, Blocks.DIAMOND_ORE.defaultBlockState());
        }
        return s;
    }

    private static byte[] lightPattern(int seed) {
        byte[] b = new byte[2048];
        for (int i = 0; i < 2048; i++) b[i] = (byte) ((i * 31 + seed) & 0xFF);
        return b;
    }

    /** Mirrors SectionSerializer pass-2's exact write sequence for one column. */
    private static byte[] writeColumn(List<LevelChunkSection> sections, byte[][] blockLight, byte[][] skyLight,
                                      XrayMaskFilter.MaskSet mask) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(sections.size());
            for (int i = 0; i < sections.size(); i++) {
                var section = sections.get(i);
                if (mask != null) {
                    var masked = XrayMaskFilter.mask(section, i, mask, FallbackKind.OVERWORLD, FACTORY);
                    section = masked;
                }
                buf.writeByte(i);
                section.write(buf);
                buf.writeBoolean(blockLight[i] != null);
                if (blockLight[i] != null) buf.writeBytes(blockLight[i]);
                buf.writeBoolean(skyLight[i] != null);
                if (skyLight[i] != null) buf.writeBytes(skyLight[i]);
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    @Test
    void maskedRoundTripPreservesLightAndNonHiddenCells() {
        var s0 = buildTerrainSection();          // straddles: terrain + air above
        var s1 = new LevelChunkSection(FACTORY); // uniform stone + ores (deep section)
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    s1.setBlockState(x, y, z, ((x + y + z) % 17 == 0)
                            ? Blocks.DIAMOND_ORE.defaultBlockState()
                            : Blocks.STONE.defaultBlockState());

        var sections = List.of(s1, s0);
        byte[][] bl = {lightPattern(1), null};
        byte[][] sl = {null, lightPattern(7)};

        var mask = XrayMaskFilter.MaskSet.resolve(ServerConfigBase.defaultXrayHiddenBlocks(), 256);
        byte[] wireUnmasked = writeColumn(sections, bl, sl, null);
        byte[] wireMasked = writeColumn(sections, bl, sl, mask);

        var plain = ClientColumnProcessor.decodeSections(wireUnmasked, 24, FACTORY);
        var masked = ClientColumnProcessor.decodeSections(wireMasked, 24, FACTORY);

        assertEquals(plain.length, masked.length, "section count must survive masking");
        var hidden = mask;
        for (int i = 0; i < plain.length; i++) {
            var p = plain[i];
            var m = masked[i];
            assertEquals(p.sectionY(), m.sectionY(), "sectionY pairing");
            // light alignment: presence and bytes identical
            assertEquals(p.blockLight() == null, m.blockLight() == null, "blockLight presence @ " + i);
            assertEquals(p.skyLight() == null, m.skyLight() == null, "skyLight presence @ " + i);
            if (p.blockLight() != null) assertArrayEquals(p.blockLight().getData(), m.blockLight().getData(), "blockLight bytes @ " + i);
            if (p.skyLight() != null) assertArrayEquals(p.skyLight().getData(), m.skyLight().getData(), "skyLight bytes @ " + i);
            // cells: identical except hidden cells; air stays air
            int diffs = 0, airFlips = 0;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState a = p.section().getBlockState(x, y, z);
                        BlockState b = m.section().getBlockState(x, y, z);
                        if (!a.equals(b)) {
                            diffs++;
                            if (!hidden.contains(a)) fail("non-hidden cell changed @ s" + i + " " + x + "," + y + "," + z + ": " + a + " -> " + b);
                            if (b.isAir()) airFlips++;
                        }
                        if (a.isAir()) assertTrue(b.isAir(), "air cell must stay air");
                    }
            assertEquals(0, airFlips, "no cell may become air");
            assertTrue(diffs > 0, "the fixture must actually exercise masking in section " + i);
        }
    }
}
