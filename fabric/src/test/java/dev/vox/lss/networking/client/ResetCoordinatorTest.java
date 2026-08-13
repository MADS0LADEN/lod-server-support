package dev.vox.lss.networking.client;

import dev.vox.lss.compat.ModCompat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The /lss reset sequence pins (v0.11.0 stage D): drain-then-Voxy-then-flush ordering,
 * the per-outcome feedback branches (UNAVAILABLE must not claim LODs disappeared), and
 * the confirm-token gate on the destructive no-manager fallback.
 */
class ResetCoordinatorTest {

    private final List<String> log = new ArrayList<>();
    private final List<String> feedback = new ArrayList<>();

    private ResetCoordinator.Deps deps(boolean managerActive, ModCompat.VoxyResetOutcome outcome) {
        return new ResetCoordinator.Deps(
                managerActive,
                () -> log.add("drain"),
                () -> { log.add("voxy"); return outcome; },
                () -> log.add("flush"),
                () -> log.add("clearAll"),
                () -> log.add("farp"),
                feedback::add);
    }

    @Test
    void activeSessionRunsDrainVoxyFlushInThatOrder() {
        assertTrue(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false));
        assertEquals(List.of("drain", "voxy", "flush", "farp"), log,
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
        assertEquals(List.of("drain", "voxy", "clearAll"), log,
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
                () -> { throw new IllegalStateException("mixin drift"); },
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
}
