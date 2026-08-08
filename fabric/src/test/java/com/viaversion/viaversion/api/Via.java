package com.viaversion.viaversion.api;

import java.util.UUID;

/**
 * Real-package-name stub of ViaVersion's static entry class — see {@link ViaAPI}.
 * Tests drive {@link #API} / {@link #throwOnGetApi}; {@code ViaProbeTest} resets both.
 */
public final class Via {
    private Via() {}

    public static volatile ViaAPI<UUID> API;
    public static volatile RuntimeException throwOnGetApi;

    /** Raw return type, like upstream ({@code public static ViaAPI getAPI()}). */
    @SuppressWarnings("rawtypes")
    public static ViaAPI getAPI() {
        var t = throwOnGetApi;
        if (t != null) throw t;
        return API;
    }
}
