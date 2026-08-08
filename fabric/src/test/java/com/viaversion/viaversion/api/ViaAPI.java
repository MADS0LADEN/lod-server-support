package com.viaversion.viaversion.api;

import java.util.UUID;

/**
 * Real-package-name stub of ViaVersion's API surface (the {@code AntiXrayCompat} /
 * {@code MoonriseReadCompat} test pattern), SHAPE-FAITHFUL to upstream (review m13):
 * the real {@code ViaAPI<T>} is generic with an abstract {@code getPlayerVersion(T)}
 * (erasure {@code Object} — the overload a sloppy lookup could collide with) and a
 * <b>default</b> {@code getPlayerVersion(UUID)} — the member {@code ViaProbe}
 * resolves. Keeping both here pins that the ladder asks for the UUID overload
 * explicitly AND that {@code unreflect} dispatches through a default method, the two
 * properties the reviewer had to verify against upstream by hand.
 */
public interface ViaAPI<T> {

    int getPlayerVersion(T player);

    @SuppressWarnings("unchecked")
    default int getPlayerVersion(UUID uuid) {
        return getPlayerVersion((T) uuid);
    }
}
