package net.caffeinemc.mods.sodium.client.gui.options.control;

import net.caffeinemc.mods.sodium.client.gui.options.Option;

public class TickBoxControl implements Control<Boolean> {
    private final Option<Boolean> option;

    public TickBoxControl(Option<Boolean> option) {
        this.option = option;
    }

    @Override
    public Option<Boolean> getOption() {
        return option;
    }

    @Override
    public int getMaxWidth() {
        return 30;
    }
}
