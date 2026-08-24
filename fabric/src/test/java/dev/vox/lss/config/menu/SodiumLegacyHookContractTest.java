package dev.vox.lss.config.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract pins for the legacy Sodium options hook (sodium-options-page-generations-plan.md
 * D5; the {@code MoveTraceHookContractTest} source-regex idiom — mixin classes refuse
 * classloading under fabric-loader-junit). On BOTH loader trees: {@code @Pseudo} (an
 * absent target is a silent skip — no config plugin, no plugin-time class load), the
 * string target is exactly the probe's prefix + screen suffix, {@code remap = false},
 * exactly ONE inject and it is {@code <init>} @ RETURN (no MC-inherited target — those
 * cannot be remapped on the loom-remap lines), the body delegates to
 * {@code LegacySodiumPage.build()}, the config is non-required and lists the hook, and
 * both loader descriptors declare the config.
 */
class SodiumLegacyHookContractTest {

    // One nested paren level tolerated: at = @At("RETURN") sits inside the @Inject parens.
    private static final Pattern INJECT = Pattern.compile(
            "@Inject\\(((?:[^()]|\\([^()]*\\))*)\\)", Pattern.DOTALL);

    @Test
    void theHookSourceCarriesTheContractedShapeOnBothTrees() throws IOException {
        for (String tree : new String[]{"fabric", "neoforge"}) {
            String src = Files.readString(ClientOptionCatalogTest.locate(
                    tree + "/src/main/java/dev/vox/lss/mixin/sodium/SodiumLegacyOptionsHook.java"));
            assertTrue(src.contains("@Pseudo"), tree + ": @Pseudo — the target may be absent (0.8+, no Sodium)");
            String expectedTarget = SodiumGeneration.CAFFEINE_PREFIX + SodiumGeneration.LEGACY_SCREEN_SUFFIX;
            assertTrue(src.contains("targets = \"" + expectedTarget + "\""),
                    tree + ": the string target must equal the probe's prefix + suffix");
            assertTrue(Pattern.compile("@Mixin\\([^)]*remap\\s*=\\s*false").matcher(src).find(),
                    tree + ": remap = false — Sodium is not an MC class");
            Matcher m = INJECT.matcher(src);
            int injects = 0;
            while (m.find()) {
                injects++;
                String body = m.group(1);
                assertTrue(body.contains("method = \"<init>\""), tree + ": the only inject is the constructor: " + body);
                assertTrue(body.contains("@At(\"RETURN\")"), tree + ": at RETURN — the page list is filled by then");
            }
            assertEquals(1, injects, tree + ": exactly one inject (no MC-inherited targets)");
            assertTrue(src.contains("LegacySodiumPage.build()"), tree + ": the body delegates to the reflective builder");
            assertTrue(src.contains("implements LegacyOptionsScreenHandle"), tree + ": the deep-link handle");
            assertFalse(SodiumGenerationTest.CLASS_LOAD.matcher(SodiumGenerationTest.stripComments(src)).find(),
                    tree + ": no class loads in the hook");
            assertTrue(src.contains("catch (Throwable t)"), tree + ": the page-list write is contained (doctrine D9)");
        }
    }

    @Test
    void theConfigIsNonRequiredAndListsTheHook() throws IOException {
        for (String tree : new String[]{"fabric", "neoforge"}) {
            JsonObject cfg = JsonParser.parseString(Files.readString(ClientOptionCatalogTest.locate(
                    tree + "/src/main/resources/lss-sodium-legacy.mixins.json"))).getAsJsonObject();
            assertFalse(cfg.get("required").getAsBoolean(), tree + ": required:false — an apply failure degrades to no page");
            assertEquals("dev.vox.lss.mixin.sodium", cfg.get("package").getAsString());
            assertTrue(cfg.getAsJsonArray("client").toString().contains("\"SodiumLegacyOptionsHook\""),
                    tree + ": the hook must be listed under client");
            assertFalse(cfg.has("mixins"), tree + ": client-only — never on a dedicated server's common list");
            // A require miss is an InjectionError that ESCAPES required:false (review) — the
            // tracer's non-required config made the same call: 0, degrade silently.
            assertEquals(0, cfg.getAsJsonObject("injectors").get("defaultRequire").getAsInt(),
                    tree + ": defaultRequire 0 — a missing constructor target must degrade, never crash");
        }
    }

    @Test
    void bothLoaderDescriptorsDeclareTheConfig() throws IOException {
        JsonObject modJson = JsonParser.parseString(Files.readString(
                ClientOptionCatalogTest.locate("fabric/src/main/resources/fabric.mod.json"))).getAsJsonObject();
        assertTrue(modJson.getAsJsonArray("mixins").toString().contains("\"lss-sodium-legacy.mixins.json\""),
                "fabric.mod.json must list lss-sodium-legacy.mixins.json");
        String toml = Files.readString(ClientOptionCatalogTest.locate(
                "neoforge/src/main/resources/META-INF/neoforge.mods.toml"));
        assertTrue(toml.contains("config=\"lss-sodium-legacy.mixins.json\""),
                "neoforge.mods.toml must carry a [[mixins]] row for lss-sodium-legacy.mixins.json");
    }
}
