package dev.vox.lss.paper;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * The Paper-family twin of {@code dev.vox.lss.platform.SectionConstruction} (V-2/S2):
 * the ONLY main-source site invoking a {@link LevelChunkSection} constructor in the
 * paper module — pinned by {@code SectionConstructionPinTest}. Paper's (Moonrise) section
 * ctor takes the MUTABLE container where 26.x Fabric accepts the read-only view, which
 * is exactly the per-family variance this seam exists to hold. See the xplat twin's
 * javadoc for the per-line ctor churn and the carried 1.21.x constraint (never the
 * deserialization ctor — the AntiXray non-null-safe mixin).
 */
final class PaperSectionConstruction {
    private PaperSectionConstruction() {}

    /** An all-air section with default biomes (vanilla's empty-section fill). */
    static LevelChunkSection empty(PalettedContainerFactory factory) {
        return new LevelChunkSection(factory);
    }

    /**
     * A section from parsed containers (the NBT serializer's object path). Unpack always
     * returns the mutable container, so the instanceof matches for parsed AND default
     * biomes (the pre-headless code had the same shape); the factory fallback covers the
     * declared-RO case.
     */
    static LevelChunkSection fromContainers(PalettedContainer<BlockState> states,
                                            PalettedContainerRO<Holder<Biome>> biomes,
                                            PalettedContainerFactory factory) {
        return biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer
                ? new LevelChunkSection(states, biomeContainer)
                : new LevelChunkSection(states, factory.createForBiomes());
    }

    /**
     * A section reusing {@code biomes} BY REFERENCE with replaced states (the mask
     * filter's rebuild). Never a default-biome fallback here: a masked section must keep
     * its real biomes, so a non-mutable container is a loud failure, not a rebuild.
     */
    static LevelChunkSection withStates(PalettedContainer<BlockState> states,
                                        PalettedContainerRO<Holder<Biome>> biomes) {
        if (!(biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer)) {
            throw new IllegalStateException("section biomes are not a mutable PalettedContainer");
        }
        return new LevelChunkSection(states, biomeContainer);
    }

    /** The section's biome container (the read side the mask filter narrows). */
    static PalettedContainerRO<Holder<Biome>> biomes(LevelChunkSection section) {
        return section.getBiomes();
    }
}
