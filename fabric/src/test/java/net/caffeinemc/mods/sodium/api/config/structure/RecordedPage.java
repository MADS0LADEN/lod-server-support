package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class RecordedPage implements OptionPageBuilder {
    public Component name;
    public final List<RecordedGroup> groups = new ArrayList<>();

    @Override
    public OptionPageBuilder setName(Component name) {
        this.name = name;
        return this;
    }

    @Override
    public OptionPageBuilder addOptionGroup(OptionGroupBuilder group) {
        groups.add((RecordedGroup) group);
        return this;
    }
}
