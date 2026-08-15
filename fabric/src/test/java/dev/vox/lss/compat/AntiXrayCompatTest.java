package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the AntiXray ENGINE-PROBE ladder (docs/planning/antixray-compat-design.md §3)
 * plus the {@code callSerializing} delegate. The crash-shim/carrier half moved to
 * {@link ScopedCarrierTest} with the V-2/S5 split — these five probe pins are
 * line-invariant and stay with the shared xplat class.
 */
class AntiXrayCompatTest {

    @Test
    void callSerializingDelegatesThroughTheCarrierSplit() {
        // This JVM has no antixray mod, so the carrier is inactive by construction —
        // pins the delegate pass-through (the S5 split must not orphan the xplat
        // entry point every serialize call site uses).
        assertEquals("through", AntiXrayCompat.callSerializing(() -> "through"));
    }

    @Test
    void delegationLinkIsWiredInTheCompiledClass() throws Exception {
        // V-2 review: the behavioral pin above cannot see a de-wired delegate — with
        // no mod installed, "return ScopedCarrier.callSerializing(body)" and
        // "return body.get()" are indistinguishable, and the de-wire silently
        // disables the crash shim on every live AntiXray server. Constant-pool
        // substring over the compiled class (the FoliaWiringContractTest idiom).
        var url = AntiXrayCompat.class.getResource("AntiXrayCompat.class");
        assertNotNull(url, "compiled AntiXrayCompat.class must be resource-loadable");
        byte[] bytes;
        try (var in = url.openStream()) {
            bytes = in.readAllBytes();
        }
        String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(pool.contains("dev/vox/lss/compat/ScopedCarrier"),
                "callSerializing must delegate to ScopedCarrier — a simplified"
                        + " pass-through disables the AntiXray crash shim invisibly");
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
        var view = assertInstanceOf(AntiXrayCompat.EngineView.Unreadable.class, probe.probe(null));
        assertFalse(view.transientNull(),
                "a resolution failure is TERMINAL unreadable — never the retryable flavor");
    }

    @Test
    void engineProbeReadsDisabledAndActiveControllers() {
        var probe = AntiXrayCompat.buildEngineProbe(true, Class::forName);

        me.drex.antixray.common.util.Util.stagedController =
                me.drex.antixray.common.util.controller.DisabledChunkPacketBlockController.NO_OPERATION_INSTANCE;
        assertInstanceOf(AntiXrayCompat.EngineView.Disabled.class, probe.probe(null),
                "the Disabled controller means that world is anti-xray-off");

        me.drex.antixray.common.util.Util.stagedController = null;
        var nullView = assertInstanceOf(AntiXrayCompat.EngineView.Unreadable.class, probe.probe(null),
                "no controller is NOT evidence the world is anti-xray-off — fail safe "
                        + "(LSS-keys masking), and without latching");
        assertTrue(nullView.transientNull(),
                "the null-controller rung is the ONE retryable unreadable flavor — the "
                        + "manager's re-probe window (R2-7) keys on it");

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
