package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the AntiXray crash-shim activation ladder (docs/planning/antixray-compat-design.md
 * §2 A1) via the injected resolver seam — the {@link ScopedCarrier} half of the split
 * (V-2/S5): mod-absent stays inactive without touching the resolver, any resolution
 * throwable (AntiXray refactoring its Arguments class) degrades to inactive instead of
 * propagating, and the active path genuinely binds every resolved ScopedValue to null for
 * exactly the duration of the carrier call — the null routing AntiXray's own mixins treat
 * as "no packet context". A Java-21 line replaces {@code ScopedCarrier} with a
 * pass-through and this suite with its pass-through pin (whole-file swaps, both).
 */
class ScopedCarrierTest {

    @Test
    void modAbsentIsInactiveWithoutResolving() {
        var resolved = new AtomicBoolean();
        assertNull(ScopedCarrier.buildCarrier(false, () -> {
            resolved.set(true);
            return List.of();
        }));
        assertFalse(resolved.get(), "mod-absent must not touch the resolver");
    }

    @Test
    void resolverExceptionDegradesToInactive() {
        assertNull(ScopedCarrier.buildCarrier(true, () -> {
            throw new NoSuchFieldException("PACKET_INFO");
        }));
    }

    @Test
    void resolverLinkageErrorDegradesToInactive() {
        assertNull(ScopedCarrier.buildCarrier(true, () -> {
            throw new NoClassDefFoundError("me/drex/antixray/common/util/Arguments");
        }), "LinkageErrors from a broken AntiXray install must degrade, not propagate");
    }

    @Test
    void emptyResolutionIsInactive() {
        assertNull(ScopedCarrier.buildCarrier(true, List::of),
                "zero resolved values would build a carrier that binds nothing — must refuse");
    }

    @Test
    void productionResolverWithoutAntiXrayDegradesToInactive() {
        // The real resolver in a JVM without the mod on the classpath: Class.forName throws
        // and the ladder lands on the inactive rung — the mod-loaded-but-unresolvable shape.
        assertNull(ScopedCarrier.buildCarrier(true,
                ScopedCarrier::resolveArgumentsScopedValues));
    }

    @Test
    void activeCarrierBindsEveryValueToNullForTheCallOnly() {
        ScopedValue<Object> a = ScopedValue.newInstance();
        ScopedValue<Object> b = ScopedValue.newInstance();
        var carrier = ScopedCarrier.buildCarrier(true, () -> List.of(a, b));
        assertNotNull(carrier);

        assertFalse(a.isBound(), "no binding may leak outside the carrier call");
        String result = carrier.<String, RuntimeException>call(() -> {
            assertTrue(a.isBound(), "every resolved value must be bound inside the call");
            assertTrue(b.isBound(), "every resolved value must be bound inside the call");
            assertNull(a.get(), "the binding must be null — AntiXray's own benign skip value");
            assertNull(b.get(), "the binding must be null — AntiXray's own benign skip value");
            return "ran";
        });
        assertEquals("ran", result, "the body's return value must pass through");
        assertFalse(a.isBound(), "the binding must end with the call");
        assertFalse(b.isBound(), "the binding must end with the call");
    }

    @Test
    void activeCarrierIsExceptionTransparentAndUnwinds() {
        // The path production relies on when a serialize throws for any non-AntiXray reason
        // while the shim is active: the exception must reach the probe containment OUTSIDE
        // the carrier scope, unchanged, with the bindings unwound.
        ScopedValue<Object> a = ScopedValue.newInstance();
        var carrier = ScopedCarrier.buildCarrier(true, () -> List.of(a));
        assertNotNull(carrier);
        var thrown = assertThrows(IllegalStateException.class,
                () -> carrier.<String, RuntimeException>call(() -> {
                    throw new IllegalStateException("serialize failed");
                }));
        assertEquals("serialize failed", thrown.getMessage());
        assertFalse(a.isBound(), "a throwing body must still unwind the binding");
    }

    @Test
    void callSerializingPassesThroughWhenInactive() {
        // This JVM has no antixray mod, so the production static is inactive by
        // construction — pins the zero-overhead pass-through every test/production
        // environment without the mod runs on.
        assertEquals("through", ScopedCarrier.callSerializing(() -> "through"));
    }
}
