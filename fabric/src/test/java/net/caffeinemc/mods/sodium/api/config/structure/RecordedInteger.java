package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class RecordedInteger extends RecordedOption<Integer> implements IntegerOptionBuilder {
    public Range range;
    public ControlValueFormatter formatter;

    RecordedInteger(Identifier id) {
        super(id);
    }

    @Override
    public IntegerOptionBuilder setDefaultValue(Integer value) {
        this.defaultValue = value;
        return this;
    }

    @Override
    public IntegerOptionBuilder setBinding(Consumer<Integer> setter, Supplier<Integer> getter) {
        this.setter = setter;
        this.getter = getter;
        return this;
    }

    @Override
    public IntegerOptionBuilder setRange(Range range) {
        this.range = range;
        return this;
    }

    @Override
    public IntegerOptionBuilder setValueFormatter(ControlValueFormatter formatter) {
        this.formatter = formatter;
        return this;
    }
}
