package dev.vox.lss.paper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the MAIN-line workflow files ({@code .github/workflows/}), read from the
 * source tree like {@link PluginYmlContractTest} — the mirror of the support branches'
 * flavor of this test. Since the v0.8.0 tri-line release the repo carries three release.yml
 * variants (main + two support lines) that publish under the SAME version number, so the
 * dominant regression vector is a merge auto-resolving one line's scoping into another:
 * compilation catches none of that, and release.yml gates an IRREVERSIBLE publish. This
 * lives in {@code :paper:test} because both build.yml and release.yml run {@code :paper:test}
 * BEFORE any publish step, so a regression here physically blocks the tag run that would
 * have shipped it.
 *
 * <p>Assertions are scoped to their STEP block wherever a value could be satisfied by the
 * wrong step. FULL-LINE comments are stripped before asserting; trailing inline comments are
 * not, so avoid other-line tokens in those.
 */
class ReleaseWorkflowContractTest {

    // ---- the line's expected values (the support branches carry their own flavors) ----
    // NOTE: GitHub's filter-pattern language treats '+' as a quantifier, so a pattern
    // containing '*+' is INVALID — it phantom-fails every push and a real tag triggers
    // NOTHING. The literal-'+' scoping must stay in shell (the guard step), never the glob.
    private static final String TRIGGER_LINE = "tags: ['v*']";
    private static final String GUARD_CASE_LINE = "*+mc*)";
    private static final String PREV_TAG_PIPELINE =
            "git tag -l 'v*' --sort=-v:refname | grep -v \"^${TAG}$\" | grep -v '+mc' | head -1 || true";
    private static final String LSS_MODRINTH_ID = "lKiXKLvv";
    private static final String VSS_MODRINTH_ID = "84zcagOb";
    private static final String[] MODRINTH_VERSION_IDS = {
            "version: ${{ github.ref_name }}+fabric+mc26.2",
            "version: ${{ github.ref_name }}+paper+mc26.2",
    };
    /** Support-line MC tokens: must not appear outside comments anywhere in release.yml. */
    private static final String[] FORBIDDEN_LINE_TOKENS = {"26.1", "1.21.11"};

    private static String releaseYml;   // comment-stripped
    private static String buildYml;     // comment-stripped

    @BeforeAll
    static void load() throws Exception {
        releaseYml = stripComments(Files.readString(locate(".github/workflows/release.yml")));
        buildYml = stripComments(Files.readString(locate(".github/workflows/build.yml")));
    }

    private static String stripComments(String yaml) {
        return yaml.lines().filter(l -> !l.strip().startsWith("#"))
                .collect(Collectors.joining("\n"));
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

    /** The step block starting at {@code marker}, ending at the next sibling step header. */
    private static String stepBlock(String marker) {
        int start = releaseYml.indexOf(marker);
        assertTrue(start >= 0, "release.yml lost the step '" + marker + "'");
        int end = releaseYml.indexOf("\n      - ", start + marker.length());
        return end < 0 ? releaseYml.substring(start) : releaseYml.substring(start, end);
    }

    @Test
    void triggerIsTheBroadGlobWithNoQuantifierConstruct() {
        assertTrue(releaseYml.contains(TRIGGER_LINE),
                "release.yml must trigger on the plain v* glob (" + TRIGGER_LINE + ")");
        assertFalse(Pattern.compile("tags:.*\\*\\+").matcher(releaseYml).find(),
                "no tag filter may contain '*+' — GitHub treats '+' as a quantifier, the "
                        + "pattern is invalid, and a real tag push would trigger NOTHING");
    }

    @Test
    void guardStepRefusesSupportLineTags() {
        // A support-line tag (v0.8.0+mc26.1 etc.) mistakenly pushed onto a main commit must
        // not publish: it would ship 26.2 jars under a support-line name, and
        // ${GITHUB_REF_NAME#v} would feed the +mc suffix into -Pmod_version. This lives in
        // a shell guard because the on.push.tags filter cannot express a literal '+'.
        String guard = stepBlock("- name: Refuse support-line tags");
        assertTrue(guard.contains(GUARD_CASE_LINE) && guard.contains("exit 1"),
                "the guard step must fail the run on any *+mc* tag");
        assertTrue(releaseYml.indexOf("- name: Refuse support-line tags")
                        < releaseYml.indexOf("- uses: actions/checkout"),
                "the guard must be the FIRST step — before checkout, builds, or any publish");
    }

    @Test
    void prevTagLookupsExcludeSupportLineTags() {
        // Both PREV_TAG computations (lightweight-tag notes fallback + compare link) must
        // filter '+mc' tags, or version-sort slots a support tag adjacent to this line's
        // and the notes/compare span across release lines.
        long hits = Pattern.compile(Pattern.quote(PREV_TAG_PIPELINE)).matcher(releaseYml)
                .results().count();
        assertEquals(2, hits,
                "release.yml must keep BOTH mainline-scoped PREV_TAG pipelines: " + PREV_TAG_PIPELINE);
    }

    @Test
    void githubReleaseStepShipsAllFourJars() {
        // Main is the one line that ships the VSS pair alongside LSS; a merge from a support
        // branch (whose gh-release step drops VSS) must not shrink the asset list.
        String gh = stepBlock("- uses: softprops/action-gh-release");
        for (String glob : new String[]{
                "fabric/build/libs/lod-server-support-fabric-*.jar",
                "paper/build/libs/lod-server-support-paper-*.jar",
                "fabric/build/libs/voxy-server-side-fabric-*.jar",
                "paper/build/libs/voxy-server-side-paper-*.jar"}) {
            assertTrue(gh.contains(glob), "the gh-release assets must include " + glob);
        }
        assertTrue(gh.contains("fail_on_unmatched_files: true"),
                "fail_on_unmatched_files guards against publishing an empty release");
        assertFalse(gh.contains("make_latest: false"),
                "main-line releases carry the Latest badge — make_latest: false is the "
                        + "support-branch flavor of this step");
    }

    @Test
    void allFourModrinthStepsTargetTheirProjects() {
        // Two LSS steps + two VSS steps, each on its own project id — a support-branch merge
        // dropping the VSS steps (they don't publish VSS) must not survive here.
        assertEquals(2, Pattern.compile(Pattern.quote("modrinth-id: " + LSS_MODRINTH_ID))
                        .matcher(releaseYml).results().count(),
                "both LSS Modrinth steps must target " + LSS_MODRINTH_ID);
        assertEquals(2, Pattern.compile(Pattern.quote("modrinth-id: " + VSS_MODRINTH_ID))
                        .matcher(releaseYml).results().count(),
                "both VSS Modrinth steps must target " + VSS_MODRINTH_ID);
        for (String id : MODRINTH_VERSION_IDS) {
            assertEquals(2, Pattern.compile(Pattern.quote(id)).matcher(releaseYml).results().count(),
                    "LSS + VSS steps must share the version id form '" + id + "'");
        }
    }

    @Test
    void paperStepsDoNotAdvertiseFolia() {
        // folia-supported is deliberately ABSENT on the 26.2 line (no Folia 26.2 build
        // exists); advertising the folia loader would surface an unloadable jar. Re-add
        // together with the plugin.yml flag + pin inversions once Folia ships 26.2.
        assertFalse(Pattern.compile("(?m)^\\s+folia\\s*$").matcher(releaseYml).find(),
                "no Modrinth step may advertise the folia loader on the 26.2 line");
    }

    @Test
    void otherLineTokensAbsentOutsideComments() {
        for (String token : FORBIDDEN_LINE_TOKENS) {
            assertFalse(releaseYml.contains(token),
                    "release.yml must not reference MC " + token + " outside comments on main");
        }
    }

    @Test
    void buildWorkflowRunsOnSupportBranches() {
        // build.yml is shared in spirit across lines; keeping the branches filter identical
        // on main makes the recurring main→support merges conflict-free and ensures a
        // support branch pushed before its own build.yml edit still gets CI.
        long hits = Pattern.compile(Pattern.quote("branches: [main, 'support/**']"))
                .matcher(buildYml).results().count();
        assertEquals(2, hits,
                "build.yml must keep branches: [main, 'support/**'] on push AND pull_request");
    }
}
