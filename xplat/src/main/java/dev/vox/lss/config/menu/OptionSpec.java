package dev.vox.lss.config.menu;

import dev.vox.lss.config.LSSClientConfig;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * One option of the in-game settings page as DATA (sodium-options-page-generations-plan.md
 * D1): everything a renderer needs to build the widget on ANY Sodium generation, and
 * nothing renderer-specific. Two kinds today — a tick box and an integer slider; an
 * enum kind is deliberately absent until an enum option exists.
 *
 * <p>Bindings are over a config INSTANCE (not a captured {@code LSSClientConfig.CONFIG}):
 * the legacy Sodium API hands the storage's data object to the binding, and the tests
 * round-trip fresh instances without touching the live config.
 *
 * <p>{@code defaultValue} is declared here only so a renderer can offer Sodium's
 * "reset to default"; the catalog test pins it EQUAL to a fresh
 * {@code new LSSClientConfig()}'s field value through the getter, so the two can never
 * drift (the v0.11/v0.12 pages hand-duplicated every default with no pin).
 */
public sealed interface OptionSpec permits OptionSpec.BoolSpec, OptionSpec.IntSpec {

    /** Stable {@code lss:<name>} id — the 0.8+ API keys per-option state by it. Never renumber. */
    String id();

    String nameKey();

    Tooltip tooltip();

    /** Null = no impact line in the tooltip (the LOD-distance slider's shipped shape). */
    Impact impact();

    /** Id of a {@link BoolSpec} on the SAME page whose (staged) value gates this option, or null. */
    String enabledBy();

    SaveHook saveHook();

    Visibility visibility();

    /** Applies the option's current config value to a renderer-typed consumer (test helper). */
    Object read(LSSClientConfig cfg);

    record BoolSpec(String id, String nameKey, Tooltip tooltip, Impact impact,
                    boolean defaultValue,
                    BiConsumer<LSSClientConfig, Boolean> setter,
                    Function<LSSClientConfig, Boolean> getter,
                    String enabledBy, SaveHook saveHook, Visibility visibility)
            implements OptionSpec {

        public BoolSpec {
            requireCommon(id, nameKey, tooltip, saveHook, visibility);
            Objects.requireNonNull(setter);
            Objects.requireNonNull(getter);
        }

        @Override
        public Object read(LSSClientConfig cfg) {
            return getter.apply(cfg);
        }

        public static Builder builder(String id) {
            return new Builder(id);
        }

        public static final class Builder {
            private final String id;
            private String nameKey;
            private Tooltip tooltip;
            private Impact impact;
            private boolean defaultValue;
            private BiConsumer<LSSClientConfig, Boolean> setter;
            private Function<LSSClientConfig, Boolean> getter;
            private String enabledBy;
            private SaveHook saveHook = SaveHook.SAVE;
            private Visibility visibility = Visibility.ALWAYS;

            private Builder(String id) {
                this.id = id;
            }

            public Builder name(String key) { this.nameKey = key; return this; }
            public Builder tooltip(Tooltip t) { this.tooltip = t; return this; }
            public Builder tooltip(String key) { return tooltip(Tooltip.fixed(key)); }
            public Builder impact(Impact i) { this.impact = i; return this; }
            public Builder defaultValue(boolean v) { this.defaultValue = v; return this; }
            public Builder bind(Function<LSSClientConfig, Boolean> getter,
                                BiConsumer<LSSClientConfig, Boolean> setter) {
                this.getter = getter;
                this.setter = setter;
                return this;
            }
            public Builder enabledBy(String optionId) { this.enabledBy = optionId; return this; }
            public Builder saveHook(SaveHook h) { this.saveHook = h; return this; }
            public Builder visibility(Visibility v) { this.visibility = v; return this; }

            public BoolSpec build() {
                return new BoolSpec(id, nameKey, tooltip, impact, defaultValue, setter, getter,
                        enabledBy, saveHook, visibility);
            }
        }
    }

    /**
     * An integer slider over {@code [min, max]} in {@code step}s. The slider's value
     * domain is whatever the binding says it is — the curved rate slider binds an INDEX
     * into {@link RateSliderStops#STOPS} and maps to the rate inside its setter/getter, so
     * renderers never know about the curve. {@code label} names a slider value for
     * display (e.g. 0 → "Server Default").
     */
    record IntSpec(String id, String nameKey, Tooltip tooltip, Impact impact,
                   int defaultValue, int min, int max, int step,
                   IntFunction<Label> label,
                   BiConsumer<LSSClientConfig, Integer> setter,
                   Function<LSSClientConfig, Integer> getter,
                   String enabledBy, SaveHook saveHook, Visibility visibility)
            implements OptionSpec {

        public IntSpec {
            requireCommon(id, nameKey, tooltip, saveHook, visibility);
            Objects.requireNonNull(label);
            Objects.requireNonNull(setter);
            Objects.requireNonNull(getter);
            if (min > max || step <= 0) {
                throw new IllegalArgumentException(id + ": bad slider domain " + min + ".." + max + " step " + step);
            }
        }

        @Override
        public Object read(LSSClientConfig cfg) {
            return getter.apply(cfg);
        }

        public static Builder builder(String id) {
            return new Builder(id);
        }

        public static final class Builder {
            private final String id;
            private String nameKey;
            private Tooltip tooltip;
            private Impact impact;
            private int defaultValue;
            private int min;
            private int max;
            private int step = 1;
            private IntFunction<Label> label = Label::number;
            private BiConsumer<LSSClientConfig, Integer> setter;
            private Function<LSSClientConfig, Integer> getter;
            private String enabledBy;
            private SaveHook saveHook = SaveHook.SAVE;
            private Visibility visibility = Visibility.ALWAYS;

            private Builder(String id) {
                this.id = id;
            }

            public Builder name(String key) { this.nameKey = key; return this; }
            public Builder tooltip(Tooltip t) { this.tooltip = t; return this; }
            public Builder tooltip(String key) { return tooltip(Tooltip.fixed(key)); }
            public Builder impact(Impact i) { this.impact = i; return this; }
            public Builder defaultValue(int v) { this.defaultValue = v; return this; }
            public Builder range(int min, int max, int step) {
                this.min = min;
                this.max = max;
                this.step = step;
                return this;
            }
            public Builder label(IntFunction<Label> f) { this.label = f; return this; }
            public Builder bind(Function<LSSClientConfig, Integer> getter,
                                BiConsumer<LSSClientConfig, Integer> setter) {
                this.getter = getter;
                this.setter = setter;
                return this;
            }
            public Builder enabledBy(String optionId) { this.enabledBy = optionId; return this; }
            public Builder saveHook(SaveHook h) { this.saveHook = h; return this; }
            public Builder visibility(Visibility v) { this.visibility = v; return this; }

            public IntSpec build() {
                return new IntSpec(id, nameKey, tooltip, impact, defaultValue, min, max, step, label,
                        setter, getter, enabledBy, saveHook, visibility);
            }
        }
    }

    private static void requireCommon(String id, String nameKey, Tooltip tooltip,
                                      SaveHook saveHook, Visibility visibility) {
        Objects.requireNonNull(id, "id");
        if (!id.startsWith("lss:")) {
            throw new IllegalArgumentException("option id must be lss:-namespaced: " + id);
        }
        Objects.requireNonNull(nameKey, id + ": nameKey");
        Objects.requireNonNull(tooltip, id + ": tooltip");
        Objects.requireNonNull(saveHook, id + ": saveHook");
        Objects.requireNonNull(visibility, id + ": visibility");
    }
}
