package dev.vox.lss.compat;


import java.util.OptionalInt;

/**
 * Handles optional mod integrations. Checks for supported LOD mods at startup
 * and registers consumers to bridge received chunk data into their pipelines.
 * <p>
 * Each integration is isolated in its own class to avoid classloading issues
 * when the target mod is not present.
 */
public final class ModCompat {
    private static boolean voxyLoaded;

    public static void init() {
        if (dev.vox.lss.platform.LoaderServices.get().isModLoaded("voxy")) {
            voxyLoaded = VoxyCompat.init();
        }
    }

    public static OptionalInt getVoxyViewDistanceChunks() {
        if (!voxyLoaded) return OptionalInt.empty();
        return VoxyCompat.getViewDistanceChunks();
    }

    /** Outcome of the {@code /lss reset} Voxy half — drives the command's feedback
     *  branch (v0.11.0 stage D, client-reset-command-and-cache-relocation-plan.md). */
    public enum VoxyResetOutcome {
        /** Full teardown + disk wipe + rebuild — "LODs visibly disappear". */
        RESET,
        /** Teardown + rebuild succeeded but the DISK wipe was skipped: the storage root
         *  was unresolvable, or a storage override (Flashback replay redirect — the
         *  origin's REAL store path, which passes directory containment) was detected
         *  by the derived-root cross-check. The feedback must not claim disk was
         *  cleared (stage-D review). */
        RESET_WIPE_SKIPPED,
        /** No live instance (config-disabled / GPU-unsupported): disk wiped via the
         *  fallback derivation, no shutdown/create (never create an instance Voxy
         *  itself didn't have). */
        WIPED_NO_INSTANCE,
        /** Voxy is not installed — the command runs its LSS half only. */
        NOT_PRESENT,
        /** The reflective surface (or the renderer holder) is unresolvable on this
         *  Voxy version — aborted BEFORE any teardown, fail-safe. */
        UNAVAILABLE,
        /** shutdownInstance threw: wipe skipped (open-handle trap), createInstance
         *  recovered — "rejoin to fully clear". */
        SHUTDOWN_FAILED,
        /** createInstance threw: Voxy is down until rejoin. */
        RESTART_FAILED
    }

    /**
     * The {@code /lss reset} Voxy half: renderer-first teardown, per-server disk wipe,
     * instance rebuild (the {@code /voxy reload} sequence with the wipe inserted into
     * the down-window). Main client thread only. Every failure shape is contained to a
     * feedback-driving outcome — it must never cost the ingest bridge.
     */
    public static VoxyResetOutcome resetVoxyLods() {
        if (!voxyLoaded) return VoxyResetOutcome.NOT_PRESENT;
        return VoxyCompat.resetVoxyProduction();
    }
}
