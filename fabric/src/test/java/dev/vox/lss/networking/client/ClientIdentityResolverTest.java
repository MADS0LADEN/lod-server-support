package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The client's §3 unknown-identity fallback ladder ({@link ClientIdentityResolver}),
 * rung by rung against the REAL 26.2 block registry (XVER plan §9: "identity resolve
 * through a real registry", "fallback content assertions, per-session memo + bounded
 * warn"). This ladder is the entire cross-version safety story on the receive side —
 * a v20 identity from a newer/modded server resolves here or renders as the fallback
 * — and none of its rungs are reachable by the corpus goldens (same-version by
 * construction, every identity known), so each rung needs its own pin:
 * <ul>
 * <li><b>Exact</b> incl. property application — the rung every same-version voxel
 *     takes; a drift here corrupts ALL terrain, not just unknown blocks.</li>
 * <li><b>Props-dropped</b> — the rung is deliberately NOT a fallback: unknown
 *     property names/values are skipped inside the exact resolve (a newer server's
 *     added blockstate property must not demote the whole block to stone, and must
 *     not inflate the fallback diagnostics).</li>
 * <li><b>Curated table</b> — {@code crossVersionBlockFallbacks}, the ViaBackwards-diff
 *     style rename map; keys on the NAME with wire properties dropped by design.</li>
 * <li><b>Terminal</b> — {@code unknownBlockFallback}, never air (air punches holes in
 *     distant terrain), with the config-hardening coercions.</li>
 * </ul>
 * plus the memo/count discipline the diag surface reads and the warn-flood bound.
 *
 * <p>Registry bootstrap follows the {@code ClientColumnProcessorTest} pattern
 * (fabric-loader-junit + {@code Bootstrap.bootStrap()}); the biome registry carries
 * TWO entries so an exact desert resolve is distinguishable from the plains fallback.
 */
class ClientIdentityResolverTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MappedRegistry<Biome> BIOME_REGISTRY;
    private static IdMap<Holder<Biome>> BIOME_ID_MAP;

    @BeforeAll
    static void setup() {
        var src = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        var biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, src.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.register(Biomes.DESERT, src.getOrThrow(Biomes.DESERT).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        BIOME_REGISTRY = biomes;
        // The SAME map production hands the resolver (ClientColumnProcessor.resolverFor):
        // the factory strategy's global holder map, never bare Registry ids.
        BIOME_ID_MAP = PalettedContainerFactory
                .create(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)))
                .biomeStrategy().globalMap();
    }

    // The resolver snapshots LSSClientConfig.CONFIG in its constructor. Pin the two
    // fields to known values per test (the fabric-loader-junit run dir can carry a
    // real config file with local edits) and restore the user's values afterwards.
    private String priorFallback;
    private Map<String, String> priorCurated;

    @BeforeEach
    void pinConfig() {
        priorFallback = LSSClientConfig.CONFIG.unknownBlockFallback;
        priorCurated = LSSClientConfig.CONFIG.crossVersionBlockFallbacks;
        LSSClientConfig.CONFIG.unknownBlockFallback = "minecraft:stone";
        LSSClientConfig.CONFIG.crossVersionBlockFallbacks = new HashMap<>();
    }

    @AfterEach
    void restoreConfig() {
        LSSClientConfig.CONFIG.unknownBlockFallback = priorFallback;
        LSSClientConfig.CONFIG.crossVersionBlockFallbacks = priorCurated;
    }

    private static ClientIdentityResolver newResolver() {
        return new ClientIdentityResolver(BIOME_REGISTRY, BIOME_ID_MAP);
    }

    private static int idOf(BlockState state) {
        return Block.BLOCK_STATE_REGISTRY.getId(state);
    }

    private static int stoneId() {
        return idOf(Blocks.STONE.defaultBlockState());
    }

    private static int biomeIdOf(Identifier id) {
        return BIOME_ID_MAP.getId(BIOME_REGISTRY.get(id).orElseThrow());
    }

    // ---- rung 1: exact ----------------------------------------------------------

    @Test
    void exactIdentityResolvesToTheExactBlockStateIncludingProperties() {
        var r = newResolver();
        var expected = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        // Every property value is NON-default (facing=east vs north, half=top vs
        // bottom, waterlogged=true vs false) so a resolve that silently kept defaults
        // cannot pass by coincidence.
        assertEquals(idOf(expected),
                r.blockIdFor("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true]"));
        assertEquals(idOf(Blocks.STONE.defaultBlockState()), r.blockIdFor("minecraft:stone"));
        assertEquals(0, r.fallbackCount(), "exact resolves are not fallbacks");
    }

    @Test
    void identityWithMissingPropertiesKeepsTheDefaultsForThose() {
        // A canonical identity carries ALL properties, but the resolve algorithm is
        // best-effort by design: an older server's identity that lacks a property this
        // client's version added must still land on the right block with the new
        // property at its default.
        var r = newResolver();
        assertEquals(idOf(Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HALF, Half.TOP)),
                r.blockIdFor("minecraft:oak_stairs[half=top]"));
        assertEquals(0, r.fallbackCount());
    }

    // ---- the props-dropped rung (inside exact, deliberately not a fallback) ------

    @Test
    void bogusPropertyValueIsSkippedAndTheRestStillApply() {
        // "facing=upwards" is charset-legal but no stairs facing — Property.getValue
        // returns empty and the default is KEPT, while the valid half=top in the same
        // identity still applies. The name resolved, so this is rung 1 succeeding,
        // NOT a fallback: fallbackCount() must stay 0 or a newer server's extended
        // property VALUES would flood the diag counter for correctly-rendered blocks.
        var r = newResolver();
        assertEquals(idOf(Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HALF, Half.TOP)),
                r.blockIdFor("minecraft:oak_stairs[facing=upwards,half=top]"));
        assertEquals(0, r.fallbackCount());
    }

    @Test
    void unknownPropertyNameIsSkippedAndTheRestStillApply() {
        // Same rung, other shape: a property NAME this version's block does not define
        // (a newer server added one) is skipped via definition.getProperty == null.
        var r = newResolver();
        assertEquals(idOf(Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HALF, Half.TOP)),
                r.blockIdFor("minecraft:oak_stairs[glitter=on,half=top]"));
        assertEquals(0, r.fallbackCount());
    }

    // ---- rung 2: the curated table ------------------------------------------------

    @Test
    void curatedTableMapsUnknownNamesAndCountsAsFallback() {
        LSSClientConfig.CONFIG.crossVersionBlockFallbacks = new HashMap<>(Map.of(
                "ancient:sulfur", "minecraft:sandstone",
                "ancient:ruby_stairs", "minecraft:oak_stairs[half=top]"));
        var r = newResolver();
        // The curated rung keys on the NAME — the wire identity's properties are
        // dropped by design (they belong to the REMOVED block's state space).
        assertEquals(idOf(Blocks.SANDSTONE.defaultBlockState()),
                r.blockIdFor("ancient:sulfur[age=3]"));
        assertEquals(1, r.fallbackCount(), "a curated mapping IS a fallback (diag-visible)");
        // A curated TARGET may itself carry properties; it resolves through rung 1.
        assertEquals(idOf(Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HALF, Half.TOP)),
                r.blockIdFor("ancient:ruby_stairs"));
        assertEquals(2, r.fallbackCount());
    }

    @Test
    void curatedEntryPointingAtAnUnknownTargetFallsThroughToTerminal() {
        // A stale curated entry (its target renamed away too) must not wedge the
        // ladder: the resolve continues to the terminal default.
        LSSClientConfig.CONFIG.crossVersionBlockFallbacks =
                new HashMap<>(Map.of("ancient:junk", "ancient:also_missing"));
        var r = newResolver();
        assertEquals(stoneId(), r.blockIdFor("ancient:junk"));
        assertEquals(1, r.fallbackCount());
    }

    // ---- rung 3: the terminal default ---------------------------------------------

    @Test
    void fullyUnknownIdentityResolvesToTheTerminalDefault() {
        var r = newResolver();
        assertEquals(stoneId(), r.blockIdFor("voidcraft:aetherium_ore"));
        assertEquals(1, r.fallbackCount());
    }

    @Test
    void configuredTerminalFallbackIsHonoredWhenItResolves() {
        LSSClientConfig.CONFIG.unknownBlockFallback = "minecraft:sandstone";
        var r = newResolver();
        assertEquals(idOf(Blocks.SANDSTONE.defaultBlockState()),
                r.blockIdFor("voidcraft:aetherium_ore"));
    }

    @Test
    void unparseableOrUnknownTerminalFallbackCoercesToStone() {
        // resolveTerminalBlock hardening, both null-state shapes: a value the identity
        // grammar rejects outright, and one that parses but names no local block. Both
        // must coerce to stone at CONSTRUCTION time — a resolver with no valid
        // terminal id would turn every unknown identity into a throw mid-decode.
        LSSClientConfig.CONFIG.unknownBlockFallback = "Not A Block!!";
        assertEquals(stoneId(), newResolver().blockIdFor("voidcraft:aetherium_ore"));

        LSSClientConfig.CONFIG.unknownBlockFallback = "minecraft:no_such_block_here";
        assertEquals(stoneId(), newResolver().blockIdFor("voidcraft:aetherium_ore"));
    }

    @Test
    void airResolvingTerminalFallbackIsCoercedToStone() {
        // The NEVER-air pin (the ViaVersion air default is the wrong answer for LOD:
        // air punches holes in distant terrain) — a configured fallback that RESOLVES
        // but to an air state takes the warn-and-coerce branch.
        LSSClientConfig.CONFIG.unknownBlockFallback = "minecraft:air";
        assertEquals(stoneId(), newResolver().blockIdFor("voidcraft:aetherium_ore"));
    }

    // ---- memo + counter discipline --------------------------------------------------

    @Test
    void fallbackCountIncrementsExactlyOncePerDistinctUnknownIdentity() {
        // The memo is the per-identity latch: re-serves of the same unknown identity
        // (every column of a biome, potentially thousands) must cost ONE diag count
        // and one resolve — the counter feeds the client trace/diag as "distinct
        // unknown identities", not "voxels rendered as fallback".
        var r = newResolver();
        assertEquals(stoneId(), r.blockIdFor("gone:one"));
        assertEquals(stoneId(), r.blockIdFor("gone:one"));
        assertEquals(stoneId(), r.blockIdFor("gone:one"));
        assertEquals(1, r.fallbackCount());
        r.blockIdFor("gone:two");
        assertEquals(2, r.fallbackCount());
        r.blockIdFor("minecraft:stone"); // a known identity never counts
        assertEquals(2, r.fallbackCount());
    }

    /**
     * The warn-flood bound (review C1-3 family): identities are WIRE-CONTROLLED
     * strings, so a hostile or buggy server can mint unbounded distinct unknowns.
     * Past {@code MAX_FALLBACK_WARNS} (256) the class stops LOGGING but must keep
     * resolving and keep COUNTING — the warns themselves go through LSSLogger/SLF4J
     * and are not cheaply countable in-JVM, so the exposed contract pinned here is
     * the counter's half: 300 distinct unknowns all resolve to the terminal id
     * without a throw and every one is counted, i.e. the ceiling silences the log
     * channel only. A regression that made the ceiling throw, stop resolving, or
     * stop counting reds this test.
     */
    @Test
    void fallbackWarnCeilingSilencesLoggingButNeverCountingOrResolution() {
        var r = newResolver();
        int terminal = stoneId();
        for (int i = 0; i < 300; i++) {
            assertEquals(terminal, r.blockIdFor("gone:unknown_" + i), "identity " + i);
        }
        assertEquals(300, r.fallbackCount(),
                "every distinct unknown past the warn ceiling must still be counted");
    }

    // ---- the biome ladder (exact + terminal; biomes have no curated/props rungs) ----

    @Test
    void biomeIdentityResolvesExactlyAndUnknownFallsBackToPlains() {
        var r = newResolver();
        assertEquals(biomeIdOf(Identifier.parse("minecraft:desert")),
                r.biomeIdFor("minecraft:desert"));
        assertEquals(0, r.fallbackCount(), "an exact biome resolve is not a fallback");

        int plains = biomeIdOf(Identifier.parse("minecraft:plains"));
        assertEquals(plains, r.biomeIdFor("hills:unknown_biome"));
        assertEquals(1, r.fallbackCount());
        // Memoized like blocks: the repeat costs no second count.
        assertEquals(plains, r.biomeIdFor("hills:unknown_biome"));
        assertEquals(1, r.fallbackCount());
    }

    // ---- M-4/M-5 (C6 follow-ups, executed at D3): toNative whole-body cases ----

    @Test
    void toNativeTranslatesAWholeBodyThroughTheResolverLadder() {
        // M-4: the ladder pins above drive blockIdFor directly; nothing drove the
        // FULL §2.3 translation (dictionary walk -> per-voxel resolve -> native
        // repack + count/light pass-through). A wiring slip between resolver and
        // translator (wrong lookup bound, swapped block/biome fns) passes every
        // per-rung pin and corrupts every real column.
        LSSClientConfig.CONFIG.unknownBlockFallback = "minecraft:stone";
        LSSClientConfig.CONFIG.crossVersionBlockFallbacks = new HashMap<>();
        var r = newResolver();
        String stoneId = dev.vox.lss.networking.server.IdentityTables.blockIdentityFor(
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()));
        int[] voxels = new int[4096];
        for (int i = 2048; i < 4096; i++) voxels[i] = 1; // half known, half unknown
        byte[] blockLight = new byte[2048];
        blockLight[0] = 0x3C;
        byte[] v20 = dev.vox.lss.common.wire.WireSectionCursor.emit(
                new dev.vox.lss.common.wire.WireSectionCursor.WireColumn(
                        List.of(stoneId, "lss_drift:absent_block", "minecraft:plains"),
                        List.of(new dev.vox.lss.common.wire.WireSectionCursor.WireSection(
                                0, 4096, 0,
                                new dev.vox.lss.common.wire.WireSectionCursor.WireContainer(
                                        4, new int[]{0, 1},
                                        dev.vox.lss.common.wire.WireSectionCursor.pack(voxels, 4)),
                                new dev.vox.lss.common.wire.WireSectionCursor.WireContainer(
                                        0, new int[]{2}, new long[0]),
                                blockLight, null))),
                dev.vox.lss.common.wire.WireSectionCursor.Layout.V20);

        byte[] nativeBody = r.toNative(v20);

        var col = dev.vox.lss.common.wire.WireSectionCursor.parse(nativeBody,
                dev.vox.lss.common.wire.WireSectionCursor.Layout.NATIVE);
        var s = col.sections().get(0);
        assertEquals(4096, s.nonEmptyBlockCount(), "count shorts pass through untouched");
        int stoneNative = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        // Known stone AND the unknown->stone fallback resolve to the SAME native id,
        // so the repack collapses to the single-value tier — itself a strong pin:
        // both voxel classes provably landed on stone, and duplicate canonicalization
        // (the translator's content-identity contract) worked through the resolver.
        assertEquals(0, s.blocks().bits(), "two same-id entries collapse to single-value");
        assertEquals(stoneNative, s.blocks().palette()[0],
                "known resolves exactly AND the unknown lands on the terminal fallback");
        assertEquals(1, r.fallbackCount(), "one distinct unknown identity, one count");
        org.junit.jupiter.api.Assertions.assertArrayEquals(blockLight, s.blockLight(),
                "light layers pass through the whole-body translation verbatim");
    }

    @Test
    void toNativeDirectWidthDerivesFromTheClientRegistrySize() {
        // M-5 (registry-size drift): the native DIRECT repack width must come from
        // THIS client's registry size — a server with a different-size registry
        // (older/newer line, mods) changes nothing here but the ids resolved into
        // the words. Pin: a >256-identity dictionary forces the native direct tier
        // at ceillog2(client Block.BLOCK_STATE_REGISTRY.size()) — if a size drift
        // ever changed the width derivation, the packed words would desync at parse.
        LSSClientConfig.CONFIG.unknownBlockFallback = "minecraft:stone";
        LSSClientConfig.CONFIG.crossVersionBlockFallbacks = new HashMap<>();
        var r = newResolver();
        var dict = new java.util.ArrayList<String>();
        int taken = 0;
        String[] table = dev.vox.lss.networking.server.IdentityTables.blockIdentities();
        for (int id = 0; id < table.length && taken < 300; id++) {
            if (table[id] != null) {
                dict.add(table[id]);
                taken++;
            }
        }
        assertEquals(300, taken, "premise: 300 real identities from the client registry");
        int[] voxels = new int[4096];
        for (int i = 0; i < 4096; i++) voxels[i] = i % 300;
        int[] wirePalette = new int[300];
        for (int i = 0; i < 300; i++) wirePalette[i] = i;
        byte[] v20 = dev.vox.lss.common.wire.WireSectionCursor.emit(
                new dev.vox.lss.common.wire.WireSectionCursor.WireColumn(
                        java.util.stream.Stream.concat(dict.stream(),
                                java.util.stream.Stream.of("minecraft:plains")).toList(),
                        List.of(new dev.vox.lss.common.wire.WireSectionCursor.WireSection(
                                0, 4096, 0,
                                new dev.vox.lss.common.wire.WireSectionCursor.WireContainer(
                                        9, wirePalette,
                                        dev.vox.lss.common.wire.WireSectionCursor.pack(voxels, 9)),
                                new dev.vox.lss.common.wire.WireSectionCursor.WireContainer(
                                        0, new int[]{300}, new long[0]),
                                null, null))),
                dev.vox.lss.common.wire.WireSectionCursor.Layout.V20);

        var s = dev.vox.lss.common.wire.WireSectionCursor.parse(r.toNative(v20),
                dev.vox.lss.common.wire.WireSectionCursor.Layout.NATIVE).sections().get(0);
        assertEquals(true, s.blocks().isDirect(),
                ">256 distinct states must repack as the native DIRECT tier");
        int wantBits = 32 - Integer.numberOfLeadingZeros(Block.BLOCK_STATE_REGISTRY.size() - 1);
        assertEquals(wantBits, s.blocks().bits(),
                "direct width = ceillog2 of the CLIENT registry size — the drift-safe derivation");
        assertEquals(0, r.fallbackCount(), "every identity was real — no fallbacks");
    }
}
