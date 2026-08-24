package net.caffeinemc.mods.sodium.api.config;

import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

/** Stub (INTERFACE, as in the real API — the walker's bytecode uses invokeinterface). */
public interface ConfigEntryPoint {
    default void registerConfigEarly(ConfigBuilder builder) {
    }

    void registerConfigLate(ConfigBuilder builder);
}
