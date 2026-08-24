package net.caffeinemc.mods.sodium.client.gui.options;

import com.google.common.collect.ImmutableList;
import net.minecraft.network.chat.Component;

public class OptionPage {
    private final Component name;
    private final ImmutableList<OptionGroup> groups;
    private final ImmutableList<Option<?>> options;

    public OptionPage(Component name, ImmutableList<OptionGroup> groups) {
        this.name = name;
        this.groups = groups;
        var all = ImmutableList.<Option<?>>builder();
        for (OptionGroup g : groups) {
            all.addAll(g.getOptions());
        }
        this.options = all.build();
    }

    public ImmutableList<OptionGroup> getGroups() {
        return groups;
    }

    public ImmutableList<Option<?>> getOptions() {
        return options;
    }

    public Component getName() {
        return name;
    }
}
