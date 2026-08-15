package dev.vox.lss.networking.server;

import dev.vox.lss.common.wire.V20ToNativeTranslator;
import dev.vox.lss.common.wire.WireSectionCursor;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §9's cross-line fixture corpus (C6), LINE-NEUTRAL WORDING since V-1/T4: every
 * checked-in {@code xver-live-corpus/} fixture — v20 bodies captured from REAL played
 * terrain on the capture line (the 26.2 mainline) by {@link XverLiveCorpusCaptureTool}
 * — must decode STRICTLY against the registries of the line this test RUNS ON: every
 * dictionary identity resolves and the translated native body re-parses with identical
 * section structure. On the capture line itself a miss means the capture and the
 * registry drifted (an MC bump without a corpus re-capture). On a support line a miss
 * is either registry drift on that line's own bump, or a re-captured corpus
 * introducing genuinely new mainline identities — only the latter may move to the
 * documented client fallback expectations ({@code unknownBlockFallback} / ladder
 * containment), never a silent widening of the strict resolvers. This is the only
 * automated coverage the actual issue-#85 scenario (cross-MC v20 serving) has
 * anywhere; the standing rule is that this corpus is NEVER regenerated on a support
 * line (decoding capture-line columns IS the cross-version claim).
 */
class XverLiveCorpusDecodeTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Map<String, Integer> blockIds;
    private static Map<String, Integer> biomeIds;
    private static int blockRegistrySize;
    private static int biomeRegistrySize;

    @BeforeAll
    static void setup() {
        blockIds = IdentityTables.blockIdsByIdentity();
        // The FULL vanilla biome lookup, exactly like the capture tool — the corpus
        // tests' minimal registry lacks live-terrain biomes (deep_dark, lush_caves…)
        // and a strict decode would red on its own line for the wrong reason.
        var provider = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var src = provider.lookupOrThrow(Registries.BIOME);
        var biomes = new net.minecraft.core.MappedRegistry<>(Registries.BIOME,
                com.mojang.serialization.Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(),
                net.minecraft.core.RegistrationInfo.BUILT_IN));
        biomes.freeze();
        var biomeTable = IdentityTables.biomeTable(biomes);
        biomeIds = biomeTable.idsByIdentity();
        blockRegistrySize = IdentityTables.blockIdentities().length;
        biomeRegistrySize = biomeTable.identities().length;
        biomeIdentityByIdCache = biomeTable.identities();
    }

    private static Path corpusDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources/xver-live-corpus");
            }
            Path nested = dir.resolve("fabric");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources/xver-live-corpus");
            }
        }
        throw new IllegalStateException("cannot locate the fabric module source tree");
    }

    private static List<Path> fixtures() throws Exception {
        var dir = corpusDir();
        assertTrue(Files.isDirectory(dir), "xver-live-corpus missing — run the capture tool"
                + " (XverLiveCorpusCaptureTool) and check the fixtures in");
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".bin")).sorted().forEach(out::add);
        }
        assertFalse(out.isEmpty(), "xver-live-corpus has no .bin fixtures");
        // Count pinned against the committed MANIFEST (pre-D3 review L3-22): the
        // non-empty check alone lets the corpus silently shrink to one fixture.
        long manifestEntries;
        try {
            manifestEntries = Files.readAllLines(dir.resolve("MANIFEST.txt")).stream()
                    .filter(l -> l.contains(".bin")).count();
        } catch (java.io.IOException e) {
            throw new AssertionError("xver-live-corpus MANIFEST.txt unreadable", e);
        }
        assertEquals(manifestEntries, out.size(),
                "fixture count must match MANIFEST.txt — a silently shrunk corpus decodes nothing");
        return out;
    }

    @Test
    void everyLiveFixtureDecodesStrictlyAgainstThisLinesRegistries() throws Exception {
        for (Path fixture : fixtures()) {
            byte[] v20 = Files.readAllBytes(fixture);
            var column = WireSectionCursor.parse(v20, WireSectionCursor.Layout.V20);
            assertTrue(column.dictionary().size() > 0, fixture + ": empty dictionary");

            // Strict resolvers, line-neutral (V-1/T4): every captured identity must
            // resolve on the line this test runs on — see the class javadoc for what a
            // miss means on the capture line vs a support line.
            byte[] nativeBody = V20ToNativeTranslator.translate(v20,
                    identity -> {
                        Integer id = blockIds.get(identity);
                        if (id == null) {
                            throw new AssertionError(fixture + ": block identity '"
                                    + identity + "' (captured on the 26.2 mainline) is "
                                    + "unknown on this line — see the class javadoc");
                        }
                        return id;
                    },
                    identity -> {
                        Integer id = biomeIds.get(identity);
                        if (id == null) {
                            throw new AssertionError(fixture + ": biome identity '"
                                    + identity + "' (captured on the 26.2 mainline) is "
                                    + "unknown on this line — see the class javadoc");
                        }
                        return id;
                    },
                    blockRegistrySize, biomeRegistrySize);

            var nativeColumn = WireSectionCursor.parse(nativeBody,
                    WireSectionCursor.Layout.NATIVE);
            assertEquals(column.sections().size(), nativeColumn.sections().size(),
                    fixture + ": section structure must survive the decode");

            // Identity-level content + count shorts (C6 review M-2): a packed-index
            // remap off-by-one produces the same section count and the same resolver
            // call set — only comparing the IDENTITY AT EVERY VOXEL catches it. The
            // v20 side reads identities through the dictionary; the native side maps
            // the decoded global ids back through this line's own tables.
            var dict = column.dictionary();
            String[] blockIdentities = IdentityTables.blockIdentities();
            for (int i = 0; i < column.sections().size(); i++) {
                var sv = column.sections().get(i);
                var sn = nativeColumn.sections().get(i);
                String at = fixture + " section " + i;
                assertEquals(sv.sectionY(), sn.sectionY(), at);
                // Derived from the S1 descriptor: this native body is CURSOR-emitted
                // (V20ToNativeTranslator), so a one-short line carries the LINE-level
                // cursor fold (review MAJOR-1 — not the per-family serializer fold,
                // which coincides on 1.21.11's fabric side but is a different field).
                assertEquals(dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                                ? sv.nonEmptyBlockCount()
                                : dev.vox.lss.common.wire.NativeSectionShape
                                        .foldedCountForNativeHeader(sv.nonEmptyBlockCount(),
                                                sv.fluidCount()),
                        sn.nonEmptyBlockCount(),
                        at + ": the native count header must match the family shape");
                assertEquals(dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                                ? sv.fluidCount() : 0,
                        sn.fluidCount(),
                        at + ": fluidCount survives only where the line carries it");
                int[] v20Blocks = resolvedValues(sv.blocks(), 4096);
                int[] nativeBlocks = resolvedValues(sn.blocks(), 4096);
                for (int v = 0; v < 4096; v++) {
                    String want = dict.get(v20Blocks[v]);
                    String got = blockIdentities[nativeBlocks[v]];
                    if (!want.equals(got)) {
                        throw new AssertionError(at + " voxel " + v + ": v20 identity '"
                                + want + "' decoded to '" + got + "'");
                    }
                }
                int[] v20Biomes = resolvedValues(sv.biomes(), 64);
                int[] nativeBiomes = resolvedValues(sn.biomes(), 64);
                for (int v = 0; v < 64; v++) {
                    String want = dict.get(v20Biomes[v]);
                    String got = biomeIdentityByIdCache[nativeBiomes[v]];
                    if (!want.equals(got)) {
                        throw new AssertionError(at + " biome voxel " + v + ": '"
                                + want + "' decoded to '" + got + "'");
                    }
                }
            }
        }
    }

    private static String[] biomeIdentityByIdCache;

    /** DIRECT-aware per-voxel resolve (the CrossRegistrySimulationTest helper's twin). */
    private static int[] resolvedValues(WireSectionCursor.WireContainer c, int entries) {
        if (c.bits() == 0) {
            int[] out = new int[entries];
            java.util.Arrays.fill(out, c.palette()[0]);
            return out;
        }
        int[] values = WireSectionCursor.unpack(c.data(), c.bits(), entries);
        if (c.palette() == null) {
            return values;
        }
        int[] out = new int[entries];
        for (int i = 0; i < entries; i++) {
            out[i] = c.palette()[values[i]];
        }
        return out;
    }

    // ---- M-3 (C6 follow-up, executed at D3): the drift arm ----

    @Test
    void driftedDictionaryDecodesViaTheFallbackLadderNotAnError() throws Exception {
        // The strict arm above proves same-line captures never fall back — but the
        // corpus exists for CROSS-line decode, where registry drift is the expected
        // state, and no fixture exercised it (M-3). Derive a drift body from a real
        // capture deterministically (swap one block identity for a valid-format
        // name no registry carries) and drive it through the CLIENT resolver ladder:
        // it must decode cleanly with the drifted voxels on the terminal fallback,
        // never throw, and count exactly one distinct fallback.
        Path base = fixtures().get(0);
        var column = WireSectionCursor.parse(Files.readAllBytes(base),
                WireSectionCursor.Layout.V20);
        int swapIdx = -1;
        for (int i = 0; i < column.dictionary().size(); i++) {
            if (blockIds.containsKey(column.dictionary().get(i))) {
                swapIdx = i;
                break;
            }
        }
        assertTrue(swapIdx >= 0, "premise: the capture has at least one block identity");
        var drifted = new java.util.ArrayList<>(column.dictionary());
        drifted.set(swapIdx, "lss_drift:absent_block[facing=north]");
        byte[] driftBody = WireSectionCursor.emit(
                new WireSectionCursor.WireColumn(drifted, column.sections()),
                WireSectionCursor.Layout.V20);

        long[] fallbacks = {0};
        byte[] nativeBody = V20ToNativeTranslator.translate(driftBody,
                identity -> {
                    Integer id = blockIds.get(identity);
                    if (id == null) {
                        fallbacks[0]++;
                        return blockIds.get(
                                "minecraft:stone"); // the terminal ladder's direction
                    }
                    return id;
                },
                identity -> biomeIds.getOrDefault(identity, 0),
                blockRegistrySize, biomeRegistrySize);
        assertEquals(1, fallbacks[0],
                "exactly the one drifted identity falls back (memoized per palette entry)");
        var nativeColumn = WireSectionCursor.parse(nativeBody,
                WireSectionCursor.Layout.NATIVE);
        assertEquals(column.sections().size(), nativeColumn.sections().size(),
                "a drifted dictionary must never cost sections — only substituted voxels");
    }
}
