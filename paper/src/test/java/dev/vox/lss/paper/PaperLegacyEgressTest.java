package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.common.wire.WireFormatException;
import dev.vox.lss.common.wire.WireSectionCursor;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper twin of the Fabric {@code LegacyColumnEgressTest}: the C2 legacy egress body
 * translation ({@code PaperOffThreadProcessor.buildLegacyColumn} over
 * {@code PaperNbtSectionSerializer.fromV20} — XVER plan §4.2/§9) at the per-recipient
 * ENQUEUE choke point. The corpus-driven translation chain (fromV20(v20 golden) must
 * byte-equal the frozen native golden through the production registry tables, one
 * deliberate palette-collapse carve-out), the codec choice (v19 recompress
 * shrink-gated), splice composition over translated frames, and the loud failure
 * shape the enqueue containment catches.
 */
class PaperLegacyEgressTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegistryAccess REGISTRY_ACCESS;

    @BeforeAll
    static void setup() {
        REGISTRY_ACCESS = CorpusRegistryAccess.build();
    }

    // ---- fixtures ----

    private static Path corpusDir(String dirName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources").resolve(dirName);
            }
            Path nested = dir.resolve("paper");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources").resolve(dirName);
            }
        }
        throw new IllegalStateException("cannot locate the paper module source tree from "
                + Path.of("").toAbsolutePath());
    }

    private static byte[] readCorpus(String dirName, String name) {
        try {
            return Files.readAllBytes(corpusDir(dirName).resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> corpusNames() throws IOException {
        try (Stream<Path> files = Files.list(corpusDir("nbt-corpus"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".bin")).sorted().toList();
        }
    }

    // ---- the §9 translation chain over the committed corpus ----

    /** Same deliberate divergence as the Fabric twin: the fixture's duplicate palette
     *  entries collapse through the identity dictionary; content pinned below. */
    private static final String COLLAPSED_FIXTURE = "duplicate-air.bin";

    @Test
    void everyCorpusGoldenTranslatesBackToItsExactNativeBytes() throws IOException {
        var names = corpusNames();
        assertTrue(names.size() >= 14, "premise: the committed corpus has at least 14 fixtures, found " + names);
        assertTrue(names.contains(COLLAPSED_FIXTURE),
                "premise: the collapse carve-out below still names a committed fixture");
        for (String name : names) {
            if (name.equals(COLLAPSED_FIXTURE)) continue;
            byte[] nativeGolden = readCorpus("nbt-corpus", name);
            byte[] v20Golden = readCorpus("v20-corpus", name);
            assertArrayEquals(nativeGolden,
                    PaperNbtSectionSerializer.fromV20(v20Golden, REGISTRY_ACCESS),
                    "fromV20 must reproduce the frozen native bytes through the "
                            + "production registry tables for " + name);
        }
    }

    @Test
    void duplicatePaletteFixtureCollapsesToContentIdenticalBytes() {
        byte[] nativeGolden = readCorpus("nbt-corpus", COLLAPSED_FIXTURE);
        byte[] translated = PaperNbtSectionSerializer.fromV20(
                readCorpus("v20-corpus", COLLAPSED_FIXTURE), REGISTRY_ACCESS);
        assertFalse(java.util.Arrays.equals(nativeGolden, translated),
                "premise: the collapse actually diverges — if this starts passing "
                        + "byte-equal, fold the fixture back into the chain test");

        var expected = WireSectionCursor.parse(nativeGolden, WireSectionCursor.Layout.NATIVE);
        var actual = WireSectionCursor.parse(translated, WireSectionCursor.Layout.NATIVE);
        assertEquals(expected.sections().size(), actual.sections().size());
        for (int i = 0; i < expected.sections().size(); i++) {
            var e = expected.sections().get(i);
            var a = actual.sections().get(i);
            assertEquals(e.sectionY(), a.sectionY());
            assertEquals(e.nonEmptyBlockCount(), a.nonEmptyBlockCount());
            assertEquals(e.fluidCount(), a.fluidCount());
            assertArrayEquals(resolvedValues(e.blocks(), 4096), resolvedValues(a.blocks(), 4096),
                    "per-entry block ids must survive the palette collapse (section " + i + ")");
            assertArrayEquals(resolvedValues(e.biomes(), 64), resolvedValues(a.biomes(), 64),
                    "per-entry biome ids must survive the palette collapse (section " + i + ")");
            assertArrayEquals(e.blockLight(), a.blockLight());
            assertArrayEquals(e.skyLight(), a.skyLight());
        }
    }

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

    // ---- the per-recipient build (codec choice + re-derived rawSize) ----

    @Test
    void rawBuildTranslatesInPlaceAndRederivesRawSize() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-section.bin");

        var build = PaperOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, false, null);

        assertArrayEquals(nativeGolden, build.shipped());
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, build.codecTag());
        assertEquals(nativeGolden.length, build.rawSize(),
                "rawSize must be re-derived from the TRANSLATED body — the legacy client's "
                        + "charge rule (and law A2's server book) read the bytes it receives");
    }

    @Test
    void compressedSessionBuildRecompressesTheNativeBody() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd, "the zstd natives ship on the test classpath (the store suite "
                + "requires them) — a null here is an environment regression, not a skip");
        byte[] v20 = readCorpus("v20-corpus", "multi-palette.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-palette.bin");

        var build = PaperOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, true, zstd);

        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, build.codecTag(),
                "a v19 session keeps its compression capability — the recompress must fire");
        assertEquals(nativeGolden.length, build.rawSize());
        assertArrayEquals(nativeGolden, zstd.decompress(build.shipped(), build.rawSize()),
                "the recompressed frame must decompress to the exact native bytes");
    }

    @Test
    void tinyBodyShipsRawUnderTheShrinkGate() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd);
        var build = PaperOffThreadProcessor.buildLegacyColumn(
                new byte[] {0, 0}, REGISTRY_ACCESS, true, zstd);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, build.codecTag());
        assertArrayEquals(new byte[] {0}, build.shipped(),
                "the ghost-clear column must translate to the native single-byte clear");
        assertEquals(1, build.rawSize());
    }

    // ---- splice composition over translated frames ----

    @Test
    void v18AndV16SplicesOverTheTranslatedFrameEqualTheNativeBuiltRewrites() {
        byte[] nativeBody = PaperNbtSectionSerializer.fromV20(
                readCorpus("v20-corpus", "waterlogged.bin"), REGISTRY_ACCESS);
        byte[] nativeGolden = readCorpus("nbt-corpus", "waterlogged.bin");
        byte[] translatedFrame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(7, -3,
                "minecraft:overworld", 1234567L, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_CODEC_RAW, nativeBody);
        byte[] nativeFrame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(7, -3,
                "minecraft:overworld", 1234567L, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_CODEC_RAW, nativeGolden);

        assertArrayEquals(PaperPayloadHandler.rewriteColumnToV18(nativeFrame),
                PaperPayloadHandler.rewriteColumnToV18(translatedFrame),
                "the v18 splice must compose on the translated frame");
        assertArrayEquals(PaperPayloadHandler.rewriteColumnToV16(nativeFrame),
                PaperPayloadHandler.rewriteColumnToV16(translatedFrame),
                "the v16 splice must compose on the translated frame");
    }

    // ---- the loud failure shape (contained at the enqueue as an up_to_date answer) ----

    @Test
    void unresolvableIdentityThrowsTheTranslatorsPinnedFailure() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        assertThrows(WireFormatException.class,
                () -> dev.vox.lss.common.wire.V20ToNativeTranslator.translate(v20,
                        identity -> -1,
                        PaperNbtSectionSerializer.biomeIdLookup(REGISTRY_ACCESS),
                        Block.BLOCK_STATE_REGISTRY.size(),
                        PaperNbtSectionSerializer.biomeIdCount(REGISTRY_ACCESS)),
                "an identity missing from the server's own registry is a table bug and "
                        + "must fail loudly (a WireFormatException the enqueue containment "
                        + "resolves as up_to_date), never serve wrong blocks");
    }
}
