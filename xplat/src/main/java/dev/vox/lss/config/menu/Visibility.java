package dev.vox.lss.config.menu;

/**
 * Whether an option is built into the page at all (as opposed to greyed — that is
 * {@link OptionSpec#enabledBy()}). Enumerable for the same reason as
 * {@link Tooltip.Condition}: tests can assert exactly which options a context hides.
 */
public enum Visibility {
    ALWAYS,
    /** Only with SeeU installed — the "Prefer LSS Far Players" coexist override (E3). */
    SEEU_ONLY;

    public boolean test(MenuContext ctx) {
        return switch (this) {
            case ALWAYS -> true;
            case SEEU_ONLY -> ctx.seeuPresent();
        };
    }
}
