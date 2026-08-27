package me.lucko.fabric.api.permissions.v0;

import net.minecraft.world.entity.Entity;

import java.util.function.BiFunction;

/**
 * Test stub of fabric-permissions-api's {@code Permissions}, mirroring the surface
 * {@code FabricPermissionsBridge} resolves BY SHAPE: the static
 * {@code check(Entity, String, boolean)} overload (the real class also carries
 * CommandSource/TriState variants the bridge must skip — represented here by the
 * two decoys). Control {@link #behavior}; reset between tests.
 */
public interface Permissions {

    /** (node, defaultValue) -> answer; null = echo the default. Throwing simulates a
     *  broken provider. */
    BiFunction<String, Boolean, Boolean>[] BEHAVIOR = new BiFunction[1];

    static boolean check(Entity entity, String permission, boolean defaultValue) {
        var b = BEHAVIOR[0];
        return b == null ? defaultValue : b.apply(permission, defaultValue);
    }

    /** Decoy overloads the by-shape resolve must NOT match. */
    static java.util.concurrent.CompletableFuture<Boolean> check(java.util.UUID uuid, String permission, boolean defaultValue) {
        throw new AssertionError("the bridge must not bind the UUID overload");
    }

    static boolean check(Entity entity, String permission) {
        throw new AssertionError("the bridge must not bind the 2-arg overload");
    }
}
