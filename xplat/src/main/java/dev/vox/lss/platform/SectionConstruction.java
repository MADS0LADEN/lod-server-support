package dev.vox.lss.platform;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * The section-construction seam (V-2/S2, version-port-isolation-plan.md §3): the ONLY
 * main-source site that invokes a {@link LevelChunkSection} constructor in the
 * xplat/fabric/neoforge family — pinned by {@code SectionConstructionPinTest}. The ctor
 * family is per-MC-line churn ({@link PalettedContainerFactory} exists only on 26.x; the
 * 1.21.x lines use the {@code Registry<Biome>} family), so a port edits THIS file and its
 * Paper twin ({@code PaperSectionConstruction}) instead of every consumer.
 *
 * <p><b>Constraint carried from the 1.21.11 branch (the plan's S2 note):</b> no flavor of
 * this helper may ever use the section DESERIALIZATION ctor — on 1.21.x, AntiXray 1.4.x's
 * one non-null-safe mixin wraps it (raw ThreadLocal NPE outside its packet scope) and no
 * automated tier covers that path (AntiXray is stubbed in Tier 1).
 */
public final class SectionConstruction {
    private SectionConstruction() {}

    /** An all-air section with default biomes (vanilla's empty-section fill). */
    public static LevelChunkSection empty(PalettedContainerFactory factory) {
        return new LevelChunkSection(factory);
    }

    /**
     * A section from parsed containers (the NBT serializers' object path). {@code factory}
     * is unused on this line — the 26.x ctor accepts the read-only view — but the Paper
     * twin needs it for its non-mutable-biomes fallback, and the twins keep one signature.
     */
    public static LevelChunkSection fromContainers(PalettedContainer<BlockState> states,
                                                   PalettedContainerRO<Holder<Biome>> biomes,
                                                   PalettedContainerFactory factory) {
        return new LevelChunkSection(states, biomes);
    }

    /**
     * A section reusing {@code biomes} BY REFERENCE with replaced states (the mask
     * filters' rebuild). Biomes must be the concrete mutable container — unreachable
     * otherwise on live and codecRO-parsed sections alike; the check exists because
     * Paper's (Moonrise) ctor requires the mutable type and the twins stay aligned.
     * Never a default-biome fallback here: a masked section must keep its real biomes.
     */
    public static LevelChunkSection withStates(PalettedContainer<BlockState> states,
                                               PalettedContainerRO<Holder<Biome>> biomes) {
        if (!(biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer)) {
            throw new IllegalStateException("section biomes are not a mutable PalettedContainer");
        }
        return new LevelChunkSection(states, biomeContainer);
    }

    /** The section's biome container (the read side the mask filters narrow). */
    public static PalettedContainerRO<Holder<Biome>> biomes(LevelChunkSection section) {
        return section.getBiomes();
    }
}
