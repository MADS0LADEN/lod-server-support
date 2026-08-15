package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V-2/S2 (version-port-isolation-plan.md §3): {@code LevelChunkSection} CONSTRUCTION is
 * seamed — {@code dev.vox.lss.platform.SectionConstruction} (xplat/fabric/neoforge
 * family) and {@code PaperSectionConstruction} (paper) are the only main-source ctor
 * sites. The ctor family is per-MC-line churn (26.x {@code PalettedContainerFactory} vs
 * the 1.21.x {@code Registry<Biome>} family — 4+ files rewritten per port before the
 * seam), and the 1.21.x lines additionally must never touch the DESERIALIZATION ctor
 * (AntiXray 1.4.x's non-null-safe mixin wraps it, uncovered by any tier). A new
 * construction site outside the helpers re-opens both: route it through the seam.
 *
 * <p>Array creation ({@code new LevelChunkSection[n]}) is not construction and passes.
 * Tests construct sections freely — main sources only.
 */
class SectionConstructionPinTest {

    private static final java.util.regex.Pattern CTOR = java.util.regex.Pattern.compile(
            // FQN-tolerant (V-2 review MINOR-5: FQN invocation is active house style in
            // exactly these files); "(" not "[" so array creation passes.
            "new\\s+(?:net\\.minecraft\\.world\\.level\\.chunk\\.)?LevelChunkSection\\s*\\(");
    private static final List<String> MAIN_ROOTS = List.of(
            "xplat/src/main/java", "fabric/src/main/java",
            "paper/src/main/java", "neoforge/src/main/java",
            "common/src/main/java");
    private static final List<String> HELPERS = List.of(
            "dev/vox/lss/platform/SectionConstruction.java",
            "dev/vox/lss/paper/PaperSectionConstruction.java");

    @Test
    void onlyTheSeamHelpersConstructSections() throws IOException {
        Path root = repoRoot();
        var offenders = new ArrayList<String>();
        boolean[] helpersSeen = new boolean[HELPERS.size()];
        for (String rootDir : MAIN_ROOTS) {
            Path dir = root.resolve(rootDir);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    String source;
                    try {
                        source = Files.readString(p);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    int idx = HELPERS.indexOf(rel);
                    if (idx >= 0) {
                        helpersSeen[idx] = CTOR.matcher(source).find();
                        return;
                    }
                    if (CTOR.matcher(source).find()) {
                        offenders.add(rootDir + "/" + rel);
                    }
                });
            }
        }
        assertTrue(offenders.isEmpty(),
                "LevelChunkSection construction outside the S2 seam — route it through "
                        + "SectionConstruction/PaperSectionConstruction (per-MC-line ctor "
                        + "churn + the 1.21.x AntiXray deserialization-ctor trap): "
                        + offenders);
        for (int i = 0; i < HELPERS.size(); i++) {
            assertTrue(helpersSeen[i], HELPERS.get(i) + " must exist and construct sections "
                    + "— moved/renamed? update this pin WITH the port runbook");
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("xplat/src/main/java"))) return dir;
        }
        throw new IllegalStateException("cannot locate the repo root");
    }
}
