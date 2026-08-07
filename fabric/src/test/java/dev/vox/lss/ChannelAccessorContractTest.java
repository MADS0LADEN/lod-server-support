package dev.vox.lss;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void bothPlatformsEchoTheEffectiveConfigWithResolvedArguments() throws Exception {
        // B0 review N5 (PERF Phase 0 item 1): the echo's FORMAT is exact-pinned in the
        // config suites, but deleting the call — or passing raw config fields where the
        // javadoc demands resolved values — reds nothing while every measurement arm
        // starts failing its arm_valid check (or worse: two identical arms compare as
        // a valid A/B). Pin the call sites: resolved thread count + the LIVE post-probe
        // compression state, on both platforms.
        var echoCall = java.util.regex.Pattern.compile(
                "LSSLogger\\.info\\(config\\.effectiveConfigEcho\\(readerThreads,\\s*"
                        + "wireCompressionLive\\)\\)");
        String fabric = Files.readString(
                Path.of("src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(echoCall.matcher(fabric).find(),
                "Fabric must echo effectiveConfigEcho(readerThreads, wireCompressionLive)");
        String paper = Files.readString(
                Path.of("../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        assertTrue(echoCall.matcher(paper).find(),
                "Paper twin must echo effectiveConfigEcho(readerThreads, wireCompressionLive)");
    }

    @Test
    void regionRawReadAccessorsTargetRealMembersAndAreRegistered() throws Exception {
        // Phase 3 (R1) split: the raw fetch goes through two accessor mixins whose
        // targets are vanilla PRIVATE members — a rename at an MC bump must red HERE,
        // not silently misread region records (the whole reason the raw read shadows
        // vanilla's helpers instead of re-deriving the format).
        String config = Files.readString(Path.of("src/main/resources/lss.mixins.json"));
        assertTrue(config.contains("\"AccessorRegionFileStorage\""),
                "AccessorRegionFileStorage missing from lss.mixins.json");
        assertTrue(config.contains("\"AccessorRegionFile\""),
                "AccessorRegionFile missing from lss.mixins.json");

        var storage = net.minecraft.world.level.chunk.storage.RegionFileStorage.class;
        storage.getDeclaredMethod("getRegionFile", net.minecraft.world.level.ChunkPos.class);
        var rf = net.minecraft.world.level.chunk.storage.RegionFile.class;
        rf.getDeclaredField("file");
        rf.getDeclaredField("SECTOR_BYTES");
        rf.getDeclaredField("CHUNK_HEADER_SIZE");
        rf.getDeclaredMethod("getOffset", net.minecraft.world.level.ChunkPos.class);
        rf.getDeclaredMethod("getExternalChunkPath", net.minecraft.world.level.ChunkPos.class);
        rf.getDeclaredMethod("getSectorNumber", int.class);
        rf.getDeclaredMethod("getNumSectors", int.class);
        rf.getDeclaredMethod("isExternalStreamChunk", byte.class);
        rf.getDeclaredMethod("getExternalChunkVersion", byte.class);
        // RegionFileRawRead synchronizes on the RegionFile instance to interoperate with
        // vanilla's own synchronized reader — that premise must hold.
        assertTrue(java.lang.reflect.Modifier.isSynchronized(
                        rf.getDeclaredMethod("getChunkDataInputStream",
                                net.minecraft.world.level.ChunkPos.class).getModifiers()),
                "vanilla's reader is no longer synchronized — the raw read's instance-monitor"
                        + " serialization premise broke");
    }

    @Test
    void diskReaderCtorWiringPassesTheSplitConfig() throws Exception {
        // B3 review F7: backgroundReadSplitDefaultsOn pins only the FIELD default — a
        // literal or wrong field at the ctor call site ships a permanently inert split
        // with every test green (the echo call site has the same pin for the same
        // reason).
        String fabric = Files.readString(Path.of(
                "src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(fabric.contains("config.useBackgroundReadSplit"),
                "the ChunkDiskReader construction must pass config.useBackgroundReadSplit");
        assertTrue(fabric.contains("config.useSelectiveNbtParse"),
                "…and config.useSelectiveNbtParse (Phase 4)");
    }

        @Test
    void bothDiagCallSitesPassTheLiveArmedFlag() throws Exception {
        // Review C-4: a literal `true` (or the wrong field) as yieldDiagLineOrNull's
        // armed argument renders `Yield: armed=true` on every DEFAULT install — the
        // operator's arming receipt inverted. Pin that both command surfaces read the
        // live config field in the withYieldLine attach.
        var yieldAttach = java.util.regex.Pattern.compile(
                "withYieldLine\\(DiagnosticsFormatter\\.yieldDiagLineOrNull\\(\\s*"
                        + "config\\.lodYieldsToVanillaTransport", java.util.regex.Pattern.DOTALL);
        String fabric = Files.readString(
                Path.of("src/main/java/dev/vox/lss/networking/server/LSSServerCommands.java"));
        assertTrue(yieldAttach.matcher(fabric).find(),
                "LSSServerCommands must feed the LIVE config flag to yieldDiagLineOrNull");
        String paper = Files.readString(
                Path.of("../paper/src/main/java/dev/vox/lss/paper/PaperCommands.java"));
        assertTrue(yieldAttach.matcher(paper).find(),
                "PaperCommands must feed the LIVE config flag to yieldDiagLineOrNull");
    }
}
