package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The R-10 graceful-degrade ladder for mount rendering (mega plan R-10 v1.3 — the
 * user's explicit requirement: modded mounts degrade, never crash):
 *
 * <ol>
 *   <li>Identity unresolvable on this client (modded / other-MC-version entity) →
 *       render the player UNMOUNTED at their own snapshot position. Resolution MUST
 *       use {@code getOptional}: {@code BuiltInRegistries.ENTITY_TYPE} is a
 *       DefaultedRegistry whose plain lookup returns PIG for unknown ids — SeeU has
 *       exactly this bug (their null-check is dead code). Pinned by a NON-injected
 *       Tier 1 test against the real registry, so injected-resolver tests cannot
 *       mask a regression to {@code getValue}.</li>
 *   <li>Type resolves but creation returns null (non-factory types, some modded) or
 *       THROWS → per-type once-latch, warn once naming the type, unmounted.</li>
 *   <li>Ride-link establishment failure is handled at the link site (the renderer):
 *       mounted-position standing pose, never a floating sit.</li>
 * </ol>
 *
 * <p>Client main thread only. Seams injected for Tier 1; production wiring in
 * {@link #production()}.
 */
final class FarPlayerMountLadder {

    private final Function<String, Optional<EntityType<?>>> typeResolver;
    private final BiFunction<EntityType<?>, ClientLevel, Entity> factory;
    /** Per-type once-latch: a failed type never retries (permanent for the session)
     *  and never warns twice. */
    private final Set<String> latchedTypes = new HashSet<>();

    FarPlayerMountLadder(Function<String, Optional<EntityType<?>>> typeResolver,
                         BiFunction<EntityType<?>, ClientLevel, Entity> factory) {
        this.typeResolver = typeResolver;
        this.factory = factory;
    }

    static FarPlayerMountLadder production() {
        return new FarPlayerMountLadder(
                FarPlayerMountLadder::resolveTypeStrict,
                (type, level) -> type.create(level,
                        net.minecraft.world.entity.EntitySpawnReason.LOAD));
    }

    /** Rung 1's resolver: getOptional, NEVER getValue (the DefaultedRegistry PIG
     *  trap). Malformed identities resolve empty like unknown ones. */
    static Optional<EntityType<?>> resolveTypeStrict(String identity) {
        try {
            Identifier id = Identifier.tryParse(identity);
            if (id == null) return Optional.empty();
            return BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Runs rungs 1-2 for one mount. Returns the render-only instance, or null =
     * render the rider unmounted (the always-well-defined fallback). The caller owns
     * entity-id assignment and render flags.
     */
    Entity createMount(String typeIdentity, ClientLevel level) {
        if (latchedTypes.contains(typeIdentity)) return null;
        try {
            Optional<EntityType<?>> type = typeResolver.apply(typeIdentity);
            if (type.isEmpty()) {
                latchWarn(typeIdentity,
                        "unknown on this client (modded or other-MC-version mount)");
                return null;
            }
            Entity entity = factory.apply(type.get(), level);
            if (entity == null) {
                latchWarn(typeIdentity, "type is not creatable client-side");
                return null;
            }
            return entity;
        } catch (Exception e) {
            latchWarn(typeIdentity, "creation failed (" + e + ")");
            return null;
        }
    }

    boolean isLatched(String typeIdentity) {
        return latchedTypes.contains(typeIdentity);
    }

    private void latchWarn(String typeIdentity, String reason) {
        if (latchedTypes.add(typeIdentity)) {
            LSSLogger.warn("Far-player mount '" + typeIdentity + "' will render as an"
                    + " unmounted player: " + reason + " — once per type per session");
        }
    }
}
