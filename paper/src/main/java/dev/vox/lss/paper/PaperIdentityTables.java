package dev.vox.lss.paper;

import dev.vox.lss.common.wire.IdentityCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;

/**
 * Paper twin of the Fabric {@code IdentityTables} — textual twin discipline, same
 * enumeration ({@code Block.BLOCK_STATE_REGISTRY} / the dynamic biome registry) and
 * the same {@link IdentityCodec} canonical form, so both platforms emit identical
 * v20 dictionaries for identical content (the wire-parity contract). Ships INERT at
 * Phase 0 — no serve path consults it until the v20 encode lands.
 */
public final class PaperIdentityTables {
    private PaperIdentityTables() {}

    private static volatile String[] blockIdentities;
    private static volatile Map<String, Integer> blockIdsByIdentity;

    /** Canonical identity for every block state, indexed by global id. */
    public static String[] blockIdentities() {
        String[] table = blockIdentities;
        if (table == null) {
            synchronized (PaperIdentityTables.class) {
                table = blockIdentities;
                if (table == null) {
                    blockIdentities = table = buildBlockIdentities();
                }
            }
        }
        return table;
    }

    /** The inverse direction (legacy egress / decode): canonical identity → global id. */
    public static Map<String, Integer> blockIdsByIdentity() {
        Map<String, Integer> map = blockIdsByIdentity;
        if (map == null) {
            synchronized (PaperIdentityTables.class) {
                map = blockIdsByIdentity;
                if (map == null) {
                    String[] table = blockIdentities();
                    var built = new HashMap<String, Integer>(table.length * 2);
                    for (int id = 0; id < table.length; id++) {
                        if (table[id] != null) {
                            built.put(table[id], id);
                        }
                    }
                    blockIdsByIdentity = map = Map.copyOf(built);
                }
            }
        }
        return map;
    }

    private static String[] buildBlockIdentities() {
        var registry = Block.BLOCK_STATE_REGISTRY;
        var table = new String[registry.size()];
        for (BlockState state : registry) {
            table[registry.getId(state)] = canonicalOf(state);
        }
        return table;
    }

    /** The canonical identity of one block state — name + ALL properties, sorted. */
    public static String canonicalOf(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var props = new HashMap<String, String>();
        for (Property<?> property : state.getProperties()) {
            props.put(property.getName(), valueName(state, property));
        }
        return IdentityCodec.canonical(name, props);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Both directions for one biome registry (dynamic — per RegistryAccess). */
    public record BiomeTable(String[] identities, Map<String, Integer> idsByIdentity) {}

    public static BiomeTable biomeTable(Registry<Biome> biomes) {
        var identities = new String[biomes.size()];
        var inverse = new HashMap<String, Integer>(biomes.size() * 2);
        for (Biome biome : biomes) {
            int id = biomes.getId(biome);
            var key = biomes.getKey(biome);
            if (key == null) {
                continue;  // unkeyed intrusive entry: left null, translator fails loudly on use
            }
            String identity = key.toString();
            IdentityCodec.validate(identity);
            identities[id] = identity;
            inverse.put(identity, id);
        }
        return new BiomeTable(identities, Map.copyOf(inverse));
    }
}
