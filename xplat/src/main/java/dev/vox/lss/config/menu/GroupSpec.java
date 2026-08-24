package dev.vox.lss.config.menu;

import java.util.List;

/** A visual group of options (a separated block on the page). Order is display order. */
public record GroupSpec(List<OptionSpec> options) {

    public GroupSpec {
        options = List.copyOf(options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("empty option group");
        }
    }

    public static GroupSpec of(OptionSpec... options) {
        return new GroupSpec(List.of(options));
    }
}
