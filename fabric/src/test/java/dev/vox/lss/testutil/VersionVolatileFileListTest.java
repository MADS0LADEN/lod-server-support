package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V-1/S6 (version-port-isolation-plan.md): the VERSION-VOLATILE FILE LIST — files whose
 * bodies are rewritten per MC line (a vanilla rework, not a loader difference) must stay
 * in the PER-LOADER trees so a support branch replaces the whole file, merge-conflict-free,
 * instead of patching shared xplat code.
 *
 * <p>This is a REGRESSION GUARD, not a savings item (V-1 review): the naive violation —
 * a same-FQN file in xplat AND a loader tree — is already a duplicate-class compile
 * error, and {@code release_check.py}'s cross-loader presence check is satisfied by a
 * single shared class. The legal-looking refactor this pin exists to red is: move the
 * file into xplat and DELETE the loader twins. That compiles, ships, and quietly turns
 * the next support port's whole-file replacement into a shared-file patch on every line.
 *
 * <p>Current list (grow it with a sentence of rationale per entry):
 * <ul>
 *   <li>{@code FarPlayerRenderer} — the 26.x extract/submit render API vs 1.21.x
 *       immediate rendering: a per-MC-line rework on top of the per-loader twin.</li>
 *   <li>{@code ChunkSaveDataHook} — the mixin target class itself is per-line
 *       ({@code SerializableChunkData.copyOf} exists only 1.21.2+; older lines hook
 *       {@code ChunkSerializer.write}).</li>
 *   <li>{@code ScopedCarrier} (V-2/S5) — {@code ScopedValue} is Java-25-only API; the
 *       Java-21 lines replace the file with a pass-through. Its extraction is what
 *       EMPTIES {@code XplatJava21SurfaceTest}'s exclusion list — moving it into
 *       xplat re-creates the per-line shared-source patch that list existed for.</li>
 * </ul>
 */
class VersionVolatileFileListTest {

    private static final String[] VERSION_VOLATILE = {
            "dev/vox/lss/networking/client/FarPlayerRenderer.java",
            "dev/vox/lss/mixin/ChunkSaveDataHook.java",
            "dev/vox/lss/compat/ScopedCarrier.java",
    };

    @Test
    void versionVolatileFilesStayOutOfXplat() {
        Path root = repoRoot();
        for (String file : VERSION_VOLATILE) {
            assertFalse(Files.exists(root.resolve("xplat/src/main/java/" + file)),
                    file + " is on the version-volatile list — it must stay in the "
                            + "per-loader trees (whole-file replacement per support line), "
                            + "never in xplat (see the class javadoc for why the compile "
                            + "alone does not guard the delete-the-twins refactor)");
            assertTrue(Files.exists(root.resolve("fabric/src/main/java/" + file)),
                    file + " must exist in the fabric tree (moved? update the list WITH "
                            + "a recorded decision — the port runbook keys off it)");
            assertTrue(Files.exists(root.resolve("neoforge/src/main/java/" + file)),
                    file + " must exist in the neoforge tree (the same-FQN twin)");
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
