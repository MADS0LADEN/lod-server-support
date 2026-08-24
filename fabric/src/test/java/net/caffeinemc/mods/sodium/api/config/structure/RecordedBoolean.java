package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class RecordedBoolean extends RecordedOption<Boolean> implements BooleanOptionBuilder {
    RecordedBoolean(Identifier id) {
        super(id);
    }

    @Override
    public BooleanOptionBuilder setDefaultValue(Boolean value) {
        this.defaultValue = value;
        return this;
    }

    @Override
    public BooleanOptionBuilder setBinding(Consumer<Boolean> setter, Supplier<Boolean> getter) {
        this.setter = setter;
        this.getter = getter;
        return this;
    }
}
