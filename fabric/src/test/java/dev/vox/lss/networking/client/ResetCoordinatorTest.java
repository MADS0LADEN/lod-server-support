package dev.vox.lss.networking.client;

import dev.vox.lss.compat.ModCompat;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The /lss reset sequence pins (v0.11.0 stage D): drain-then-Voxy-then-flush ordering,
 * the per-outcome feedback branches (UNAVAILABLE must not claim LODs disappeared), and
 * the confirm-token gate on the destructive no-manager fallback.
 *
 * <p>Issue #4 adds the second axis: the skipped-wipe feedback must carry BOTH storage
 * roots, and {@code voxy-force} must be two-stage — the unconfirmed form touches
 * nothing at all.
 */
class ResetCoordinatorTest {

    private static final Path LIVE = Path.of("/games/mc/.voxy/saves/origin.example.com");
    private static final Path DERIVED = Path.of("/games/mc/.voxy/saves/current.example.com");

    private final List<String> log = new ArrayList<>();
    private final List<String> feedback = new ArrayList<>();

    private ResetCoordinator.Deps deps(boolean managerActive, ModCompat.VoxyResetOutcome outcome) {
        return deps(managerActive,
                new ModCompat.VoxyResetReport(outcome, LIVE, DERIVED, null,
                        outcome == ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED),
                new ModCompat.VoxyStorageProbe(true, LIVE, DERIVED, null, true));
    }

    private ResetCoordinator.Deps deps(boolean managerActive,
                                       ModCompat.VoxyResetReport report,
                                       ModCompat.VoxyStorageProbe probe) {
        return new ResetCoordinator.Deps(
                managerActive,
                () -> log.add("drain"),
                force -> { log.add("voxy(force=" + force + ")"); return report; },
                () -> { log.add("probe"); return probe; },
                () -> log.add("flush"),
                () -> log.add("clearAll"),
                () -> log.add("farp"),
                feedback::add);
    }

    private String allFeedback() {
        return String.join("\n", feedback);
    }

    @Test
    void activeSessionRunsDrainVoxyFlushInThatOrder() {
        assertTrue(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false));
        assertEquals(List.of("drain", "voxy(force=false)", "flush", "farp"), log,
                "drain FIRST (a late decode dispatch can open a store inside the wipe dir), "
                        + "Voxy half SECOND, LSS flush, then the R-3 far-player re-subscribe "
                        + "AFTER the flush (the bumped-epoch roster repopulates fresh state)");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).startsWith("Voxy LODs cleared (disk + memory)."), feedback.get(0));
    }

    @Test
    void activeSessionNeedsNoConfirmToken() {
        assertTrue(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false),
                "with an active LSS session the wipe is recoverable by construction — one step");
        assertTrue(log.contains("flush"));
    }

    @Test
    void unavailableOutcomeMustNotClaimLodsDisappeared() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.UNAVAILABLE), false);
        assertTrue(feedback.get(0).contains("Voxy reset unavailable"), feedback.get(0));
        assertFalse(feedback.get(0).contains("cleared (disk + memory)"),
                "LODs did NOT visibly disappear on this branch — the message must not claim it");
        assertTrue(log.contains("flush"), "the LSS half still runs");
    }

    @Test
    void failureOutcomesCarryTheirRejoinGuidance() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED), false);
        assertTrue(feedback.get(0).contains("rejoin to fully clear"), feedback.get(0));
        feedback.clear();
        log.clear();
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESTART_FAILED), false);
        assertTrue(feedback.get(0).contains("rejoin to recover"), feedback.get(0));
    }

    @Test
    void notPresentOutcomeReportsTheLssHalfOnly() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.NOT_PRESENT), false);
        assertFalse(feedback.get(0).contains("Voxy"), "no Voxy installed — no Voxy claims");
        assertTrue(feedback.get(0).contains("re-requesting"), feedback.get(0));
    }

    @Test
    void noManagerFallbackRequiresTheConfirmToken() {
        assertFalse(ResetCoordinator.run(deps(false, ModCompat.VoxyResetOutcome.RESET), false),
                "the destructive no-re-stream branch must not run unconfirmed");
        assertTrue(log.isEmpty(), "nothing wiped, nothing drained");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).contains("reset confirm"),
                "the prompt must name the confirm form: " + feedback.get(0));
        assertTrue(feedback.get(0).contains("NO re-stream"),
                "the prompt must say exactly why it is destructive");
    }

    @Test
    void confirmedNoManagerFallbackClearsAllAndSaysThereIsNoRestream() {
        assertTrue(ResetCoordinator.run(deps(false, ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE), true));
        assertEquals(List.of("drain", "voxy(force=false)", "clearAll"), log,
                "no LSS session: drain still closes the wipe window (a reset racing a "
                        + "just-died session's final dispatch), then the Voxy half + clearAll");
        assertTrue(feedback.get(0).contains("vanilla chunk loading"), feedback.get(0));
    }

    /** Review m2: a throw escaping the Voxy half must never skip the LSS flush — the
     *  coordinator belts it to RESTART_FAILED and the flush + feedback still run. */
    @Test
    void voxyHalfThrowStillRunsTheFlushAndFeedback() {
        var deps = new ResetCoordinator.Deps(
                true,
                () -> log.add("drain"),
                force -> { throw new IllegalStateException("mixin drift"); },
                () -> new ModCompat.VoxyStorageProbe(true, LIVE, DERIVED, null, true),
                () -> log.add("flush"),
                () -> log.add("clearAll"),
                () -> log.add("farp"),
                feedback::add);
        assertTrue(ResetCoordinator.run(deps, false));
        assertTrue(log.contains("flush"),
                "a skipped flush after a wipe persists false stamps — the belt is load-bearing");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).contains("rejoin to recover"), feedback.get(0));
    }

    /** The RESET_WIPE_SKIPPED line must admit the disk was NOT cleared. */
    @Test
    void wipeSkippedOutcomeIsHonestAboutTheDisk() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED), false);
        assertTrue(feedback.get(0).contains("disk wipe was SKIPPED"), feedback.get(0));
        assertFalse(feedback.get(0).contains("disk + memory"),
                "must not claim the full-RESET disk line");
        assertTrue(log.contains("flush"), "the LSS half still runs");
    }

    // ---- issue #4: actionable skipped-wipe feedback ----

    /** AC1: the in-game feedback carries the same two roots the client log gets. */
    @Test
    void wipeSkippedFeedbackCarriesBothStorageRoots() {
        ResetCoordinator.run(deps(true,
                new ModCompat.VoxyResetReport(
                        ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, LIVE, DERIVED, null, true),
                new ModCompat.VoxyStorageProbe(true, LIVE, DERIVED, null, true)), false);
        String text = allFeedback();
        assertTrue(text.contains(LIVE.toString()), "live root missing from feedback: " + text);
        assertTrue(text.contains(DERIVED.toString()), "derived root missing: " + text);
        assertTrue(text.contains("another mod has redirected Voxy's storage location"), text);
        assertTrue(text.contains("reset voxy-force"),
                "the feedback must point at the escape hatch: " + text);
    }

    /** The detail block belongs to the skipped branch only — a clean RESET must not
     *  spray paths and override talk at a user whose wipe worked. */
    @Test
    void successfulResetGetsNoOverrideDetailBlock() {
        ResetCoordinator.run(deps(true,
                new ModCompat.VoxyResetReport(ModCompat.VoxyResetOutcome.RESET, LIVE, LIVE, null,
                        false),
                new ModCompat.VoxyStorageProbe(true, LIVE, LIVE, null, true)), false);
        assertEquals(1, feedback.size(), "one line, as before #4: " + feedback);
        assertFalse(allFeedback().contains("voxy-force"), allFeedback());
    }

    // ---- issue #4: /lss reset voxy-force, two-stage ----

    /** AC2: the unconfirmed override deletes nothing — it does not even drain. */
    @Test
    void unconfirmedForceTouchesNothingAndShowsThePathsFirst() {
        assertFalse(ResetCoordinator.run(
                        deps(true, ModCompat.VoxyResetOutcome.RESET), false, true),
                "stage 1 reports; it does not reset");
        assertEquals(List.of("probe"), log,
                "a read-only probe is the ONLY thing stage 1 may do: " + log);
        String text = allFeedback();
        assertTrue(text.contains(LIVE.toString()), "the doomed path must be shown: " + text);
        assertTrue(text.contains(DERIVED.toString()), text);
        assertTrue(text.contains("reset voxy-force confirm"),
                "stage 1 must name stage 2: " + text);
        assertTrue(text.contains("Nothing has been deleted"), text);
    }

    /** AC2/AC4: the confirmed override runs the ordinary sequence with the cross-check
     *  waived — same order, same unconditional flush, force flag threaded through. */
    @Test
    void confirmedForceRunsTheFullSequenceWithTheForceFlagSet() {
        assertTrue(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), true, true));
        assertEquals(List.of("drain", "voxy(force=true)", "flush", "farp"), log,
                "force changes the wipe criterion, never the ordering: " + log);
    }

    /** The confirm token is shared: a forced reset on a dead session is still the
     *  destructive no-re-stream branch, and stage 1 says so. */
    @Test
    void unconfirmedForceWithoutASessionAlsoWarnsAboutTheMissingRestream() {
        assertFalse(ResetCoordinator.run(
                deps(false, ModCompat.VoxyResetOutcome.RESET), false, true));
        assertTrue(allFeedback().contains("NO re-stream"), allFeedback());
        assertEquals(List.of("probe"), log, log.toString());
    }

    @Test
    void confirmedForceWithoutASessionStillClearsAllAndSkipsTheFlush() {
        assertTrue(ResetCoordinator.run(
                deps(false, ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE), true, true));
        assertEquals(List.of("drain", "voxy(force=true)", "clearAll"), log, log.toString());
    }

    /** AC4: the two-argument entry point — every existing caller of the default path —
     *  must still request an UNFORCED reset. */
    @Test
    void theDefaultEntryPointNeverForces() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false);
        assertTrue(log.contains("voxy(force=false)"), log.toString());
        assertFalse(log.contains("probe"),
                "the default path must not pay for the force-prompt probe: " + log);
    }

    /** A forced run that still ends skipped must not tell the user to run the very
     *  command they just ran. */
    @Test
    void forcedRunThatStillSkipsDoesNotSuggestForcingAgain() {
        ResetCoordinator.run(deps(true,
                new ModCompat.VoxyResetReport(
                        ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, null, DERIVED, null, true),
                new ModCompat.VoxyStorageProbe(true, null, DERIVED, null, false)), true, true);
        String text = allFeedback();
        assertTrue(text.contains("disk wipe was SKIPPED"), text);
        assertFalse(text.contains("voxy-force"), "already forced — the hint is noise: " + text);
    }

    // ---- issue #4 follow-up: the detail block follows the WIPE, not the outcome ----

    /**
     * The original #4 keyed the detail block on {@code RESET_WIPE_SKIPPED}. A wipe the
     * cross-check declined can still end UNAVAILABLE / SHUTDOWN_FAILED / RESTART_FAILED
     * when a later rung fails — and those users got a full report in the client log and
     * nothing in chat. The report carries {@code wipeDeclined} precisely so the two
     * agree.
     */
    @Test
    void aDeclinedWipeIsReportedInChatWhateverTheLadderEndedAs() {
        for (var outcome : List.of(ModCompat.VoxyResetOutcome.UNAVAILABLE,
                ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED,
                ModCompat.VoxyResetOutcome.RESTART_FAILED,
                ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED)) {
            log.clear();
            feedback.clear();
            ResetCoordinator.run(deps(true,
                    new ModCompat.VoxyResetReport(outcome, LIVE, DERIVED, null, true),
                    new ModCompat.VoxyStorageProbe(true, LIVE, DERIVED, null, true)), false);
            String text = allFeedback();
            assertTrue(text.contains(LIVE.toString()),
                    outcome + ": the LODs are still at this path and chat never said so: " + text);
            assertTrue(text.contains(DERIVED.toString()), outcome + ": " + text);
            assertTrue(log.contains("flush"), outcome + ": the LSS half still runs");
        }
    }

    /** The mirror image: a failure that never declined a wipe must stay quiet, because
     *  the client log said nothing either. */
    @Test
    void anUndeclinedWipeGetsNoDetailBlock() {
        for (var outcome : List.of(ModCompat.VoxyResetOutcome.UNAVAILABLE,
                ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED,
                ModCompat.VoxyResetOutcome.RESTART_FAILED,
                ModCompat.VoxyResetOutcome.RESET)) {
            log.clear();
            feedback.clear();
            ResetCoordinator.run(deps(true,
                    new ModCompat.VoxyResetReport(outcome, LIVE, DERIVED, null, false),
                    new ModCompat.VoxyStorageProbe(true, LIVE, DERIVED, null, true)), false);
            assertEquals(1, feedback.size(),
                    outcome + ": no cross-check refusal, so no report: " + feedback);
        }
    }
}
