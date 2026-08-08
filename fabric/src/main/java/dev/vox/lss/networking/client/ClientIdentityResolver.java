package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.wire.IdentityCodec;
import dev.vox.lss.common.wire.V20ToNativeTranslator;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The client's unknown-identity fallback ladder (XVER §3), injected into
 * {@link V20ToNativeTranslator} as the identity → local-id resolver:
 * <ol>
 * <li><b>Exact</b>: the block registry has the name → {@code defaultBlockState()} →
 *     apply each property via {@code Property.getValue(String)}; unknown property
 *     names/values are SKIPPED, missing ones keep defaults — the drop-unknown-props
 *     rung falls out of the resolve algorithm for free.</li>
 * <li><b>Curated table</b> (name-level): {@code crossVersionBlockFallbacks} from the
 *     client config — removed/renamed names mapped to visually-similar local ones,
 *     ViaBackwards-diff style. Ships EMPTY; populated as real gaps are reported.</li>
 * <li><b>Terminal default</b>: {@code unknownBlockFallback} (default
 *     {@code minecraft:stone}). NEVER air — air punches holes in distant terrain
 *     (ViaVersion's air default is the wrong answer for LOD), so an air-resolving
 *     configured fallback is coerced to stone. Biomes: unknown →
 *     {@code minecraft:plains}.</li>
 * </ol>
 *
 * <p>Per-identity resolution is memoized for the resolver's lifetime (one instance
 * per session — the processor rebuilds it if the registry access changes); each
 * fallback logs once per identity per session (bounded) and bumps the
 * {@code fallbacks} counter surfaced in the client trace/diag.
 */
final class ClientIdentityResolver {

    private final Registry<Biome> biomeRegistry;
    private final IdMap<Holder<Biome>> biomeIdMap;
    private final Map<String, Integer> blockIds = new ConcurrentHashMap<>();
    private final Map<String, Integer> biomeIds = new ConcurrentHashMap<>();
    private final Map<String, String> curated;
    private final int terminalBlockId;
    private final int fallbackBiomeId;
    private final AtomicLong fallbacks = new AtomicLong();

    ClientIdentityResolver(Registry<Biome> biomeRegistry, IdMap<Holder<Biome>> biomeIdMap) {
        this.biomeRegistry = biomeRegistry;
        this.biomeIdMap = biomeIdMap;
        this.curated = Map.copyOf(LSSClientConfig.CONFIG.crossVersionBlockFallbacks);
        this.terminalBlockId = resolveTerminalBlock(LSSClientConfig.CONFIG.unknownBlockFallback);
        this.fallbackBiomeId = resolveFallbackBiome();
    }

    /** Translates one v20 section-array body to the CLIENT's native layout (§2.3). */
    byte[] toNative(byte[] v20Body) {
        return V20ToNativeTranslator.translate(v20Body,
                this::blockIdFor, this::biomeIdFor,
                Block.BLOCK_STATE_REGISTRY.size(), this.biomeIdMap.size());
    }

    long fallbackCount() {
        return this.fallbacks.get();
    }

    /** Memo cap (review C1-3): identities are WIRE-CONTROLLED strings — a hostile or
     *  buggy server can mint unbounded distinct ones. Past the cap, resolution still
     *  works (correctness never depends on the memo), it just stops being cached. */
    private static final int MAX_MEMOIZED_IDENTITIES = 65_536;
    /** Global fallback-warn ceiling on top of the per-identity latch — the per-identity
     *  bound is itself wire-controlled, so it alone permits a log flood. */
    private static final int MAX_FALLBACK_WARNS = 256;

    int blockIdFor(String identity) {
        Integer hit = this.blockIds.get(identity);
        if (hit != null) {
            return hit;
        }
        // Resolve OUTSIDE any map lock (resolution may log); a racing duplicate resolve
        // is benign — both arrive at the same id.
        int id = resolveBlockId(identity);
        if (this.blockIds.size() < MAX_MEMOIZED_IDENTITIES) {
            this.blockIds.putIfAbsent(identity, id);
        }
        return id;
    }

    int biomeIdFor(String identity) {
        Integer hit = this.biomeIds.get(identity);
        if (hit != null) {
            return hit;
        }
        int id = resolveBiomeId(identity);
        if (this.biomeIds.size() < MAX_MEMOIZED_IDENTITIES) {
            this.biomeIds.putIfAbsent(identity, id);
        }
        return id;
    }

    private int resolveBlockId(String identity) {
        BlockState state = resolveExact(identity);
        if (state != null) {
            return Block.BLOCK_STATE_REGISTRY.getId(state);
        }
        // Rung 2: curated table keys on the NAME (properties dropped by design).
        String name = nameOf(identity);
        String mapped = name == null ? null : this.curated.get(name);
        if (mapped != null) {
            BlockState curatedState = resolveExact(mapped);
            if (curatedState != null) {
                noteFallback(identity, "curated -> " + mapped);
                return Block.BLOCK_STATE_REGISTRY.getId(curatedState);
            }
        }
        noteFallback(identity, "terminal default");
        return this.terminalBlockId;
    }

    /** Rung 1: exact name + best-effort properties. Null when the NAME is unknown. */
    private static BlockState resolveExact(String identity) {
        IdentityCodec.ParsedIdentity parsed;
        try {
            parsed = IdentityCodec.parse(identity);
        } catch (IllegalArgumentException e) {
            return null;  // malformed identity: let the ladder fall through
        }
        var rl = Identifier.tryParse(parsed.name());
        if (rl == null) {
            return null;
        }
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        if (block == null) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        var definition = block.getStateDefinition();
        for (var prop : parsed.properties()) {
            Property<?> property = definition.getProperty(prop.getKey());
            if (property == null) {
                continue;  // unknown property name: skipped, defaults kept
            }
            state = applyProperty(state, property, prop.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state,
                                                                      Property<T> property,
                                                                      String valueName) {
        return property.getValue(valueName)
                .map(v -> state.setValue(property, v))
                .orElse(state);  // unknown value: skipped, default kept
    }

    private int resolveBiomeId(String identity) {
        var rl = Identifier.tryParse(identity);
        if (rl != null) {
            var holder = this.biomeRegistry.get(rl).orElse(null);
            if (holder != null) {
                int id = this.biomeIdMap.getId(holder);
                if (id >= 0) {
                    return id;
                }
            }
        }
        noteFallback(identity, "biome -> minecraft:plains");
        return this.fallbackBiomeId;
    }

    private int resolveTerminalBlock(String configured) {
        BlockState state = configured == null ? null : resolveExact(configured);
        if (state == null || state.isAir()) {
            if (state != null) {
                LSSLogger.warn("unknownBlockFallback '" + configured
                        + "' resolves to air — air punches holes in distant terrain;"
                        + " using minecraft:stone");
            }
            state = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
        return Block.BLOCK_STATE_REGISTRY.getId(state);
    }

    private int resolveFallbackBiome() {
        var plains = this.biomeRegistry.get(Identifier.parse("minecraft:plains")).orElse(null);
        int id = plains == null ? -1 : this.biomeIdMap.getId(plains);
        // A modded/test registry without plains: any valid id beats a crash; 0 exists
        // in every non-empty registry.
        return id >= 0 ? id : 0;
    }

    private void noteFallback(String identity, String how) {
        long n = this.fallbacks.incrementAndGet();
        // The memo is the per-identity latch (at most one resolve per cached identity);
        // the global ceiling bounds what a hostile dictionary can flood past it.
        if (n <= MAX_FALLBACK_WARNS) {
            LSSLogger.warn("LOD identity '" + identity + "' not in this client's registries ("
                    + how + ") — a newer/modded server's content renders as its fallback");
            if (n == MAX_FALLBACK_WARNS) {
                LSSLogger.warn("Fallback warn ceiling reached (" + MAX_FALLBACK_WARNS
                        + ") — further identity fallbacks are counted but not logged");
            }
        }
    }

    private static String nameOf(String identity) {
        try {
            return IdentityCodec.parse(identity).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
