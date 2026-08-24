package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

public interface ModOptionsBuilder {
    ModOptionsBuilder setIcon(Identifier icon);

    ModOptionsBuilder addPage(PageBuilder page);
}
