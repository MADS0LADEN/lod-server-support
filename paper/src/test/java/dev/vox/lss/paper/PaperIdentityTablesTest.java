package dev.vox.lss.paper;

import dev.vox.lss.common.wire.IdentityCodec;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper twin of {@code IdentityTablesTest}: the same spot pins and the whole-registry
 * canonical/unique sweep, run against Paper's runtime — both platforms must produce
 * IDENTICAL canonical identities or v20 dictionaries would diverge across platforms
 * (the wire-parity contract). The exact-string spot pins here are the cross-platform
 * anchor: they must match the Fabric suite's literals character-for-character.
 */
class PaperIdentityTablesTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void propertylessStateIsTheBareForm() {
        int stoneId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        assertEquals("minecraft:stone", PaperIdentityTables.blockIdentities()[stoneId]);
    }

    @Test
    void propertiedStateCarriesAllPropertiesSorted() {
        var state = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]",
                PaperIdentityTables.canonicalOf(state));
    }

    @Test
    void everyBlockStateIdentityIsCanonicalAndGloballyUnique() {
        String[] table = PaperIdentityTables.blockIdentities();
        assertEquals(Block.BLOCK_STATE_REGISTRY.size(), table.length);
        var seen = new HashSet<String>(table.length * 2);
        for (int id = 0; id < table.length; id++) {
            String identity = table[id];
            assertNotNull(identity, "hole at global id " + id);
            IdentityCodec.validate(identity);
            assertTrue(seen.add(identity), "duplicate identity: " + identity);
        }
    }

    @Test
    void inverseTableIsTheExactMirror() {
        String[] table = PaperIdentityTables.blockIdentities();
        var inverse = PaperIdentityTables.blockIdsByIdentity();
        assertEquals(table.length, inverse.size());
        for (int id = 0; id < table.length; id++) {
            assertEquals(id, inverse.get(table[id]), "inverse of " + table[id]);
        }
    }

    @Test
    void boundsCheckedLookupReturnsNullOutsideTheTable() {
        assertEquals(null, PaperIdentityTables.blockIdentityFor(-1));
        assertEquals(null, PaperIdentityTables.blockIdentityFor(Integer.MAX_VALUE));
        assertNotNull(PaperIdentityTables.blockIdentityFor(0));
    }

    @Test
    void biomeTableCoversTheRegistryBothDirections() {
        var provider = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var src = provider.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var registry = new net.minecraft.core.MappedRegistry<>(
                net.minecraft.core.registries.Registries.BIOME,
                com.mojang.serialization.Lifecycle.stable());
        src.listElements().forEach(ref -> registry.register(ref.key(), ref.value(),
                net.minecraft.core.RegistrationInfo.BUILT_IN));
        registry.freeze();

        var biomeTable = PaperIdentityTables.biomeTable(registry);
        assertEquals(registry.size(), biomeTable.identities().length);
        assertTrue(biomeTable.identities().length > 50, "vanilla has ~65 biomes");
        for (int id = 0; id < biomeTable.identities().length; id++) {
            String identity = biomeTable.identities()[id];
            assertNotNull(identity, "hole at biome id " + id);
            IdentityCodec.validate(identity);
            assertEquals(id, biomeTable.idsByIdentity().get(identity));
        }
        assertTrue(biomeTable.idsByIdentity().containsKey("minecraft:plains"));
        assertEquals(null, biomeTable.identityFor(-1));
    }
}
