package dev.vox.lss.config.menu;

import java.util.List;
import java.util.Objects;

/**
 * An option's tooltip as catalog DATA: a fixed translation key, or a pair of keys
 * chosen by an enumerable {@link Condition} over the {@link MenuContext} at page-build
 * time — the "say so where the user is looking" tooltips (join-slow-start with the
 * governor off, the Xaero toggle without Xaero, Show Far Players under SeeU).
 *
 * <p>The condition is an ENUM rather than a predicate lambda so {@link #keys()} can
 * enumerate every key the tooltip may ever resolve to — that is what lets the catalog
 * test pin lang-file completeness without guessing context combinations.
 */
public record Tooltip(Condition condition, String whenTrueKey, String whenFalseKey) {

    /** The environment tests a tooltip may switch on. Each maps to one {@link MenuContext}
     *  fact; {@link #ALWAYS} is the fixed-tooltip degenerate case. */
    public enum Condition {
        ALWAYS,
        /** The adaptive transfer governor umbrella is on (join slow start is live). */
        GOVERNOR_ON,
        /** Xaero's World Map is installed (the map bridge can do something). */
        XAERO_PRESENT,
        /** SeeU is NOT installed (LSS far players are not overridden by the coexist gate). */
        SEEU_ABSENT;

        public boolean test(MenuContext ctx) {
            return switch (this) {
                case ALWAYS -> true;
                case GOVERNOR_ON -> ctx.governorOn();
                case XAERO_PRESENT -> ctx.xaeroPresent();
                case SEEU_ABSENT -> !ctx.seeuPresent();
            };
        }
    }

    public Tooltip {
        Objects.requireNonNull(condition);
        Objects.requireNonNull(whenTrueKey);
        Objects.requireNonNull(whenFalseKey);
    }

    public static Tooltip fixed(String key) {
        return new Tooltip(Condition.ALWAYS, key, key);
    }

    /** {@code whenTrueKey} when the condition holds in the build context, else {@code whenFalseKey}. */
    public static Tooltip conditional(Condition condition, String whenTrueKey, String whenFalseKey) {
        return new Tooltip(condition, whenTrueKey, whenFalseKey);
    }

    public String resolve(MenuContext ctx) {
        return condition.test(ctx) ? whenTrueKey : whenFalseKey;
    }

    /** Every key this tooltip can resolve to (distinct, in declaration order). */
    public List<String> keys() {
        return whenTrueKey.equals(whenFalseKey) ? List.of(whenTrueKey) : List.of(whenTrueKey, whenFalseKey);
    }
}
