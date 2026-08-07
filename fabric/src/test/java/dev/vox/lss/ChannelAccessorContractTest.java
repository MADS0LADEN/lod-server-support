package dev.vox.lss;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract pin for the two accessor mixins behind the outbound-buffer gauge
 * (docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.3).
 *
 * <p><b>Why this exists.</b> The gauge's failure mode is silent and terminal: one warning,
 * then {@code NO_SIGNAL} forever, {@code obuf=n/a} on every player and an {@code obuf_hw}
 * that never rises. That reads exactly like "no buffer is building" — a false negative on
 * the one measurement that decides whether transport deference is ever armed. A renamed
 * vanilla field would produce it at the next MC bump with nothing red.
 *
 * <p>Source-regex + reflective resolution, the {@code LanHookContractTest} /
 * {@code SaveHookContractTest} pattern: mixin-package classes refuse classloading under
 * fabric-loader-junit (this test itself lives OUTSIDE that package for the same reason),
 * so the {@code @Accessor} target is read out of the source and then checked against the
 * real vanilla class.
 */
class ChannelAccessorContractTest {

    private static String source(String simpleName) throws Exception {
        Path p = Path.of("src/main/java/dev/vox/lss/mixin/" + simpleName + ".java");
        assertTrue(Files.exists(p), "missing mixin source: " + p.toAbsolutePath());
        return Files.readString(p);
    }

    @Test
    void connectionAccessorTargetsTheRealNettyChannelField() throws Exception {
        String src = source("AccessorConnection");
        assertTrue(src.contains("@Accessor(\"channel\")"),
                "AccessorConnection must target Connection.channel by that exact name");
        assertTrue(src.contains("@Mixin(Connection.class)"), "…on Connection");

        var field = Connection.class.getDeclaredField("channel");
        assertEquals(io.netty.channel.Channel.class, field.getType(),
                "vanilla's Connection.channel changed type — the accessor's return type and"
                        + " FabricChannelPressure's cast must move with it");
    }

    @Test
    void packetListenerAccessorTargetsTheRealConnectionField() throws Exception {
        String src = source("AccessorServerCommonPacketListener");
        assertTrue(src.contains("@Accessor(\"connection\")"),
                "AccessorServerCommonPacketListener must target the 'connection' field");
        assertTrue(src.contains("@Mixin(ServerCommonPacketListenerImpl.class)"),
                "…on ServerCommonPacketListenerImpl, the class that actually declares it");

        var field = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
        assertEquals(Connection.class, field.getType(),
                "vanilla's listener->Connection hop changed — the gauge's first hop breaks");
    }

    @Test
    void bothAccessorsAreRegisteredInTheMixinConfig() throws Exception {
        // An unregistered accessor compiles and resolves but is never applied, so every
        // probe call would ClassCastException into the warn-once latch — the silent-death
        // shape this whole test exists to prevent.
        String config = Files.readString(Path.of("src/main/resources/lss.mixins.json"));
        assertTrue(config.contains("\"AccessorConnection\""),
                "AccessorConnection missing from lss.mixins.json");
        assertTrue(config.contains("\"AccessorServerCommonPacketListener\""),
                "AccessorServerCommonPacketListener missing from lss.mixins.json");
    }

    @Test
    void bothPlatformsWireTheConfiguredCeilingIntoTheFlush() throws Exception {
        // Source-regex wiring pin (the StoreEnvironmentContractTest pattern). The only test
        // that touches the flush glue goes through the overload that hard-codes 0L, so a
        // revert of either production call site to 0L — or a dropped *1024 — would ship
        // green and turn outboundBufferCeilingKB into a silent no-op an operator could only
        // diagnose by noticing deferred= never moves.
        String fabric = Files.readString(Path.of(
                "src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(fabric.contains("config.outboundBufferCeilingKB * 1024L"),
                "Fabric must pass the CONFIGURED ceiling, in bytes, into flushSendQueues");
        String paper = Files.readString(Path.of(
                "../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        assertTrue(paper.contains("this.config.outboundBufferCeilingKB * 1024L"),
                "Paper twin must pass the same configured ceiling in bytes");
    }

    @Test
    void bothPlatformsInstallTheChannelPressureProbeAtRegistration() throws Exception {
        String fabric = Files.readString(Path.of(
                "src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(fabric.contains("setChannelPressureProbe(FabricChannelPressure.forPlayer(player))"),
                "Fabric must install the probe on the state it creates, or the gauge is dead");
        String paper = Files.readString(Path.of(
                "../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        assertTrue(paper.contains("setChannelPressureProbe(PaperChannelPressure.forPlayer(player))"),
                "Paper twin must install its probe too");
    }

    @Test
    void paperReflectiveTwinTargetsTheSameTwoFields() throws Exception {
        // Paper reaches the same two fields reflectively (no mixins there). If the field
        // names drift, Fabric's accessors and Paper's strings must move together — this
        // pins them to the same literals from the Fabric side, where both are visible.
        Path paper = Path.of("../paper/src/main/java/dev/vox/lss/paper/PaperChannelPressure.java");
        assertTrue(Files.exists(paper), "missing Paper twin: " + paper.toAbsolutePath());
        String src = Files.readString(paper);
        assertTrue(src.contains("getDeclaredField(\"connection\")"),
                "Paper twin must resolve the same 'connection' field Fabric's accessor targets");
        assertTrue(src.contains("getDeclaredField(\"channel\")"),
                "Paper twin must resolve the same 'channel' field Fabric's accessor targets");
    }

    @Test
    void bothPlatformFlushCallSitesPassTheYieldConfig() throws Exception {
        // S-9b (yield plan §6): the gate exists only if the services ARM it from live
        // config — a dropped argument leaves lodYieldsToVanillaTransport silently inert
        // on one platform, which no Tier 1 state test can see.
        String fabric = Files.readString(
                Path.of("src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(fabric.contains("config.lodYieldsToVanillaTransport"),
                "the Fabric flush wiring must pass config.lodYieldsToVanillaTransport");
        Path paperPath = Path.of("../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        assertTrue(Files.exists(paperPath), "paper service source not found: " + paperPath.toAbsolutePath());
        String paper = Files.readString(paperPath);
        assertTrue(paper.contains("config.lodYieldsToVanillaTransport"),
                "the Paper flush wiring must pass config.lodYieldsToVanillaTransport");
    }
}
