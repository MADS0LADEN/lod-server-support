package dev.vox.lss.common.wire;

import dev.vox.lss.common.wire.WireSectionCursor.Layout;
import dev.vox.lss.common.wire.WireSectionCursor.WireColumn;
import dev.vox.lss.common.wire.WireSectionCursor.WireContainer;
import dev.vox.lss.common.wire.WireSectionCursor.WireSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native → v20 byte-domain translation (§4.2 encode direction): indexed
 * containers keep bits + packed longs VERBATIM with palettes rewritten to
 * dictionary indices; DIRECT containers re-palettize (first-seen scan order) and
 * repack at the §2.1 v20 widths; count shorts and light pass through; the
 * dictionary is shared, first-seen, and validated.
 */
class NativeToV20TranslatorTest {

    private static final IntFunction<String> BLOCKS = id -> switch (id) {
        case 0 -> "minecraft:air";
        case 9 -> "minecraft:stone";
        case 130 -> "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]";
        default -> "minecraft:generated_" + id;
    };
    private static final IntFunction<String> BIOMES = id -> switch (id) {
        case 2 -> "minecraft:plains";
        default -> "minecraft:biome_" + id;
    };

    private static byte[] nativeBody(WireSection... sections) {
        return WireSectionCursor.emit(new WireColumn(List.of(), List.of(sections)), Layout.NATIVE);
    }

    private static long[] packed(int[] values, int bits) {
        return WireSectionCursor.pack(values, bits);
    }

    @Test
    void indexedSectionsShipLongsVerbatimWithDictIndexPalettes() {
        var rng = new Random(7);
        var blockValues = new int[4096];
        for (int i = 0; i < 4096; i++) {
            blockValues[i] = rng.nextInt(3);
        }
        var blocks = new WireContainer(4, new int[] { 0, 9, 130 }, packed(blockValues, 4));
        var biomes = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(-4, 100, 5, blocks, biomes, null, null)),
                BLOCKS, BIOMES);

        var column = WireSectionCursor.parse(v20, Layout.V20);
        assertEquals(List.of("minecraft:air", "minecraft:stone",
                "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]",
                "minecraft:plains"), column.dictionary());
        var s = column.sections().get(0);
        assertEquals(-4, s.sectionY());
        assertEquals(100, s.nonEmptyBlockCount());
        assertEquals(5, s.fluidCount());
        assertEquals(4, s.blocks().bits());
        assertArrayEquals(new int[] { 0, 1, 2 }, s.blocks().palette());
        assertArrayEquals(blocks.data(), s.blocks().data(), "packed longs must ship verbatim");
        assertEquals(0, s.biomes().bits());
        assertArrayEquals(new int[] { 3 }, s.biomes().palette());
    }

    @Test
    void directBlocksArePalettizedInFirstSeenOrderAndRepacked() {
        // A DIRECT container at width 15 holding three distinct ids.
        var ids = new int[4096];
        for (int i = 0; i < 4096; i++) {
            ids[i] = (i % 3 == 0) ? 9 : (i % 3 == 1 ? 4242 : 130);
        }
        var direct = new WireContainer(15, null, packed(ids, 15));
        var biomes = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(2, 4096, 0, direct, biomes, null, null)),
                BLOCKS, BIOMES);

        var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
        // First-seen scan order: 9, 4242, 130 -> dict 0,1,2; blocks floor width 4.
        assertEquals(4, s.blocks().bits());
        assertArrayEquals(new int[] { 0, 1, 2 }, s.blocks().palette());
        int[] repacked = WireSectionCursor.unpack(s.blocks().data(), 4, 4096);
        for (int i = 0; i < 4096; i++) {
            int expected = (i % 3 == 0) ? 0 : (i % 3 == 1 ? 1 : 2);
            assertEquals(expected, repacked[i], "voxel " + i + " must be preserved");
        }
        assertEquals(List.of("minecraft:stone", "minecraft:generated_4242",
                "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]",
                "minecraft:plains"),
                WireSectionCursor.parse(v20, Layout.V20).dictionary());
    }

    @Test
    void directBiomesRepackAtCeillog2IncludingTheSingleValueCollapse() {
        var biomeIds = new int[64];
        for (int i = 0; i < 64; i++) {
            biomeIds[i] = (i < 32) ? 2 : 13;  // both fit the 4-bit DIRECT width
        }
        var directBiomes = new WireContainer(4, null, packed(biomeIds, 4));
        var single = new WireContainer(0, new int[] { 9 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(0, 1, 0, single, directBiomes, null, null)),
                BLOCKS, BIOMES);
        var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
        assertEquals(1, s.biomes().bits(), "two entries -> ceillog2(2) = 1 (no block floor)");
        int[] repacked = WireSectionCursor.unpack(s.biomes().data(), 1, 64);
        for (int i = 0; i < 64; i++) {
            assertEquals(i < 32 ? 0 : 1, repacked[i]);
        }

        // All-one-id DIRECT collapses to a single-value container (bits 0, no data).
        var uniform = new WireContainer(4, null, packed(new int[64], 4)); // all id 0
        byte[] v20b = NativeToV20Translator.translate(
                nativeBody(new WireSection(0, 1, 0, single, uniform, null, null)),
                BLOCKS, id -> "minecraft:the_void");
        var sb = WireSectionCursor.parse(v20b, Layout.V20).sections().get(0);
        assertEquals(0, sb.biomes().bits());
        assertEquals(0, sb.biomes().data().length);
    }

    @Test
    void dictionaryIsSharedAcrossSectionsAndKindsFirstSeen() {
        var stoneSingle = new WireContainer(0, new int[] { 9 }, new long[0]);
        var plainsSingle = new WireContainer(0, new int[] { 2 }, new long[0]);
        var s1 = new WireSection(-4, 1, 0, stoneSingle, plainsSingle, null, null);
        var s2 = new WireSection(-3, 1, 0, stoneSingle, plainsSingle, null, null);
        byte[] v20 = NativeToV20Translator.translate(nativeBody(s1, s2), BLOCKS, BIOMES);
        var column = WireSectionCursor.parse(v20, Layout.V20);
        // stone first (section 1 blocks), plains second (section 1 biomes); section 2
        // adds nothing — repeats resolve to the same indices.
        assertEquals(List.of("minecraft:stone", "minecraft:plains"), column.dictionary());
        assertArrayEquals(new int[] { 0 }, column.sections().get(1).blocks().palette());
        assertArrayEquals(new int[] { 1 }, column.sections().get(1).biomes().palette());
    }

    @Test
    void clearColumnTranslatesToTheEmptyDictionaryClearFrame() {
        byte[] v20 = NativeToV20Translator.translate(nativeBody(), BLOCKS, BIOMES);
        assertArrayEquals(new byte[] { 0, 0 }, v20);
    }

    @Test
    void lightLayersAndCountShortsPassThroughVerbatim() {
        var blLight = new byte[2048];
        var skyLight = new byte[2048];
        new Random(3).nextBytes(blLight);
        new Random(4).nextBytes(skyLight);
        var single = new WireContainer(0, new int[] { 9 }, new long[0]);
        var biome = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(5, 1234, 77, single, biome, blLight, skyLight)),
                BLOCKS, BIOMES);
        var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
        assertEquals(1234, s.nonEmptyBlockCount());
        assertEquals(77, s.fluidCount());
        assertArrayEquals(blLight, s.blockLight());
        assertArrayEquals(skyLight, s.skyLight());
    }

    @Test
    void missingOrInvalidIdentitiesFailLoudly() {
        var single = new WireContainer(0, new int[] { 9 }, new long[0]);
        var biome = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] body = nativeBody(new WireSection(0, 1, 0, single, biome, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> NativeToV20Translator.translate(body, id -> null, BIOMES));
        assertThrows(IllegalArgumentException.class,
                () -> NativeToV20Translator.translate(body, id -> "Not Canonical", BIOMES));
    }

    @Test
    void v20BitsMatchesTheSpecTable() {
        assertEquals(0, NativeToV20Translator.v20Bits(1, true));
        assertEquals(4, NativeToV20Translator.v20Bits(2, true));
        assertEquals(4, NativeToV20Translator.v20Bits(16, true));
        assertEquals(5, NativeToV20Translator.v20Bits(17, true));
        assertEquals(8, NativeToV20Translator.v20Bits(256, true));
        assertEquals(9, NativeToV20Translator.v20Bits(257, true));
        assertEquals(12, NativeToV20Translator.v20Bits(4096, true));
        assertEquals(0, NativeToV20Translator.v20Bits(1, false));
        assertEquals(1, NativeToV20Translator.v20Bits(2, false));
        assertEquals(3, NativeToV20Translator.v20Bits(8, false));
        assertEquals(6, NativeToV20Translator.v20Bits(64, false));
    }

    /** Fuzz: random native columns translate to parseable v20 whose voxel content matches. */
    @Test
    void translationPreservesEveryVoxelUnderFuzz() {
        var rng = new Random(20260807);
        for (int round = 0; round < 40; round++) {
            int paletteSize = 1 + rng.nextInt(20);
            var palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = rng.nextInt(30000);
            }
            var values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                values[i] = rng.nextInt(paletteSize);
            }
            WireContainer blocks;
            boolean direct = rng.nextBoolean();
            if (direct) {
                var ids = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    ids[i] = palette[values[i]];
                }
                blocks = new WireContainer(15, null, packed(ids, 15));
            } else {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
                blocks = paletteSize == 1
                        ? new WireContainer(0, palette, new long[0])
                        : new WireContainer(bits, palette, packed(values, bits));
            }
            var biomes = new WireContainer(0, new int[] { rng.nextInt(60) }, new long[0]);
            byte[] v20 = NativeToV20Translator.translate(
                    nativeBody(new WireSection(0, 1, 0, blocks, biomes, null, null)),
                    BLOCKS, BIOMES);
            var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
            var dict = WireSectionCursor.parse(v20, Layout.V20).dictionary();
            assertNotNull(s.blocks().palette());
            int[] decodedIdx = s.blocks().bits() == 0
                    ? null
                    : WireSectionCursor.unpack(s.blocks().data(), s.blocks().bits(), 4096);
            for (int i = 0; i < 4096; i++) {
                String expected = BLOCKS.apply(palette[values[i]]);
                int dictIndex = decodedIdx == null
                        ? s.blocks().palette()[0]
                        : s.blocks().palette()[decodedIdx[i]];
                assertEquals(expected, dict.get(dictIndex), "round " + round + " voxel " + i);
            }
            assertTrue(s.blocks().bits() == 0 || s.blocks().bits() >= 4);
        }
    }
}
