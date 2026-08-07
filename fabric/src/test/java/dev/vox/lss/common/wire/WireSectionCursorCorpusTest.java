package dev.vox.lss.common.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cursor's NATIVE-layout knowledge, proven against the REAL emitter's output:
 * every committed {@code nbt-corpus} golden (raw {@code serializeChunkNbt} bodies —
 * incl. DIRECT/global palettes, negative Y, waterlogged, light combos, masked) must
 * scan to its exact length and survive parse → emit byte-identical. A cursor that
 * misread any layout detail (palette thresholds, implied long counts, light framing)
 * cannot pass this against all fourteen shapes.
 */
class WireSectionCursorCorpusTest {

    private static Path corpusDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources/nbt-corpus");
            }
            Path nested = dir.resolve("fabric");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources/nbt-corpus");
            }
        }
        throw new IllegalStateException("cannot locate the fabric module source tree from "
                + Path.of("").toAbsolutePath());
    }

    private static List<Path> corpusFiles() throws IOException {
        try (Stream<Path> files = Files.list(corpusDir())) {
            List<Path> bins = files.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .sorted().toList();
            assertFalse(bins.isEmpty(), "corpus dir has no .bin goldens");
            return bins;
        }
    }

    @Test
    void everyCorpusGoldenScansToItsExactLength() throws IOException {
        for (Path bin : corpusFiles()) {
            byte[] body = Files.readAllBytes(bin);
            assertEquals(body.length,
                    WireSectionCursor.scan(body, 0, WireSectionCursor.Layout.NATIVE),
                    bin.getFileName() + " must scan cleanly to its end");
        }
    }

    @Test
    void everyCorpusGoldenSurvivesParseEmitByteIdentical() throws IOException {
        boolean sawDirect = false;
        for (Path bin : corpusFiles()) {
            byte[] body = Files.readAllBytes(bin);
            var column = WireSectionCursor.parse(body, WireSectionCursor.Layout.NATIVE);
            assertArrayEquals(body,
                    WireSectionCursor.emit(column, WireSectionCursor.Layout.NATIVE),
                    bin.getFileName() + " must re-emit byte-identical");
            sawDirect |= column.sections().stream().anyMatch(
                    s -> s.blocks().isDirect() || s.biomes().isDirect());
        }
        assertTrue(sawDirect, "corpus must exercise at least one DIRECT container "
                + "(global-palette / biome-global goldens) or this suite lost its teeth");
    }
}
