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
 * The 26.2 side of §9's cross-line fixture corpus (C6): every checked-in
 * {@code xver-live-corpus/} fixture — v20 bodies captured from REAL played terrain by
 * {@link XverLiveCorpusCaptureTool} — must decode STRICTLY against this line's own
 * registries: every dictionary identity resolves (zero fallbacks on the same line),
 * and the translated native body re-parses with identical section structure. The
 * 26.1 / 1.21.11 backport suites (D3 phase 7) run the same fixtures against THEIR
 * registries with per-line fallback expectations — the only automated coverage the
 * actual issue-#85 scenario (cross-MC v20 serving) has anywhere.
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
        return out;
    }

    @Test
    void everyLiveFixtureDecodesStrictlyAgainstThisLinesRegistries() throws Exception {
        for (Path fixture : fixtures()) {
            byte[] v20 = Files.readAllBytes(fixture);
            var column = WireSectionCursor.parse(v20, WireSectionCursor.Layout.V20);
            assertTrue(column.dictionary().size() > 0, fixture + ": empty dictionary");

            // Strict resolvers: on the SAME line every captured identity must be known
            // — a miss here means the capture and the registry drifted (an MC bump
            // without a corpus re-capture), never a legitimate fallback.
            byte[] nativeBody = V20ToNativeTranslator.translate(v20,
                    identity -> {
                        Integer id = blockIds.get(identity);
                        if (id == null) {
                            throw new AssertionError(fixture + ": block identity '"
                                    + identity + "' unknown on its OWN line");
                        }
                        return id;
                    },
                    identity -> {
                        Integer id = biomeIds.get(identity);
                        if (id == null) {
                            throw new AssertionError(fixture + ": biome identity '"
                                    + identity + "' unknown on its OWN line");
                        }
                        return id;
                    },
                    blockRegistrySize, biomeRegistrySize);

            var nativeColumn = WireSectionCursor.parse(nativeBody,
                    WireSectionCursor.Layout.NATIVE);
            assertEquals(column.sections().size(), nativeColumn.sections().size(),
                    fixture + ": section structure must survive the decode");
        }
    }
}
