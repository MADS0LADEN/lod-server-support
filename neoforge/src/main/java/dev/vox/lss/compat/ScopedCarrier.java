package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@link ScopedValue} half of the AntiXray crash shim (design:
 * docs/planning/antixray-compat-design.md §2), split out of {@code AntiXrayCompat}
 * at V-2/S5 (version-port-isolation-plan.md §3): {@code ScopedValue} is Java-25-only
 * API, so this class is VERSION-VOLATILE — it lives in the PER-LOADER trees as
 * byte-identical same-FQN twins (fabric + neoforge; pinned identical by
 * {@code NeoForgeModuleContractTest}), and a Java-21 support line replaces the whole
 * file with a pass-through variant instead of flavoring shared xplat source.
 * {@code AntiXrayCompat} (xplat, ScopedValue-free) delegates here; the engine probe
 * stays with it. Keep this file loader-free and MC-free so the twins stay identical.
 *
 * <p>AntiXray threads its obfuscation context through {@link ScopedValue}s
 * ({@code me.drex.antixray.common.util.Arguments}) that are bound only inside vanilla
 * chunk-packet construction, and its {@code PalettedContainer} mixins read two of them
 * with raw {@code .get()} — which throws {@link java.util.NoSuchElementException} when
 * unbound. Every LSS {@code section.write(buf)} runs outside that scope. This shim
 * binds the values to {@code null} around LSS's serialization choke points, routing
 * those mixins onto their benign {@code packetInfo == null} skip path — the same null
 * AntiXray's own {@code LevelChunkSectionMixin} binds for the biome write, so the
 * semantics are theirs, not ours.
 */
public final class ScopedCarrier {
    private ScopedCarrier() {}

    /**
     * The {@code Arguments} fields bound to null. PACKET_INFO + CHUNK_SECTION_INDEX are the
     * two read raw on the section write path today (both null-safe by AntiXray's own
     * {@code packetInfo != null} guard); PALETTE_ENTRIES + PRESET_VALUES are bound as
     * future-proofing — their current readers either check {@code isBound()} or bind their
     * own inner values, which win over ours. CAVEAT for the next AntiXray version bump:
     * binding a value of NULL makes {@code isBound()} TRUE-with-null — a future reader
     * that guards with isBound() and then dereferences would NPE instead of taking its
     * benign skip path. Verified safe against 1.4.16 only; re-check on upgrade.
     */
    static final String[] BOUND_FIELDS = {
            "PACKET_INFO", "CHUNK_SECTION_INDEX", "PALETTE_ENTRIES", "PRESET_VALUES"};

    /** Resolves AntiXray's ScopedValue instances — test seam for {@link #buildCarrier}. */
    @FunctionalInterface
    interface ArgumentsResolver {
        List<ScopedValue<?>> resolve() throws Throwable;
    }

    // Resolved once at first use (the first column serialization); the statics make the
    // inactive path a JIT-erasable constant check. Warn-once on failure is structural: a
    // static initializer runs exactly once.
    private static final ScopedValue.Carrier CARRIER = buildCarrierProduction();
    private static final boolean ACTIVE = CARRIER != null;

    /**
     * The initializer must be provably throw-free: an escaped throwable here would poison
     * the class (ExceptionInInitializerError → NoClassDefFoundError on every later
     * serialize), which the disk path does NOT contain. buildCarrier floors its own ladder;
     * this floors the loader lookup and the catch-path logging themselves.
     */
    private static ScopedValue.Carrier buildCarrierProduction() {
        try {
            return buildCarrier(dev.vox.lss.platform.LoaderServices.get().isModLoaded("antixray"),
                    ScopedCarrier::resolveArgumentsScopedValues);
        } catch (Throwable t) {
            return null;   // can't log — logging is part of what may have failed
        }
    }

    /**
     * Runs {@code body} with AntiXray's obfuscation-context ScopedValues bound to null when
     * the shim is active, or calls straight through otherwise. Callers bind once per COLUMN
     * (wrap the whole serialize call, not each section) — one carrier scope per column keeps
     * the cost negligible.
     */
    public static <T> T callSerializing(Supplier<T> body) {
        if (!ACTIVE) return body.get();
        return CARRIER.<T, RuntimeException>call(body::get);
    }

    /**
     * The activation ladder, injectable for tests: mod absent → inactive silently (the
     * normal case, resolver untouched); resolver throwable or an empty resolution →
     * inactive + warning; else a reusable carrier binding every resolved value to null.
     */
    static ScopedValue.Carrier buildCarrier(boolean modLoaded, ArgumentsResolver resolver) {
        if (!modLoaded) return null;
        try {
            List<ScopedValue<?>> values = resolver.resolve();
            ScopedValue.Carrier carrier = null;
            for (ScopedValue<?> sv : values) {
                @SuppressWarnings("unchecked")
                var key = (ScopedValue<Object>) sv;
                carrier = carrier == null
                        ? ScopedValue.where(key, null)
                        : carrier.where(key, null);
            }
            if (carrier == null) {
                throw new IllegalStateException("no ScopedValue instances resolved");
            }
            LSSLogger.info("AntiXray detected — serialization crash shim active "
                    + "(obfuscation context neutralized around LOD serialization)");
            return carrier;
        } catch (Throwable t) {
            LSSLogger.warn("AntiXray is installed but its obfuscation context could not be "
                    + "resolved — the LSS crash shim is INACTIVE for this session. LOD "
                    + "serving will likely fail on this AntiXray version (contained as "
                    + "blank LODs, not a crash).", t);
            return null;
        }
    }

    /** Production resolver: the four {@link #BOUND_FIELDS} statics of AntiXray's Arguments. */
    static List<ScopedValue<?>> resolveArgumentsScopedValues() throws Exception {
        Class<?> arguments = Class.forName("me.drex.antixray.common.util.Arguments");
        var values = new ArrayList<ScopedValue<?>>(BOUND_FIELDS.length);
        for (String name : BOUND_FIELDS) {
            Object value = arguments.getField(name).get(null);
            if (!(value instanceof ScopedValue<?> sv)) {
                throw new IllegalStateException("Arguments." + name + " is not a ScopedValue: "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            values.add(sv);
        }
        return values;
    }
}
