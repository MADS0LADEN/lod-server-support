package net.caffeinemc.mods.sodium.client.gui.options;

import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;

/** Stub of Sodium 0.6/0.7's option interface (the public surface LegacySodiumPage touches). */
public interface Option<T> {
    Component getName();
    Component getTooltip();
    OptionImpact getImpact();
    Control<T> getControl();
    T getValue();
    void setValue(T value);
    void reset();
    OptionStorage<?> getStorage();
    boolean isAvailable();
    boolean hasChanged();
    void applyChanges();
}
