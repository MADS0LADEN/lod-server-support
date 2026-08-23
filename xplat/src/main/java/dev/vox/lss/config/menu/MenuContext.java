package dev.vox.lss.config.menu;

import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.client.FarPlayerClientSupport;
import dev.vox.lss.platform.LoaderServices;

/**
 * The environment facts the options catalog's CONDITIONAL surfaces read at page-build
 * time (sodium-options-page-generations-plan.md D1): the three booleans behind the
 * conditional tooltips ({@link Tooltip.Condition}) and the SeeU-only option
 * ({@link Visibility}). Computed ONCE per page build by {@link #current()} — every
 * renderer resolves the same facts the same way, so a legacy-Sodium page and a modern
 * one never disagree about which tooltip the user sees.
 *
 * <p>A plain record so tests enumerate every combination (the lang-key completeness
 * pin walks {@link Tooltip#keys()} instead, but the resolve tests flip these).
 *
 * @param governorOn   {@code enableAdaptiveTransferRate} — the join-slow-start toggle
 *                     is inert without it (its tooltip says so)
 * @param xaeroPresent Xaero's World Map is installed — the map-bridge toggle is inert
 *                     without it (its tooltip says so)
 * @param seeuPresent  SeeU is installed — the coexist gate overrides "Show Far
 *                     Players" (its tooltip says so) and reveals the override toggle
 */
public record MenuContext(boolean governorOn, boolean xaeroPresent, boolean seeuPresent) {

    /** The live facts: the loaded client config + the loader's mod list + the SeeU probe.
     *  Every lookup is contained — a loader-less unit context reads as "absent". */
    public static MenuContext current() {
        boolean governor;
        try {
            governor = LSSClientConfig.CONFIG.enableAdaptiveTransferRate;
        } catch (Throwable t) {
            governor = true;
        }
        boolean xaero;
        try {
            xaero = LoaderServices.get().isModLoaded("xaeroworldmap");
        } catch (Throwable t) {
            xaero = false;
        }
        boolean seeu;
        try {
            seeu = FarPlayerClientSupport.isSeeuPresent();
        } catch (Throwable t) {
            seeu = false;
        }
        return new MenuContext(governor, xaero, seeu);
    }
}
