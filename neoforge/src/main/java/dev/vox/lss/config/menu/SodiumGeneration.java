package dev.vox.lss.config.menu;

import java.util.function.Predicate;

/**
 * Which Sodium options API this client carries (sodium-options-page-generations-plan.md
 * D2) — a RESOURCE probe, never a class load:
 * <ul>
 *   <li>{@code MODERN}: Sodium 0.8+ — the public config API
 *       ({@code …/api/config/ConfigEntryPoint.class} is on the class path); the
 *       {@code sodium:config_api_user} entrypoint renders the catalog;</li>
 *   <li>{@code LEGACY}: Sodium 0.5/0.6/0.7 — only the internal options screen
 *       ({@code …/client/gui/SodiumOptionsGUI.class}); the constructor mixin renders
 *       the catalog through the reflective builder. The two classes are mutually
 *       exclusive across every Sodium release (0.8 deleted the legacy screen), which
 *       is what makes this probe unambiguous;</li>
 *   <li>{@code NONE}: no Sodium (or an unrecognized one) — no page, config files only.</li>
 * </ul>
 *
 * <p>WHY A RESOURCE LOOKUP: Mixin reads every config's declared targets while PREPARING
 * the configs (the first transform after the DEFAULT phase, before any mod entrypoint)
 * and refuses a target that is ALREADY LOADED; a {@code Class.forName} of the legacy
 * screen from inside that window — a config plugin's {@code onLoad}/{@code shouldApplyMixin},
 * the v1.0 design — defines the class through the transforming loader BEFORE our
 * constructor hook is attached: the hook never applies, no crash, no page, and no stub
 * test can see it (the plan review's A-1/B-2). A probe that runs later (entrypoint or
 * click time) is safe, but a resource lookup is safe EVERYWHERE: {@code getResource}
 * defines nothing, runs no static initializer, and — being a {@code .class} resource —
 * is exempt from JPMS encapsulation on NeoForge's module layer.
 * {@code SodiumGenerationTest} pins that this file contains no class load.
 *
 * <p>Same-FQN TWIN in the neoforge tree — keep byte-identical (pinned).
 */
public final class SodiumGeneration {

    public enum Kind { MODERN, LEGACY, NONE }

    /** Sodium 0.6+ package root. */
    public static final String CAFFEINE_PREFIX = "net.caffeinemc.mods.sodium";
    /** Sodium 0.5 package root (the frozen 1.20.1 line; probed for completeness — NOTE a
     *  revived 1.20.1 also needs a second {@code SodiumLegacyOptionsHook} target for this
     *  prefix and the contract test widened; the probe alone does not light the page). */
    public static final String JELLYSQUID_PREFIX = "me.jellysquid.mods.sodium";
    /** Relative to a prefix: the legacy options screen (0.5-0.7). */
    public static final String LEGACY_SCREEN_SUFFIX = ".client.gui.SodiumOptionsGUI";
    /** The 0.8+ public config-API entry point (absolute — only the caffeine package has it). */
    public static final String MODERN_ENTRY_POINT = CAFFEINE_PREFIX + ".api.config.ConfigEntryPoint";

    /** The memoized answer: the generation plus, for LEGACY, the package prefix it lives under. */
    public record Detected(Kind kind, String legacyPrefix) {
        static final Detected NONE = new Detected(Kind.NONE, null);
    }

    private static volatile Detected detected;

    private SodiumGeneration() {
    }

    public static Kind detect() {
        return current().kind();
    }

    /** The package prefix of the LEGACY Sodium, or null when the generation is not LEGACY. */
    public static String legacyPrefix() {
        return current().legacyPrefix();
    }

    /**
     * The legacy prefix WITHOUT the modern short-circuit — for callers that already hold
     * proof the legacy screen exists (its own constructor hook): a foreign jar shipping
     * the 0.8 API interface beside a 0.6/0.7 Sodium would otherwise flip {@link #detect()}
     * to MODERN and silently drop the page (implementation review). Null when no legacy
     * screen resource is present under either prefix.
     */
    public static String legacyPrefixIgnoringModern() {
        return legacyPrefixWith(SodiumGeneration::resourcePresent);
    }

    static String legacyPrefixWith(Predicate<String> resourcePresent) {
        try {
            for (String prefix : new String[]{CAFFEINE_PREFIX, JELLYSQUID_PREFIX}) {
                if (resourcePresent.test(resourceOf(prefix + LEGACY_SCREEN_SUFFIX))) {
                    return prefix;
                }
            }
        } catch (Throwable t) {
            // contained — see detectWith
        }
        return null;
    }

    static Detected current() {
        Detected d = detected;
        if (d == null) {
            d = detectWith(SodiumGeneration::resourcePresent);
            detected = d;
        }
        return d;
    }

    /** The probe body with the resource lookup injected (the unit tests' seam). Never throws. */
    static Detected detectWith(Predicate<String> resourcePresent) {
        try {
            if (resourcePresent.test(resourceOf(MODERN_ENTRY_POINT))) {
                return new Detected(Kind.MODERN, null);
            }
            String legacy = legacyPrefixWith(resourcePresent);
            if (legacy != null) {
                return new Detected(Kind.LEGACY, legacy);
            }
        } catch (Throwable t) {
            // A probe must never take the client down: unknown = NONE.
        }
        return Detected.NONE;
    }

    static String resourceOf(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static boolean resourcePresent(String resource) {
        ClassLoader own = SodiumGeneration.class.getClassLoader();
        if (own != null && own.getResource(resource) != null) {
            return true;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null && context != own && context.getResource(resource) != null;
    }

    /** Test seam: forget the memoized answer. */
    static void resetForTests() {
        detected = null;
    }
}
