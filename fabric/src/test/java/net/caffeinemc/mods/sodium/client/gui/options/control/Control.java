package net.caffeinemc.mods.sodium.client.gui.options.control;

import net.caffeinemc.mods.sodium.client.gui.options.Option;

public interface Control<T> {
    Option<T> getOption();
    int getMaxWidth();
}
