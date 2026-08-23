package dev.vox.lss.config.menu;

import java.util.List;

/**
 * Implemented BY THE MIXIN onto the legacy Sodium (0.6/0.7) options screen
 * (sodium-options-page-generations-plan.md D5/D6): the constructor hook records the
 * page objects it injected so the ModMenu deep-link can select the first one
 * pre-init without touching the screen's private page list. Lives in xplat because it
 * names no Sodium type — the pages are opaque {@code Object}s here (the legacy
 * builder is reflective; nothing in shared code may spell a Sodium class name).
 */
public interface LegacyOptionsScreenHandle {

    /** The pages this mod added to the screen (display order), empty when none were built. */
    List<Object> lss$injectedPages();
}
