package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.compat.VoxyStorageOverride;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code /lss reset} sequence (v0.11.0 stage D —
 * client-reset-command-and-cache-relocation-plan.md Part 1), seam-injected so JUnit
 * pins the ordering and both fallbacks without MC. The command body stays thin.
 *
 * <p>Order is load-bearing: decode-queue drain + in-flight await FIRST (a late decode
 * dispatch can open a fresh Voxy store handle inside the directory the Voxy half is
 * about to wipe), the Voxy half SECOND (teardown + wipe + rebuild), the LSS flush LAST
 * (after the new Voxy instance is live, so every re-served column lands in the fresh
 * engine; columns arriving mid-sequence enqueue into the NEW instance and their stamps
 * are cleared at the flush → re-served → duplicate ingest, idempotent by protocol
 * design).
 *
 * <p>The no-manager branch (LSS inactive on this server) is destructive with NO
 * re-stream — no LSS server exists to refill Voxy, it repopulates only from vanilla
 * chunk loading — so it is gated on an explicit {@code confirm} token. With an active
 * LSS session the single-step command stands: the wipe is recoverable by construction.
 *
 * <p>Issue #4 adds a SECOND confirm-gated form, {@code reset voxy-force}, for the case
 * where a storage override made the Voxy disk wipe fail-safe out. It reuses the same
 * two-stage shape: unconfirmed it only PROBES (shows both storage roots and deletes
 * nothing); confirmed it runs this very sequence with the ladder's derived-root
 * cross-check waived. The default path — everything reachable without typing
 * {@code voxy-force} — is untouched.
 */
final class ResetCoordinator {

    /** The Voxy half, parameterised by issue #4's force flag. */
    @FunctionalInterface
    interface VoxyReset {
        ModCompat.VoxyResetReport reset(boolean forceWipe);
    }

    /** Injectable dependencies; production wiring lives in {@link LSSClientCommands}. */
    record Deps(boolean managerActive,
                Runnable drainAndAwaitDecode,
                VoxyReset voxyReset,
                Supplier<ModCompat.VoxyStorageProbe> voxyStorageProbe,
                Runnable lssFlush,
                Runnable clearAllCaches,
                Runnable farPlayerResubscribe,
                Consumer<String> feedback) {}

    /** The default (never-forcing) entry point — {@code /lss reset [confirm]}. */
    static boolean run(Deps deps, boolean confirmed) {
        return run(deps, confirmed, false);
    }

    /** Runs the sequence; returns true when anything was actually reset (false = an
     *  unconfirmed destructive branch replied with its prompt only). */
    static boolean run(Deps deps, boolean confirmed, boolean forceVoxyWipe) {
        if (forceVoxyWipe && !confirmed) {
            // Stage 1 of the override: show the path that stage 2 would delete. The probe
            // is read-only by construction — nothing is drained, torn down or deleted
            // until the user comes back with the confirm token.
            VoxyStorageOverride.forcePromptLines(deps.voxyStorageProbe().get(), deps.managerActive())
                    .forEach(deps.feedback());
            return false;
        }
        if (!deps.managerActive()) {
            if (!confirmed) {
                deps.feedback().accept("No active " + Brand.shortName() + " session — this wipes "
                        + "the Voxy and " + Brand.shortName() + " caches for ALL servers with NO "
                        + "re-stream (Voxy repopulates only from vanilla chunk loading). Run '/"
                        + Brand.clientCommand() + " reset confirm' to proceed.");
                return false;
            }
            deps.drainAndAwaitDecode().run(); // a reset racing a just-died session's final
                                              // dispatch must still close the wipe window
            var report = voxyResetContained(deps, forceVoxyWipe);
            deps.clearAllCaches().run();
            deps.feedback().accept(voxyLine(report) + Brand.shortName() + " caches cleared for "
                    + "ALL servers. No " + Brand.shortName() + " server on this connection — "
                    + "terrain repopulates only from vanilla chunk loading.");
            emitStorageDetail(deps, report, forceVoxyWipe);
            return true;
        }

        deps.drainAndAwaitDecode().run();
        var report = voxyResetContained(deps, forceVoxyWipe);
        deps.lssFlush().run();
        // R-3 (filled at E1): clear the far-player tracker + seen-epoch state and
        // re-send prefs AFTER the flush — the server answers ANY prefs receipt with a
        // bumped-epoch full roster, which repopulates. Inert while unsubscribed.
        deps.farPlayerResubscribe().run();
        deps.feedback().accept(voxyLine(report) + Brand.shortName()
                + " cache cleared — re-requesting everything from the server.");
        emitStorageDetail(deps, report, forceVoxyWipe);
        return true;
    }

    /** The last containment belt (stage-D review m2): a throw escaping the Voxy half
     *  must never skip the LSS flush — a wiped Voxy plus surviving LSS stamps is the
     *  persisted-false-stamps hole the feedback branches exist to prevent. */
    private static ModCompat.VoxyResetReport voxyResetContained(Deps deps, boolean forceVoxyWipe) {
        try {
            return deps.voxyReset().reset(forceVoxyWipe);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            dev.vox.lss.common.LSSLogger.error("Voxy reset threw — treating as restart failure "
                    + "so the " + Brand.shortName() + " flush still runs", t);
            return ModCompat.VoxyResetReport.of(ModCompat.VoxyResetOutcome.RESTART_FAILED);
        }
    }

    /**
     * Issue #4: a declined disk wipe leaves LODs on disk that the user may want to remove
     * by hand, so the feedback names both roots — the SAME lines the client log got, from
     * the same assembler.
     *
     * <p>A22 (issue #1) adds the seed-derived root to the same lines when this connection
     * has one, so the report never understates what {@code /lss reset} touched.
     *
     * <p>The trigger is {@code wipeDeclined}, NOT the {@code RESET_WIPE_SKIPPED} outcome.
     * A wipe the cross-check refused can still finish as UNAVAILABLE, SHUTDOWN_FAILED or
     * RESTART_FAILED when a later rung fails, and the original #4 implementation gave
     * those users a full report in the client log and not one word in chat. The flag is
     * set exactly where the log emits, so the two cannot diverge.
     */
    private static void emitStorageDetail(Deps deps, ModCompat.VoxyResetReport report,
                                          boolean forceVoxyWipe) {
        if (!report.wipeDeclined()) return;
        VoxyStorageOverride.wipeSkippedLines(report.liveRoot(), report.expectedRoot(),
                report.seedRoot(), !forceVoxyWipe).forEach(deps.feedback());
    }

    /** The per-outcome Voxy prefix of the feedback line. The UNAVAILABLE and
     *  RESET_WIPE_SKIPPED branches must not claim more than actually happened;
     *  RESET_WIPE_SKIPPED's detail (both storage roots) follows on its own lines. */
    private static String voxyLine(ModCompat.VoxyResetReport report) {
        return switch (report.outcome()) {
            case RESET -> "Voxy LODs cleared (disk + memory). ";
            case RESET_WIPE_SKIPPED -> "Voxy engine reset (memory cleared) — the disk wipe was "
                    + "SKIPPED (fail-safe); rejoin or re-run to clear disk. ";
            case WIPED_NO_INSTANCE -> "Voxy disk cache cleared (Voxy not running). ";
            case NOT_PRESENT -> "";
            case UNAVAILABLE -> "Voxy reset unavailable on this Voxy version — clearing the "
                    + Brand.shortName() + " half only. ";
            case SHUTDOWN_FAILED -> "Voxy reset incomplete — rejoin to fully clear. ";
            case RESTART_FAILED -> "Voxy failed to restart — rejoin to recover. ";
        };
    }

    private ResetCoordinator() {}
}
