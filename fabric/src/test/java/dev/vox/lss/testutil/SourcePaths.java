package dev.vox.lss.testutil;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves production-source files for the source-regex contract-test family.
 *
 * <p>Since stage N-1a (neoforge-support-plan.md §2) loader-neutral MC logic
 * lives in the {@code xplat/} shared source set, so a production file sits
 * under {@code fabric/src/main/java} OR {@code xplat/src/main/java}. This
 * helper hides which — a future move between the two trees is a no-op for
 * callers (the plan's one-line-change rule for the next extraction round).
 * Survives both the Gradle CWD (module dir) and an IDE repo-root CWD.
 */
public final class SourcePaths {
    private SourcePaths() {
    }

    private static final String[] SOURCE_TREES = {
            "src/main/java/",         // CWD = fabric module dir
            "fabric/src/main/java/",  // CWD = repo root
            "xplat/src/main/java/",   // CWD = repo root, xplat tree (module-dir CWD reaches it via the parent walk)
    };

    /**
     * @param javaPath package-relative path, e.g. {@code "dev/vox/lss/trace/MoveDesyncHooks.java"}
     * @throws AssertionError when the file exists in neither tree — a moved-without-retarget signal
     */
    public static Path mainSource(String javaPath) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            for (String tree : SOURCE_TREES) {
                Path candidate = dir.resolve(tree + javaPath);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        throw new AssertionError("cannot locate production source " + javaPath
                + " under fabric/ or xplat/ (cwd=" + Path.of("").toAbsolutePath() + ")");
    }
}
