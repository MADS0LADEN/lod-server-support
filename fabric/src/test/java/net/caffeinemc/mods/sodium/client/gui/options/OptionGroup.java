package net.caffeinemc.mods.sodium.client.gui.options;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

public class OptionGroup {
    private final ImmutableList<Option<?>> options;

    private OptionGroup(ImmutableList<Option<?>> options) {
        this.options = options;
    }

    public static Builder createBuilder() {
        return new Builder();
    }

    public ImmutableList<Option<?>> getOptions() {
        return options;
    }

    public static class Builder {
        private final List<Option<?>> options = new ArrayList<>();

        public Builder add(Option<?> option) {
            options.add(option);
            return this;
        }

        public OptionGroup build() {
            // Real 0.6.13/0.7.3: Validate.notEmpty(options, "At least one option must be specified")
            // — an empty group throws out of the whole page build, so the renderers' skip-empty-
            // group guard is load-bearing and this stub must red without it.
            if (options.isEmpty()) {
                throw new IllegalArgumentException("At least one option must be specified");
            }
            return new OptionGroup(ImmutableList.copyOf(options));
        }
    }
}
