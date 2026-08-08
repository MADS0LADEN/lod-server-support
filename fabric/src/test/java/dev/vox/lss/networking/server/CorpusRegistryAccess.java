package dev.vox.lss.networking.server;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.List;

/**
 * The corpus-fixed biome {@link RegistryAccess} the golden suites share (extracted at
 * C2 so the translation-chain suite and the serializer suite provably decode against
 * the SAME registry the committed fixtures were generated with).
 *
 * <p>Carries exactly the BIOME registry — the only registry
 * {@code PalettedContainerFactory.create} reads (block states come from static
 * bootstrap state). The golden corpus bytes embed biome palette ids, so ids must be
 * platform- and version-independent: register exactly the corpus biomes in this fixed
 * order (full-vanilla {@code listElements()} order differs between the Fabric and
 * Paper test runtimes and skewed the fixtures). <b>Never reorder; APPEND-ONLY</b> —
 * ids are assigned in list order and the committed goldens bake them; if this list
 * changes, regenerate all goldens on BOTH modules.
 */
final class CorpusRegistryAccess {
    private CorpusRegistryAccess() {}

    static RegistryAccess build() {
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        for (var key : List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.JUNGLE, Biomes.SNOWY_TAIGA,
                // Appended for the round-2 transcode goldens (the biome 3-bit-linear and
                // global tiers need >4 and >8 distinct biomes).
                Biomes.SWAMP, Biomes.TAIGA, Biomes.SAVANNA, Biomes.BADLANDS, Biomes.BEACH,
                Biomes.RIVER)) {
            biomes.register(key, src.getOrThrow(key).value(), RegistrationInfo.BUILT_IN);
        }
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }
}
