package net.caffeinemc.mods.sodium.client.gui.options.control;

import net.caffeinemc.mods.sodium.client.gui.options.Option;

public class SliderControl implements Control<Integer> {
    private final Option<Integer> option;
    private final int min;
    private final int max;
    private final int interval;
    private final ControlValueFormatter mode;

    public SliderControl(Option<Integer> option, int min, int max, int interval, ControlValueFormatter mode) {
        this.option = option;
        this.min = min;
        this.max = max;
        this.interval = interval;
        this.mode = mode;
    }

    @Override
    public Option<Integer> getOption() {
        return option;
    }

    @Override
    public int getMaxWidth() {
        return 130;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public int interval() {
        return interval;
    }

    public ControlValueFormatter formatter() {
        return mode;
    }
}
