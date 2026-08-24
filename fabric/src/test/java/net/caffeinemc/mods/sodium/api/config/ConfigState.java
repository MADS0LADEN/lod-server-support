package net.caffeinemc.mods.sodium.api.config;

import net.minecraft.resources.Identifier;

public interface ConfigState {
    boolean readBooleanOption(Identifier id);

    int readIntOption(Identifier id);
}
