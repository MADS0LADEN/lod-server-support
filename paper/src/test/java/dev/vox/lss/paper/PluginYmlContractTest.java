package dev.vox.lss.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@code paper/src/main/resources/plugin.yml}, parsed with Bukkit's own
 * {@link YamlConfiguration} from the SOURCE tree: the classpath copy has {@code ${version}}
 * already expanded by processResources, which would make the placeholder pin vacuous.
 * (Expansion inside the built jar is release_check.py's job.) A typo in any of these fields
 * is invisible until a real Paper server refuses to load — or silently mis-loads — the
 * plugin: an unresolvable {@code main} or wrong {@code api-version} aborts plugin load, a
 * renamed plugin moves the {@code plugins/LodServerSupport/} data folder the config and
 * soak staging rely on, and {@code folia-supported} must stay DECLARED on the 26.2 line —
 * Folia ships a real 26.2 build (26.2-1, 2026-07-28) and all four Folia soak scenarios
 * passed against it (2026-08-01), so the guarded failure is now a jar that silently
 * STOPS loading on Folia. (The flag was absent while no Folia 26.2 existed; that
 * direction inverts on fresh re-ports to lines Folia does not publish for.)
 */
class PluginYmlContractTest {

    private static String rawText;
    private static YamlConfiguration yml;

    @BeforeAll
    static void load() throws Exception {
        rawText = Files.readString(locate("paper/src/main/resources/plugin.yml"));
        yml = new YamlConfiguration();
        // 'lss.admin' is a literal permission key; the default '.' separator would split it.
        yml.options().pathSeparator('/');
        yml.loadFromString(rawText);
    }

    /** Walks up from the working dir (paper/ under Gradle, the repo root elsewhere). */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above " + Path.of("").toAbsolutePath());
    }

    @Test
    void mainClassResolvesToThePluginEntryPoint() throws Exception {
        String main = yml.getString("main");
        assertNotNull(main, "plugin.yml must declare main");
        Class<?> resolved = Class.forName(main);
        assertEquals(LSSPaperPlugin.class, resolved, "main must point at the real entry point");
        assertTrue(JavaPlugin.class.isAssignableFrom(resolved), "main must be a JavaPlugin");
    }

    @Test
    void pluginNameIsTheDataFolderContract() {
        // PaperConfig lives at plugins/LodServerSupport/lss-server-config.json and the soak
        // harness stages configs by that path; renaming the plugin silently orphans both.
        assertEquals("LodServerSupport", yml.getString("name"));
    }

    @Test
    void lsslodCommandIsDeclaredBehindTheAdminPermission() {
        assertNotNull(yml.getConfigurationSection("commands/lsslod"),
                "registerCommands() reads the declared command name from plugin.yml; without this "
                        + "section the command vanishes (the VSS repackage rewrites the key to vsslod)");
        assertEquals("lss.admin", yml.getString("commands/lsslod/permission"));
    }

    @Test
    void exactlyOneCommandIsDeclared() {
        // registerCommands() resolves the command name as getCommands().keySet().first(), so a
        // single declared command is what makes that deterministic (and lets the VSS repackage
        // rename the key without a code fork). A second command would make the pick ambiguous.
        assertEquals(1, yml.getConfigurationSection("commands").getKeys(false).size(),
                "the plugin must declare exactly one command (registerCommands picks the first)");
    }

    @Test
    void adminPermissionDefaultsToOp() {
        assertEquals("op", yml.getString("permissions/lss.admin/default"),
                "stats/diag expose server internals; the permission must not default to everyone");
    }

    @Test
    void bothFarPlayerPrivacyNodesAreDeclaredDefaultFalse() {
        // Review-wave V-M1 (the load-bearing dual declaration): the enforcement
        // dual-checks lss.* OR vss.*, and Bukkit resolves an UNDECLARED node to the op
        // default — deleting EITHER declaration (or flipping a default) silently hides
        // every op from far players on the jars where that spelling is undeclared.
        // Both spellings ship in BOTH jars; the vssJar rewrite must not rename them.
        assertEquals("false", yml.getString("permissions/lss.farplayers.hidden/default"),
                "lss.farplayers.hidden must be declared default false");
        assertEquals("false", yml.getString("permissions/vss.farplayers.hidden/default"),
                "vss.farplayers.hidden must be declared default false");
    }

    @Test
    void apiVersionMatchesTheDevBundleMinecraftVersion() throws Exception {
        // V-1/P3: the SOURCE carries the template token; the value IS gradle.properties'
        // minecraft_version by construction (paper processResources), so the lockstep
        // assertion moves to the data side.
        assertEquals("${api_version}", yml.getString("api-version"),
                "plugin.yml's api-version must stay templated from minecraft_version");

        var props = new Properties();
        props.load(new StringReader(Files.readString(locate("gradle.properties"))));
        String apiVersion = props.getProperty("minecraft_version");
        assertNotNull(apiVersion);

        var bundle = Pattern.compile("paperweight\\.paperDevBundle\\('([^']+)'\\)")
                .matcher(Files.readString(locate("paper/build.gradle")));
        assertTrue(bundle.find(), "paper/build.gradle must declare paperweight.paperDevBundle('...')");
        assertTrue(bundle.group(1).startsWith(apiVersion + "."),
                "dev bundle " + bundle.group(1) + " must be a build of api-version " + apiVersion);
    }

    @Test
    void foliaSupportedIsDeclaredNowThatFolia262Exists() {
        // Third flip. Absent 2026-07-19..2026-08-01 because Folia had no MC 26.2 build at
        // all, so declaring it would have auto-loaded release jars onto a platform that did
        // not exist yet. Folia published 26.2-1 (channel BETA) on 2026-07-28, so the flag is
        // back — putting this line on the same experimental footing as 1.21.8/1.21.11/26.1.x
        // rather than ahead of them. FoliaWiringContractTest still pins the wiring
        // (no legacy scheduler, lifecycle through the mailbox).
        assertTrue(yml.contains("folia-supported"),
                "folia-supported must be declared now that Folia ships a 26.2 build — the"
                        + " single jar serves Paper and Folia");
        assertTrue(yml.getBoolean("folia-supported"),
                "...and it must be true; a false/absent flag makes Folia refuse the jar");
        assertTrue(rawText.contains("folia-supported: true"),
                "release_check.py greps the RAW line, so the source must carry that exact form");
    }

    @Test
    void versionIsTheProcessResourcesPlaceholder() {
        // The literal placeholder must survive in the source: processResources expands it at
        // build time, and release_check.py (HD-045) verifies the expansion in the built jar.
        assertEquals("${version}", yml.getString("version"));
        assertTrue(rawText.contains("version: '${version}'"),
                "the placeholder must stay single-quoted so the YAML stays parseable pre-expansion");
    }

    @Test
    void viaVersionIsASoftdependForTheClassloaderWarning() {
        // C5 (review m7): without the softdepend, Spigot's PluginClassLoader still
        // resolves com.viaversion... through the global group (the probe works), but
        // logs the "not a depend, softdepend or loadbefore" warning on the first
        // handshake with Via installed. A HARD depend would be wrong: the guard is
        // fail-open and the plugin must load without Via.
        var softdepend = yml.getStringList("softdepend");
        org.junit.jupiter.api.Assertions.assertTrue(softdepend.contains("ViaVersion"),
                "ViaVersion must be a softdepend (never a depend)");
        org.junit.jupiter.api.Assertions.assertNull(yml.get("depend"),
                "no hard depends — the plugin loads standalone");
    }

    @Test
    void pluginYmlShipsOnTheClasspath() {
        assertNotNull(LSSPaperPlugin.class.getResource("/plugin.yml"),
                "plugin.yml must be packaged at the jar root or Paper will not recognize the plugin");
    }
}
