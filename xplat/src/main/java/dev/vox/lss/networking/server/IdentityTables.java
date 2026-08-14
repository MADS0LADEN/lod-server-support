package dev.vox.lss.networking.server;

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
 * The protocol-20 registry identity tables (XVER Phase 0): global id ↔ canonical
 * identity string ({@link IdentityCodec} form), both directions.
 *
 * <p>Blocks enumerate {@code Block.BLOCK_STATE_REGISTRY} — the same enumeration
 * {@code RegistryFingerprint} uses (its iteration order IS global-id order, but ids
 * are taken from {@code getId} rather than assumed). Biomes are the dynamic
 * per-{@code RegistryAccess} registry, so their table is built per registry instance
 * by the caller ({@link #biomeTable}); the block table is JVM-global and lazy.
 *
 * <p>Every identity passes {@link IdentityCodec#canonical} validation at build — an
 * out-of-charset modded property value fails the table build loudly (and its Tier-1
 * whole-registry sweep), never as silent wire corruption. Phase 0 ships these tables
 * INERT: no serve path consults them until the v20 encode lands (C1); the settling
 * measurement and the translator tests are the only consumers today.
 */
public final class IdentityTables {
    private IdentityTables() {}

    private static volatile String[] blockIdentities;
    private static volatile Map<String, Integer> blockIdsByIdentity;

    /** Canonical identity for every block state, indexed by global id. */
    public static String[] blockIdentities() {
        String[] table = blockIdentities;
        if (table == null) {
            synchronized (IdentityTables.class) {
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
            synchronized (IdentityTables.class) {
                map = blockIdsByIdentity;
                if (map == null) {
                    String[] table = blockIdentities();
                    var built = new HashMap<String, Integer>(table.length * 2);
                    int live = 0;
                    for (int id = 0; id < table.length; id++) {
                        if (table[id] != null) {
                            built.put(table[id], id);
                            live++;
                        }
                    }
                    // Fail-loud on duplicate canonical identities (pre-D3 review L1-1):
                    // put() overwrites, so a modded Property whose getName(T) collides
                    // two values would silently serve state B for state A to every
                    // legacy session and lossy-migrate store rows. Vanilla cannot hit
                    // this (per-block property names are unique); a mod that does must
                    // fail at table build, not corrupt the wire.
                    if (built.size() != live) {
                        throw new IllegalStateException("block identity table has "
                                + (live - built.size()) + " duplicate canonical identities"
                                + " — a modded block Property collides state names");
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

    /**
     * Bounds-checked lookup for wire-derived ids — the shape every consumer should
     * use (a bare {@code table[id]} turns a corrupt palette id into
     * {@code ArrayIndexOutOfBoundsException} instead of the translator's pinned
     * loud failure). Returns null for an unknown id; {@code NativeToV20Translator}
     * converts null into {@code IllegalArgumentException}.
     */
    public static String blockIdentityFor(int globalId) {
        String[] table = blockIdentities();
        return globalId >= 0 && globalId < table.length ? table[globalId] : null;
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
    public record BiomeTable(String[] identities, Map<String, Integer> idsByIdentity) {
        /** Bounds-checked lookup for wire-derived ids (see {@code blockIdentityFor}). */
        public String identityFor(int id) {
            return id >= 0 && id < identities.length ? identities[id] : null;
        }
    }

    public static BiomeTable biomeTable(Registry<Biome> biomes) {
        var identities = new String[biomes.size()];
        var inverse = new HashMap<String, Integer>(biomes.size() * 2);
        for (Biome biome : biomes) {
            int id = biomes.getId(biome);
            var key = biomes.getKey(biome);
            if (key == null) {
                // A registry-iterated value always has a key; a null here means the
                // registry is broken — fail the table build loudly rather than leave
                // a hole that surfaces as a per-serve exception later.
                throw new IllegalStateException("biome id " + id + " has no registry key");
            }
            String identity = key.toString();
            IdentityCodec.validate(identity);
            identities[id] = identity;
            inverse.put(identity, id);
        }
        return new BiomeTable(identities, Map.copyOf(inverse));
    }
}
