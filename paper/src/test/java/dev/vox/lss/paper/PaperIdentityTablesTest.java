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
}
