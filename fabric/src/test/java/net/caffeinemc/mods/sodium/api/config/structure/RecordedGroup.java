package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.ArrayList;
import java.util.List;

public class RecordedGroup implements OptionGroupBuilder {
    public final List<RecordedOption<?>> options = new ArrayList<>();

    @Override
    public OptionGroupBuilder addOption(OptionBuilder option) {
        options.add((RecordedOption<?>) option);
        return this;
    }
}
