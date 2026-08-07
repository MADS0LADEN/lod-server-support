package dev.vox.lss.networking.server;

import dev.vox.lss.common.wire.IdentityCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry identity tables against the REAL 26.2 registries: spot-pinned
 * canonical forms, and the whole-registry sweep that is the charset-safety pin's
 * strongest form — every one of the ~29k block states must produce a canonical,
 * strictly-parseable, GLOBALLY UNIQUE identity, and the inverse table must be the
 * exact mirror. A vanilla (or, on a modded server, modded) property value that
 * violated the no-escaping charset would fail here at table build, not on the wire.
 */
class IdentityTablesTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> BIOMES;

    @BeforeAll
    static void setup() {
        var biomes = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        var registry = new net.minecraft.core.MappedRegistry<>(Registries.BIOME,
                com.mojang.serialization.Lifecycle.stable());
        biomes.listElements().forEach(ref -> registry.register(ref.key(), ref.value(),
                net.minecraft.core.RegistrationInfo.BUILT_IN));
        registry.freeze();
        BIOMES = registry;
    }

    @Test
    void propertylessStateIsTheBareForm() {
        int stoneId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        assertEquals("minecraft:stone", IdentityTables.blockIdentities()[stoneId]);
    }

    @Test
    void propertiedStateCarriesAllPropertiesSorted() {
        var state = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        String identity = IdentityTables.canonicalOf(state);
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]",
                identity);
    }

    @Test
    void everyBlockStateIdentityIsCanonicalAndGloballyUnique() {
        String[] table = IdentityTables.blockIdentities();
        assertEquals(Block.BLOCK_STATE_REGISTRY.size(), table.length);
        var seen = new HashSet<String>(table.length * 2);
        for (int id = 0; id < table.length; id++) {
            String identity = table[id];
            assertNotNull(identity, "hole at global id " + id);
            IdentityCodec.validate(identity);  // throws on any charset/grammar violation
            assertTrue(seen.add(identity), "duplicate identity: " + identity);
        }
    }

    @Test
    void inverseTableIsTheExactMirror() {
        String[] table = IdentityTables.blockIdentities();
        var inverse = IdentityTables.blockIdsByIdentity();
        assertEquals(table.length, inverse.size());
        for (int id = 0; id < table.length; id++) {
            assertEquals(id, inverse.get(table[id]), "inverse of " + table[id]);
        }
    }

    @Test
    void boundsCheckedLookupReturnsNullOutsideTheTable() {
        assertEquals(null, IdentityTables.blockIdentityFor(-1));
        assertEquals(null, IdentityTables.blockIdentityFor(Integer.MAX_VALUE));
        assertNotNull(IdentityTables.blockIdentityFor(0));
    }

    @Test
    void biomeTableCoversTheRegistryBothDirections() {
        var biomeTable = IdentityTables.biomeTable(BIOMES);
        assertEquals(BIOMES.size(), biomeTable.identities().length);
        assertTrue(biomeTable.identities().length > 50, "vanilla has ~65 biomes");
        for (int id = 0; id < biomeTable.identities().length; id++) {
            String identity = biomeTable.identities()[id];
            assertNotNull(identity, "hole at biome id " + id);
            IdentityCodec.validate(identity);
            assertEquals(id, biomeTable.idsByIdentity().get(identity));
        }
        assertTrue(biomeTable.idsByIdentity().containsKey("minecraft:plains"));
    }
}
