package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface BooleanOptionBuilder extends StatefulOptionBuilder<Boolean> {
    @Override
    BooleanOptionBuilder setDefaultValue(Boolean value);

    @Override
    BooleanOptionBuilder setBinding(Consumer<Boolean> setter, Supplier<Boolean> getter);
}
