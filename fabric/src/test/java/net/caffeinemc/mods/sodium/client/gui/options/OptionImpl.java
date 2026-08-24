package net.caffeinemc.mods.sodium.client.gui.options;

import net.caffeinemc.mods.sodium.client.gui.options.binding.OptionBinding;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Stub of Sodium 0.6.13's {@code OptionImpl} with the REAL staging semantics (verified by
 * javap, 2026-08-23): {@code getValue()} returns the STAGED value, {@code isAvailable()}
 * re-evaluates the supplier, and {@code applyChanges()} writes the binding only — the
 * SCREEN saves each distinct storage (see LegacySodiumPageTest's apply-contract case).
 * {@link #FAIL_BUILD} is the test lever for the "throwing Sodium" degrade path.
 */
public class OptionImpl<S, T> implements Option<T> {
    public static volatile boolean FAIL_BUILD;

    private final OptionStorage<S> storage;
    private final OptionBinding<S, T> binding;
    private final Control<T> control;
    private final Component name;
    private final Component tooltip;
    private final OptionImpact impact;
    private final BooleanSupplier enabled;
    private T value;
    private T modifiedValue;

    private OptionImpl(OptionStorage<S> storage, Component name, Component tooltip,
                       OptionBinding<S, T> binding, Function<OptionImpl<S, T>, Control<T>> control,
                       OptionImpact impact, BooleanSupplier enabled) {
        this.storage = storage;
        this.name = name;
        this.tooltip = tooltip;
        this.binding = binding;
        this.impact = impact;
        this.enabled = enabled;
        this.control = control.apply(this);
        this.reset();
    }

    public static <S, T> Builder<S, T> createBuilder(Class<T> type, OptionStorage<S> storage) {
        return new Builder<>(storage);
    }

    @Override public Component getName() { return name; }
    @Override public Component getTooltip() { return tooltip; }
    @Override public OptionImpact getImpact() { return impact; }
    @Override public Control<T> getControl() { return control; }
    @Override public T getValue() { return modifiedValue; }
    @Override public void setValue(T value) { this.modifiedValue = value; }
    @Override public void reset() { this.value = binding.getValue(storage.getData()); this.modifiedValue = this.value; }
    @Override public OptionStorage<?> getStorage() { return storage; }
    @Override public boolean isAvailable() { return enabled.getAsBoolean(); }
    @Override public boolean hasChanged() { return !Objects.equals(value, modifiedValue); }
    @Override public void applyChanges() { binding.setValue(storage.getData(), modifiedValue); this.value = modifiedValue; }

    public static class Builder<S, T> {
        private final OptionStorage<S> storage;
        private Component name;
        private Component tooltip;
        private OptionBinding<S, T> binding;
        private Function<OptionImpl<S, T>, Control<T>> control;
        private OptionImpact impact;
        private BooleanSupplier enabled = () -> true;

        private Builder(OptionStorage<S> storage) {
            this.storage = storage;
        }

        public Builder<S, T> setName(Component name) { this.name = name; return this; }
        /** Sodium 0.7's SECOND overload, declared FIRST so an arity-only resolver would bind
         *  it: invoking it with a Component fails loudly (the resolver must prefer the
         *  Component overload — LegacySodiumPageTest pins that). */
        public Builder<S, T> setTooltip(Function<T, Component> tooltip) {
            throw new IllegalStateException("stub: the Function<T,Component> setTooltip overload was bound"
                    + " — the resolver must prefer setTooltip(Component)");
        }
        public Builder<S, T> setTooltip(Component tooltip) { this.tooltip = tooltip; return this; }
        public Builder<S, T> setBinding(BiConsumer<S, T> setter, Function<S, T> getter) {
            this.binding = new OptionBinding<>() {
                @Override public void setValue(S s, T v) { setter.accept(s, v); }
                @Override public T getValue(S s) { return getter.apply(s); }
            };
            return this;
        }
        public Builder<S, T> setBinding(OptionBinding<S, T> binding) { this.binding = binding; return this; }
        public Builder<S, T> setControl(Function<OptionImpl<S, T>, Control<T>> control) { this.control = control; return this; }
        public Builder<S, T> setImpact(OptionImpact impact) { this.impact = impact; return this; }
        public Builder<S, T> setEnabled(BooleanSupplier enabled) { this.enabled = enabled; return this; }

        public OptionImpl<S, T> build() {
            if (FAIL_BUILD) {
                throw new IllegalStateException("stub: simulated Sodium build failure");
            }
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(control, "control");
            return new OptionImpl<>(storage, name, tooltip, binding, control, impact, enabled);
        }
    }
}
