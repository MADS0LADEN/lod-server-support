package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.Range;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IntegerOptionBuilder extends StatefulOptionBuilder<Integer> {
    @Override
    IntegerOptionBuilder setDefaultValue(Integer value);

    @Override
    IntegerOptionBuilder setBinding(Consumer<Integer> setter, Supplier<Integer> getter);

    IntegerOptionBuilder setRange(Range range);

    IntegerOptionBuilder setValueFormatter(ControlValueFormatter formatter);
}
