package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.compat.ModCompat;

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
 */
final class ResetCoordinator {

    /** Injectable dependencies; production wiring lives in {@link LSSClientCommands}. */
    record Deps(boolean managerActive,
                Runnable drainAndAwaitDecode,
                Supplier<ModCompat.VoxyResetOutcome> voxyReset,
                Runnable lssFlush,
                Runnable clearAllCaches,
                Runnable farPlayerResubscribe,
                Consumer<String> feedback) {}

    /** Runs the sequence; returns true when anything was actually reset (false = the
     *  unconfirmed destructive branch replied with the confirm prompt only). */
    static boolean run(Deps deps, boolean confirmed) {
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
            var outcome = voxyResetContained(deps);
            deps.clearAllCaches().run();
            deps.feedback().accept(voxyLine(outcome) + Brand.shortName() + " caches cleared for "
                    + "ALL servers. No " + Brand.shortName() + " server on this connection — "
                    + "terrain repopulates only from vanilla chunk loading.");
            return true;
        }

        deps.drainAndAwaitDecode().run();
        var outcome = voxyResetContained(deps);
        deps.lssFlush().run();
        // R-3 (filled at E1): clear the far-player tracker + seen-epoch state and
        // re-send prefs AFTER the flush — the server answers ANY prefs receipt with a
        // bumped-epoch full roster, which repopulates. Inert while unsubscribed.
        deps.farPlayerResubscribe().run();
        deps.feedback().accept(voxyLine(outcome) + Brand.shortName()
                + " cache cleared — re-requesting everything from the server.");
        return true;
    }

    /** The last containment belt (stage-D review m2): a throw escaping the Voxy half
     *  must never skip the LSS flush — a wiped Voxy plus surviving LSS stamps is the
     *  persisted-false-stamps hole the feedback branches exist to prevent. */
    private static ModCompat.VoxyResetOutcome voxyResetContained(Deps deps) {
        try {
            return deps.voxyReset().get();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            dev.vox.lss.common.LSSLogger.error("Voxy reset threw — treating as restart failure "
                    + "so the " + Brand.shortName() + " flush still runs", t);
            return ModCompat.VoxyResetOutcome.RESTART_FAILED;
        }
    }

    /** The per-outcome Voxy prefix of the feedback line. The UNAVAILABLE and
     *  RESET_WIPE_SKIPPED branches must not claim more than actually happened. */
    private static String voxyLine(ModCompat.VoxyResetOutcome outcome) {
        return switch (outcome) {
            case RESET -> "Voxy LODs cleared (disk + memory). ";
            case RESET_WIPE_SKIPPED -> "Voxy engine reset (memory cleared) — the disk wipe was "
                    + "SKIPPED (storage unavailable or overridden, e.g. during a replay); "
                    + "rejoin or re-run to clear disk. ";
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
