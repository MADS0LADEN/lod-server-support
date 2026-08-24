package dev.vox.lss.config.menu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Sodium-generation probe (sodium-options-page-generations-plan.md D2): MODERN /
 * LEGACY (either package prefix) / NONE from resource presence alone; MODERN wins if
 * both ever answered (cannot happen live — 0.8 deleted the legacy screen — pinned so a
 * future overlap cannot double-register); a throwing lookup is NONE, never a throw; and
 * the SOURCE contains no {@code Class.forName} — the review's A-1/B-2: a class load of
 * the legacy screen before mixin application defines it past our constructor hook.
 */
class SodiumGenerationTest {

    private static final String MODERN = SodiumGeneration.resourceOf(SodiumGeneration.MODERN_ENTRY_POINT);
    private static final String LEGACY_CAFFEINE = SodiumGeneration.resourceOf(
            SodiumGeneration.CAFFEINE_PREFIX + SodiumGeneration.LEGACY_SCREEN_SUFFIX);
    private static final String LEGACY_JELLYSQUID = SodiumGeneration.resourceOf(
            SodiumGeneration.JELLYSQUID_PREFIX + SodiumGeneration.LEGACY_SCREEN_SUFFIX);

    @AfterEach
    void reset() {
        SodiumGeneration.resetForTests();
    }

    @Test
    void modernFromTheConfigApiEntryPoint() {
        var d = SodiumGeneration.detectWith(Set.of(MODERN)::contains);
        assertEquals(SodiumGeneration.Kind.MODERN, d.kind());
        assertNull(d.legacyPrefix());
    }

    @Test
    void legacyFromTheOptionsScreenUnderEitherPrefix() {
        var caffeine = SodiumGeneration.detectWith(Set.of(LEGACY_CAFFEINE)::contains);
        assertEquals(SodiumGeneration.Kind.LEGACY, caffeine.kind());
        assertEquals(SodiumGeneration.CAFFEINE_PREFIX, caffeine.legacyPrefix());
        var jelly = SodiumGeneration.detectWith(Set.of(LEGACY_JELLYSQUID)::contains);
        assertEquals(SodiumGeneration.Kind.LEGACY, jelly.kind());
        assertEquals(SodiumGeneration.JELLYSQUID_PREFIX, jelly.legacyPrefix());
    }

    @Test
    void nothingIsNone() {
        assertEquals(SodiumGeneration.Kind.NONE, SodiumGeneration.detectWith(r -> false).kind());
    }

    @Test
    void modernWinsAnImpossibleOverlap() {
        var d = SodiumGeneration.detectWith(Set.of(MODERN, LEGACY_CAFFEINE)::contains);
        assertEquals(SodiumGeneration.Kind.MODERN, d.kind());
    }

    @Test
    void aThrowingLookupIsNoneNeverAThrow() {
        var d = SodiumGeneration.detectWith(r -> {
            throw new IllegalStateException("classloader on fire");
        });
        assertEquals(SodiumGeneration.Kind.NONE, d.kind());
    }

    @Test
    void theResourceNameIsTheClassFilePath() {
        assertEquals("net/caffeinemc/mods/sodium/api/config/ConfigEntryPoint.class", MODERN);
    }

    @Test
    void theProbeSourceNeverLoadsAClass() throws IOException {
        for (String tree : new String[]{"fabric", "neoforge"}) {
            String src = stripComments(Files.readString(ClientOptionCatalogTest.locate(
                    tree + "/src/main/java/dev/vox/lss/config/menu/SodiumGeneration.java")));
            assertFalse(CLASS_LOAD.matcher(src).find(),
                    tree + ": the probe must be a RESOURCE lookup — a class load of the legacy"
                            + " screen before mixin application defines it past the hook");
        }
    }

    /** Every spelling of a class load (review): Class.forName, loadClass(...), findClass(...),
     *  and the ::loadClass method reference. */
    static final java.util.regex.Pattern CLASS_LOAD = java.util.regex.Pattern.compile(
            "Class\\s*\\.\\s*forName|\\.\\s*loadClass\\s*\\(|\\.\\s*findClass\\s*\\(|::\\s*loadClass");

    @Test
    void theLegacyPrefixIgnoringModernSeesThroughAForeignApiJar() {
        // A foreign jar shipping the 0.8 API interface beside a 0.6/0.7 Sodium flips the
        // generation to MODERN — the hook (which already runs inside the legacy screen)
        // must still find its prefix.
        assertEquals(SodiumGeneration.CAFFEINE_PREFIX,
                SodiumGeneration.legacyPrefixWith(Set.of(MODERN, LEGACY_CAFFEINE)::contains));
        assertNull(SodiumGeneration.legacyPrefixWith(Set.of(MODERN)::contains));
        assertNull(SodiumGeneration.legacyPrefixWith(r -> { throw new IllegalStateException(); }));
    }

    /** Code only — the javadoc legitimately names the barred call to explain the rule. */
    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
