package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The storage-override diagnostics text (issue #4). Two things are pinned here and
 * nowhere else, because they are the whole point of the ticket:
 *
 * <ol>
 *   <li>the skipped-wipe report NAMES BOTH ROOTS — the live one the user can go and
 *       delete by hand, and the one LSS derived — plus the "another mod redirected
 *       Voxy's storage" explanation. The pre-#4 text said only "skipped (fail-safe)",
 *       which told the user nothing they could act on;</li>
 *   <li>the {@code voxy-force} prompt is the FIRST half of a two-stage override: it
 *       shows the path that WOULD be deleted and names the confirm form, and it says
 *       in as many words that nothing has been deleted yet.</li>
 * </ol>
 *
 * <p>This class is the single assembler behind BOTH the client log and the in-game
 * feedback (the AC "log and feedback agree"); {@code VoxyCompatTest} and
 * {@code ResetCoordinatorTest} pin that each side actually routes through it.
 */
class VoxyStorageOverrideTest {

    private static final Path LIVE = Path.of("/games/mc/.voxy/saves/origin.example.com");
    private static final Path DERIVED = Path.of("/games/mc/.voxy/saves/current.example.com");

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    // ---- the skipped-wipe report ----

    @Test
    void skippedWipeReportNamesBothRootsAndTheLikelyCause() {
        String text = joined(VoxyStorageOverride.wipeSkippedLines(LIVE, DERIVED, true));
        assertTrue(text.contains(LIVE.toString()),
                "the user cannot hand-delete a path we never printed: " + text);
        assertTrue(text.contains(DERIVED.toString()),
                "the derived root is what makes the mismatch legible: " + text);
        assertTrue(text.contains("another mod has redirected Voxy's storage location"),
                "the ticket's required explanation sentence is missing: " + text);
        assertTrue(text.contains("NOT deleted"), "must state the disk was left alone: " + text);
    }

    @Test
    void skippedWipeReportOffersTheForceOverrideExactlyOnce() {
        List<String> lines = VoxyStorageOverride.wipeSkippedLines(LIVE, DERIVED, true);
        assertEquals(1, lines.stream().filter(l -> l.contains("reset voxy-force")).count(),
                "the escape hatch must be named, once: " + lines);
    }

    /** After a forced run the suggestion would be nonsense — the caller suppresses it. */
    @Test
    void skippedWipeReportCanSuppressTheForceSuggestion() {
        String text = joined(VoxyStorageOverride.wipeSkippedLines(LIVE, DERIVED, false));
        assertFalse(text.contains("voxy-force"),
                "telling a user who just ran voxy-force to run voxy-force is noise: " + text);
        assertTrue(text.contains(LIVE.toString()), "the paths are still the point: " + text);
    }

    /** RESET_WIPE_SKIPPED also fires when the root was unreadable — a "does not match"
     *  headline would then be a lie. */
    @Test
    void unreadableLiveRootGetsItsOwnHeadlineAndPlaceholders() {
        String text = joined(VoxyStorageOverride.wipeSkippedLines(null, DERIVED, true));
        assertTrue(text.contains("could not be read"), text);
        assertFalse(text.contains("does not match"),
                "nothing was compared — do not claim a mismatch: " + text);
        assertTrue(text.contains(VoxyStorageOverride.UNRESOLVED),
                "an absent path must render as the placeholder, never as 'null': " + text);
        assertFalse(text.contains("null"), "'null' is not a path a user can act on: " + text);
        assertFalse(text.contains("another mod has redirected"),
                "no root was read — there is nothing to blame a mod for: " + text);
    }

    /** Three ways to fail, three headlines. Claiming "does not match" when nothing was
     *  compared is the dishonesty this pins (issue #4 follow-up). */
    @Test
    void anUnverifiableRootGetsItsOwnHeadlineAndCauseInsteadOfAnOverrideGuess() {
        String text = joined(VoxyStorageOverride.wipeSkippedLines(LIVE, null, true));
        assertTrue(text.contains("could not be verified"), text);
        assertFalse(text.contains("does not match"),
                "nothing was compared — do not claim a mismatch: " + text);
        assertFalse(text.contains("another mod has redirected"),
                "an underivable derived root is not evidence of another mod: " + text);
        assertTrue(text.contains("could not derive the expected root"), text);
        assertTrue(text.contains(LIVE.toString()), "the live root is still actionable: " + text);
    }

    /** voxy-force answers a missing live root with "nothing to force-wipe" — offering it
     *  there would send the user down a dead end. */
    @Test
    void noLiveRootIsNeverOfferedTheForceOverride() {
        String text = joined(VoxyStorageOverride.wipeSkippedLines(null, DERIVED, true));
        assertFalse(text.contains("voxy-force"),
                "voxy-force cannot act on this branch — do not advertise it: " + text);
        assertTrue(text.contains(DERIVED.toString()),
                "the derived root is the only path this branch has; it must be shown: " + text);
    }

    @Test
    void mismatchHeadlineSaysMismatch() {
        String text = joined(VoxyStorageOverride.wipeSkippedLines(LIVE, DERIVED, true));
        assertTrue(text.contains("does not match"), text);
        assertFalse(text.contains("could not be read"), text);
    }

    // ---- the voxy-force prompt (stage 1) ----

    private static ModCompat.VoxyStorageProbe probe(Path live, Path expected, boolean contained) {
        return new ModCompat.VoxyStorageProbe(true, live, expected, contained);
    }

    @Test
    void forcePromptShowsTheDoomedPathAndNamesTheConfirmForm() {
        String text = joined(VoxyStorageOverride.forcePromptLines(
                probe(LIVE, DERIVED, true), true));
        assertTrue(text.contains(LIVE.toString()), "the path about to be deleted: " + text);
        assertTrue(text.contains(DERIVED.toString()), "the derived root, for contrast: " + text);
        assertTrue(text.contains("reset voxy-force confirm"),
                "stage 1 must name stage 2 verbatim: " + text);
        assertTrue(text.contains("Nothing has been deleted"),
                "the two-stage promise must be stated, not implied: " + text);
    }

    @Test
    void forcePromptWarnsWhenAnOverrideIsActuallyActive() {
        String text = joined(VoxyStorageOverride.forcePromptLines(
                probe(LIVE, DERIVED, true), true));
        assertTrue(text.contains("another mod has redirected Voxy's storage location"), text);
        assertTrue(text.contains("DO NOT confirm"),
                "deleting another server's (or a replay's) store is the real hazard: " + text);
    }

    @Test
    void forcePromptSaysSoWhenThereIsNoOverrideToOverride() {
        String text = joined(VoxyStorageOverride.forcePromptLines(
                probe(LIVE, LIVE, true), true));
        assertTrue(text.contains("No override detected"), text);
        assertFalse(text.contains("DO NOT confirm"),
                "no hazard here — the scary line must not cry wolf: " + text);
    }

    /** Containment is the SECOND fence and force does not lift it — say so up front
     *  rather than letting the user confirm a wipe that will be refused anyway. */
    @Test
    void forcePromptWarnsThatContainmentWillStillRefuseAnOutOfRootPath() {
        String text = joined(VoxyStorageOverride.forcePromptLines(
                probe(Path.of("/etc"), DERIVED, false), true));
        assertTrue(text.contains("OUTSIDE"), text);
        assertTrue(text.contains("refused"), text);
    }

    @Test
    void forcePromptCarriesTheNoRestreamWarningWithoutAnLssSession() {
        String withSession = joined(VoxyStorageOverride.forcePromptLines(
                probe(LIVE, DERIVED, true), true));
        String without = joined(VoxyStorageOverride.forcePromptLines(
                probe(LIVE, DERIVED, true), false));
        assertFalse(withSession.contains("NO re-stream"),
                "with a live session the wipe is recoverable by construction: " + withSession);
        assertTrue(without.contains("NO re-stream"), without);
        assertTrue(without.contains("vanilla chunk loading"), without);
    }

    @Test
    void forcePromptDegradesHonestlyWithoutVoxyOrWithoutAReadableRoot() {
        String noVoxy = joined(VoxyStorageOverride.forcePromptLines(
                new ModCompat.VoxyStorageProbe(false, null, null, false), true));
        assertTrue(noVoxy.contains("Voxy is not installed"), noVoxy);
        assertFalse(noVoxy.contains("confirm"), "nothing to confirm: " + noVoxy);

        String noRoot = joined(VoxyStorageOverride.forcePromptLines(
                probe(null, DERIVED, false), true));
        assertTrue(noRoot.contains("could not be read"), noRoot);
        assertFalse(noRoot.contains("voxy-force confirm"),
                "there is no path to force — do not offer stage 2: " + noRoot);
    }

    /** The probe's verdict is the same criterion the ladder wipes on, and it keeps
     *  "checked and disagreed" apart from "never checkable". */
    @Test
    void probeVerdictDistinguishesOverriddenFromMerelyUnverifiable() {
        assertEquals(VoxyStorageOverride.Verdict.OVERRIDDEN, probe(LIVE, DERIVED, true).verdict());
        assertEquals(VoxyStorageOverride.Verdict.MATCHES, probe(LIVE, LIVE, true).verdict());
        assertEquals(VoxyStorageOverride.Verdict.NO_LIVE_ROOT, probe(null, DERIVED, false).verdict(),
                "nothing read means nothing to compare, not an override");
        assertEquals(VoxyStorageOverride.Verdict.UNVERIFIABLE, probe(LIVE, null, true).verdict(),
                "an underivable derived root leaves the live root UNCHECKED — not overridden");
    }

    /** The prompt must not guess "another mod did this" when it simply could not look. */
    @Test
    void forcePromptSaysUnverifiableRatherThanGuessingAnOverride() {
        String text = joined(VoxyStorageOverride.forcePromptLines(probe(LIVE, null, true), true));
        assertFalse(text.contains("another mod has redirected"), text);
        assertFalse(text.contains("DO NOT confirm"),
                "no override was detected — the override-specific warning must not fire: " + text);
        assertTrue(text.contains("could not derive the expected root"), text);
        assertTrue(text.contains("Confirm only if you recognise"), text);
        assertTrue(text.contains("reset voxy-force confirm"),
                "an unverifiable root is still force-wipeable, so stage 2 is still offered: " + text);
    }

    /** The two root lines come from one place — a reworded pair must move together. */
    @Test
    void bothReportsShareTheSameRootLines() {
        var shared = VoxyStorageOverride.rootLines(LIVE, DERIVED);
        assertTrue(VoxyStorageOverride.wipeSkippedLines(LIVE, DERIVED, true).containsAll(shared),
                "the skipped-wipe report must use the shared root lines");
        assertTrue(VoxyStorageOverride.forcePromptLines(probe(LIVE, DERIVED, true), true)
                        .containsAll(shared),
                "the force prompt must use the very same two lines");
        assertTrue(VoxyStorageOverride.wipeSkippedLines(LIVE, DERIVED, true)
                        .contains(VoxyStorageOverride.causeLine(VoxyStorageOverride.Verdict.OVERRIDDEN)),
                "and the very same cause sentence");
        assertTrue(VoxyStorageOverride.forcePromptLines(probe(LIVE, DERIVED, true), true)
                        .contains(VoxyStorageOverride.causeLine(VoxyStorageOverride.Verdict.OVERRIDDEN)),
                "and the very same cause sentence");
    }
}
