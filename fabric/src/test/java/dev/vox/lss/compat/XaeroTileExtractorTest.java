package dev.vox.lss.compat;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure section→map-pixel recipe (xaero-map-bridge-plan.md §5) against
 * hand-built sections. The reference semantics are the decompiled
 * {@code MapWriter.loadPixel} (surface mode): floor = first opaque
 * map-colored block; topHeight = first VISIBLE block (void pixels keep world
 * bottom — the native shape); water becomes overlay runs with light-dampening
 * opacity (incl. the ≥5-deep one-step extension); lava is a floor, not an
 * overlay; invisible blocks (torches) never raise topHeight; block light is
 * sampled at floor+1; an all-air column produces the void erase shape.
 */
class XaeroTileExtractorTest {

    private static final int BOTTOM_Y = -64;
    private static final int TOP_Y = 320;

    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref ->
                biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        FACTORY = PalettedContainerFactory.create(
                new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
    }

    // ---- builders ----

    private record SectionSpec(int sectionY, LevelChunkSection section, DataLayer blockLight) {}

    private static LevelChunkSection emptySection() {
        return SectionConstruction.empty(FACTORY);
    }

    private static VoxelColumnData column(SectionSpec... specs) {
        var sections = new ArrayList<VoxelColumnData.SectionData>();
        for (var spec : specs) {
            sections.add(new VoxelColumnData.SectionData(
                    spec.sectionY(), spec.section(), spec.blockLight(), null));
        }
        return new VoxelColumnData(sections.toArray(new VoxelColumnData.SectionData[0]), 1L);
    }

    private static XaeroTileExtractor.PreparedTile extract(VoxelColumnData data) {
        return XaeroTileExtractor.extract(3, 5, BOTTOM_Y, TOP_Y, data);
    }

    private static int idx(int x, int z) {
        return x * 16 + z;
    }

    /** Fill one full 16×16 layer of a section at local y. */
    private static void fillLayer(LevelChunkSection section, int localY, BlockState state) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlockState(x, localY, z, state);
            }
        }
    }

    // ---- cases ----

    @Test
    void plainSurfaceFloorsEveryPixel() {
        var section = emptySection();
        fillLayer(section, 4, Blocks.GRASS_BLOCK.defaultBlockState()); // Y = -60
        var tile = extract(column(new SectionSpec(-4, section, null)));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = idx(x, z);
                assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), tile.floorState()[i]);
                assertEquals(-60, tile.floorY()[i]);
                assertEquals(-60, tile.topY()[i], "the floor raises topHeight");
                assertEquals(0, tile.light()[i]);
                assertFalse(tile.glowing()[i]);
                assertNull(tile.overlays()[i]);
                assertNotNull(tile.biome()[i], "biome sampled at topHeight");
            }
        }
        assertEquals(3, tile.chunkX());
        assertEquals(5, tile.chunkZ());
        assertEquals(BOTTOM_Y, tile.worldBottomY());
    }

    @Test
    void voidColumnIsTheEraseShape() {
        var tile = extract(column()); // no sections at all (also the resync-clear case)
        int i = idx(8, 8);
        assertEquals(Blocks.AIR.defaultBlockState(), tile.floorState()[i]);
        assertEquals(BOTTOM_Y, tile.floorY()[i], "void pixels write AIR at world bottom");
        assertEquals(BOTTOM_Y, tile.topY()[i], "void pixels never raise topHeight");
        assertEquals(0, tile.light()[i]);
        assertNull(tile.overlays()[i]);
    }

    @Test
    void resyncAllAirPresentSectionMatchesTheVoidShape() {
        // A resync-cleared column ships PRESENT all-air sections — it must produce the
        // same erase pixels as no sections at all (stale map terrain gets overwritten).
        var fromEmptySection = extract(column(new SectionSpec(0, emptySection(), null)));
        var fromNothing = extract(column());
        int i = idx(0, 0);
        assertEquals(fromNothing.floorState()[i], fromEmptySection.floorState()[i]);
        assertEquals(fromNothing.floorY()[i], fromEmptySection.floorY()[i]);
        assertEquals(fromNothing.topY()[i], fromEmptySection.topY()[i]);
    }

    @Test
    void waterMakesAnOverlayRunOverTheFloor() {
        var section = emptySection();
        section.setBlockState(2, 5, 3, Blocks.STONE.defaultBlockState());   // Y = -59
        section.setBlockState(2, 6, 3, Blocks.WATER.defaultBlockState());   // Y = -58
        section.setBlockState(2, 7, 3, Blocks.WATER.defaultBlockState());   // Y = -57
        section.setBlockState(2, 8, 3, Blocks.WATER.defaultBlockState());   // Y = -56
        var tile = extract(column(new SectionSpec(-4, section, null)));
        int i = idx(2, 3);
        assertEquals(Blocks.STONE.defaultBlockState(), tile.floorState()[i],
                "fluids never become the floor state");
        assertEquals(-59, tile.floorY()[i]);
        assertEquals(-56, tile.topY()[i], "topHeight = the water surface");
        var runs = tile.overlays()[i];
        assertNotNull(runs);
        assertEquals(1, runs.length, "one contiguous run, not one overlay per block");
        assertEquals(Blocks.WATER.defaultBlockState(), runs[0].state());
        assertEquals(3, runs[0].opacity(), "opacity = light dampening × run depth");
    }

    @Test
    void deepWaterTakesTheOneStepExtensionAndStaysOneRun() {
        var section = emptySection();
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());   // Y = -64
        for (int y = 1; y <= 11; y++) {
            section.setBlockState(0, y, 0, Blocks.WATER.defaultBlockState()); // Y = -63..-53
        }
        var tile = extract(column(new SectionSpec(-4, section, null)));
        int i = idx(0, 0);
        assertEquals(Blocks.STONE.defaultBlockState(), tile.floorState()[i]);
        assertEquals(-64, tile.floorY()[i]);
        assertEquals(-53, tile.topY()[i]);
        var runs = tile.overlays()[i];
        assertNotNull(runs);
        assertEquals(1, runs.length);
        assertEquals(11, runs[0].opacity(),
                "the ≥5-deep extension must account the full run depth exactly once");
    }

    @Test
    void lavaIsAFloorNotAnOverlay() {
        var section = emptySection();
        section.setBlockState(1, 2, 1, Blocks.STONE.defaultBlockState()); // Y = -62
        section.setBlockState(1, 3, 1, Blocks.LAVA.defaultBlockState());  // Y = -61
        var tile = extract(column(new SectionSpec(-4, section, null)));
        int i = idx(1, 1);
        assertEquals(Blocks.LAVA.defaultBlockState(), tile.floorState()[i],
                "non-water fluids floor the pixel (native: only translucent fluids overlay)");
        assertEquals(-61, tile.floorY()[i]);
        assertNull(tile.overlays()[i]);
    }

    @Test
    void torchesAreInvisibleAndNeverRaiseTopHeight() {
        var section = emptySection();
        section.setBlockState(4, 4, 4, Blocks.STONE.defaultBlockState()); // Y = -60
        section.setBlockState(4, 5, 4, Blocks.TORCH.defaultBlockState()); // Y = -59
        var tile = extract(column(new SectionSpec(-4, section, null)));
        int i = idx(4, 4);
        assertEquals(Blocks.STONE.defaultBlockState(), tile.floorState()[i]);
        assertEquals(-60, tile.topY()[i],
                "an invisible block must not raise topHeight (native isInvisible skip)");
    }

    @Test
    void glowingFloorsAreMarked() {
        var section = emptySection();
        section.setBlockState(0, 0, 1, Blocks.GLOWSTONE.defaultBlockState());
        var tile = extract(column(new SectionSpec(-4, section, null)));
        assertTrue(tile.glowing()[idx(0, 1)]);
    }

    @Test
    void blockLightIsSampledAboveTheFloor() {
        var section = emptySection();
        section.setBlockState(6, 4, 6, Blocks.STONE.defaultBlockState()); // Y = -60
        var light = new DataLayer();
        light.set(6, 5, 6, 13); // the nibble at floor+1
        light.set(6, 4, 6, 4);  // the floor's own nibble must NOT be used
        var tile = extract(column(new SectionSpec(-4, section, light)));
        assertEquals(13, tile.light()[idx(6, 6)]);
    }

    @Test
    void theScanCrossesMissingSections() {
        var high = emptySection();
        high.setBlockState(5, 15, 5, Blocks.STONE.defaultBlockState()); // sectionY 2 → Y = 47
        var low = emptySection();
        fillLayer(low, 0, Blocks.STONE.defaultBlockState());           // sectionY 0 → Y = 0
        var tile = extract(column(new SectionSpec(2, high, null), new SectionSpec(0, low, null)));
        assertEquals(47, tile.floorY()[idx(5, 5)]);
        assertEquals(0, tile.floorY()[idx(0, 0)],
                "the scan must cross the absent section 1 (reads as air) down to the floor");
    }

    @Test
    void colorlessBlocksAreSkippedAsFloor() {
        var section = emptySection();
        section.setBlockState(9, 3, 9, Blocks.STONE.defaultBlockState());  // Y = -61
        section.setBlockState(9, 4, 9, Blocks.BARRIER.defaultBlockState()); // Y = -60, MapColor NONE
        var tile = extract(column(new SectionSpec(-4, section, null)));
        assertEquals(Blocks.STONE.defaultBlockState(), tile.floorState()[idx(9, 9)],
                "a zero-map-color block cannot floor the pixel (native hasVanillaColor)");
    }
}
