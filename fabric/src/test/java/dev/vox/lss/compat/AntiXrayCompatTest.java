package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the AntiXray crash-shim activation ladder (docs/planning/antixray-compat-design.md
 * §2 A1) via the injected resolver seam: mod-absent stays inactive without touching the
 * resolver, any resolution throwable (AntiXray refactoring its Arguments class) degrades to
 * inactive instead of propagating, and the active path genuinely binds every resolved
 * ScopedValue to null for exactly the duration of the carrier call — the null routing
 * AntiXray's own mixins treat as "no packet context".
 */
class AntiXrayCompatTest {

    @Test
    void modAbsentIsInactiveWithoutResolving() {
        var resolved = new AtomicBoolean();
        assertNull(AntiXrayCompat.buildCarrier(false, () -> {
            resolved.set(true);
            return List.of();
        }));
        assertFalse(resolved.get(), "mod-absent must not touch the resolver");
    }

    @Test
    void resolverExceptionDegradesToInactive() {
        assertNull(AntiXrayCompat.buildCarrier(true, () -> {
            throw new NoSuchFieldException("PACKET_INFO");
        }));
    }

    @Test
    void resolverLinkageErrorDegradesToInactive() {
        assertNull(AntiXrayCompat.buildCarrier(true, () -> {
            throw new NoClassDefFoundError("me/drex/antixray/common/util/Arguments");
        }), "LinkageErrors from a broken AntiXray install must degrade, not propagate");
    }

    @Test
    void emptyResolutionIsInactive() {
        assertNull(AntiXrayCompat.buildCarrier(true, List::of),
                "zero resolved values would build a carrier that binds nothing — must refuse");
    }

    @Test
    void productionResolverWithoutAntiXrayDegradesToInactive() {
        // The real resolver in a JVM without the mod on the classpath: Class.forName throws
        // and the ladder lands on the inactive rung — the mod-loaded-but-unresolvable shape.
        assertNull(AntiXrayCompat.buildCarrier(true,
                AntiXrayCompat::resolveArgumentsScopedValues));
    }

    @Test
    void activeCarrierBindsEveryValueToNullForTheCallOnly() {
        ScopedValue<Object> a = ScopedValue.newInstance();
        ScopedValue<Object> b = ScopedValue.newInstance();
        var carrier = AntiXrayCompat.buildCarrier(true, () -> List.of(a, b));
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
        var carrier = AntiXrayCompat.buildCarrier(true, () -> List.of(a));
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
        assertEquals("through", AntiXrayCompat.callSerializing(() -> "through"));
    }

    // ---- engine probe (design §3 Detection) ----
    //
    // The test classpath carries STUB classes at AntiXray's real fully-qualified names
    // (fabric/src/test/java/me/drex/antixray/...), declared with the real mod's exact
    // member names and modifiers — so buildEngineProbe(true, Class::forName) exercises the
    // REAL resolution path: findStatic signature matching, getDeclaredField names,
    // setAccessible on the private/protected fields.

    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** Concrete HIDE-mode stub controller (mode 1) carrying staged obfuscation inputs —
     *  extends the Hide class because the probe adopts obfuscateGlobal ONLY from it. */
    private static final class StubActiveController
            extends me.drex.antixray.common.util.controller.HideChunkPacketBlockController {
        StubActiveController(java.util.Map<net.minecraft.world.level.block.state.BlockState, Boolean> entries,
                             int maxBlockHeight) {
            super(toFastutil(entries), maxBlockHeight);
        }

        private static it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap<net.minecraft.world.level.block.state.BlockState>
                toFastutil(java.util.Map<net.minecraft.world.level.block.state.BlockState, Boolean> entries) {
            var map = new it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap<net.minecraft.world.level.block.state.BlockState>();
            entries.forEach(map::put);
            return map;
        }
    }

    /** Concrete OBFUSCATE-mode stub (modes 2/3): an antiXrayBase subclass that is NOT the
     *  Hide controller — its obfuscateGlobal is the hidden ∪ replacement union and must
     *  never be adopted as a mask. */
    private static final class StubObfuscateController
            extends me.drex.antixray.common.util.controller.ChunkPacketBlockControllerAntiXray {
        StubObfuscateController(int maxBlockHeight) {
            super(new it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap<>(), maxBlockHeight);
        }
    }

    @Test
    void engineProbeModAbsentIsAbsentWithoutResolving() {
        var probe = AntiXrayCompat.buildEngineProbe(false, name -> {
            throw new AssertionError("mod-absent must not resolve classes");
        });
        assertInstanceOf(AntiXrayCompat.EngineView.Absent.class, probe.probe(null));
    }

    @Test
    void engineProbeResolutionFailureIsUnreadable() {
        var probe = AntiXrayCompat.buildEngineProbe(true, name -> {
            throw new ClassNotFoundException(name);
        });
        assertInstanceOf(AntiXrayCompat.EngineView.Unreadable.class, probe.probe(null));
    }

    @Test
    void engineProbeReadsDisabledAndActiveControllers() {
        var probe = AntiXrayCompat.buildEngineProbe(true, Class::forName);

        me.drex.antixray.common.util.Util.stagedController =
                me.drex.antixray.common.util.controller.DisabledChunkPacketBlockController.NO_OPERATION_INSTANCE;
        assertInstanceOf(AntiXrayCompat.EngineView.Disabled.class, probe.probe(null),
                "the Disabled controller means that world is anti-xray-off");

        me.drex.antixray.common.util.Util.stagedController = null;
        assertInstanceOf(AntiXrayCompat.EngineView.Unreadable.class, probe.probe(null),
                "no controller is NOT evidence the world is anti-xray-off — fail safe "
                        + "(LSS-keys masking), and without latching");

        var diamond = net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState();
        var iron = net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        // A false-valued entry mirrors the map's getOrDefault(_, false) semantics: not hidden.
        me.drex.antixray.common.util.Util.stagedController = new StubActiveController(
                java.util.Map.of(diamond, true, iron, true, stone, false), 48);

        var view = probe.probe(null);
        var active = assertInstanceOf(AntiXrayCompat.EngineView.Active.class, view);
        assertEquals(48, active.maxBlockHeight());
        assertEquals(java.util.Set.of(diamond, iron), java.util.Set.copyOf(active.hiddenStates()),
                "only true-valued obfuscateGlobal entries are the engine's hidden set");
        // The null rung above must NOT have latched: this Active read proves the probe
        // stayed live. Reset the staged stub so later tests inherit no hidden state.
        me.drex.antixray.common.util.Util.stagedController = null;
    }

    @Test
    void engineProbeObfuscateModeControllerIsReplacementNoiseNotActive() {
        // Modes 2/3 (Obfuscate/ObfuscateLayer controllers): obfuscateGlobal is the
        // hidden ∪ replacement UNION — on real configs that includes stone/deepslate/
        // dirt/sand, and adopting it flattened whole sections to their dominant
        // non-masked state (the sculk/water black-LOD report, 2026-07-27). The probe
        // must surface the mode WITHOUT the unusable list, keeping the engine height.
        var probe = AntiXrayCompat.buildEngineProbe(true, Class::forName);
        me.drex.antixray.common.util.Util.stagedController = new StubObfuscateController(256);
        try {
            var view = probe.probe(null);
            var noise = assertInstanceOf(AntiXrayCompat.EngineView.ReplacementNoise.class, view);
            assertEquals(256, noise.maxBlockHeight(),
                    "the engine's cutoff height is still adopted in modes 2/3");
        } finally {
            me.drex.antixray.common.util.Util.stagedController = null;
        }
    }

    @Test
    void engineProbeUnknownControllerFlavorLatchesUnreadable() {
        var probe = AntiXrayCompat.buildEngineProbe(true, Class::forName);
        // Implements the interface but is neither Disabled nor the AntiXray base — an
        // obfuscation flavor we cannot read must fall back, never pass as disabled.
        me.drex.antixray.common.util.Util.stagedController =
                new me.drex.antixray.common.util.controller.ChunkPacketBlockController() {};
        assertInstanceOf(AntiXrayCompat.EngineView.Unreadable.class, probe.probe(null));

        // The latch: even a later readable controller stays unreadable for this probe —
        // per-world probing must not warn/flip repeatedly within one session.
        me.drex.antixray.common.util.Util.stagedController =
                me.drex.antixray.common.util.controller.DisabledChunkPacketBlockController.NO_OPERATION_INSTANCE;
        assertInstanceOf(AntiXrayCompat.EngineView.Unreadable.class, probe.probe(null));
        me.drex.antixray.common.util.Util.stagedController = null;
    }
}
