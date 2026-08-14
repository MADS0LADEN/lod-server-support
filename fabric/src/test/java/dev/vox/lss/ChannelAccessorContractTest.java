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
        // Accessor INTERFACES live in xplat since N-1b (shared with neoforge);
        // @Inject shims stay per-loader — SourcePaths resolves either tree.
        Path p = dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/mixin/" + simpleName + ".java");
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
    void bothPlatformsPlumbThePingFactorThroughTheFlushAllocation() throws Exception {
        // The m12 plumbing pin (adaptive-transfer-rate-plan.md): the ping backstop's
        // factor must ride the ALLOCATION argument into flushSendQueue — the
        // per-player bucket clamps its banked burst to allocation/4, so only this
        // placement shrinks the bank (up to ~6.25 MB at default caps) on the FIRST
        // post-cut tick. Applied anywhere else, a cut leaves the old-cap bank intact
        // for one full burst.
        String fabric = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(fabric.contains("state.getPingBackstop().apply(perPlayerCap)"),
                "Fabric must apply the ping factor to the flush allocation");
        String paper = Files.readString(Path.of(
                "../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        assertTrue(paper.contains("state.getPingBackstop().apply(perPlayerCap)"),
                "Paper twin must apply the ping factor to the flush allocation");
        // The OBSERVE pass (impl review: with it deleted, the factor stays 1.0 forever
        // and the apply pin above stays green — Mechanism B silently inert), plus the
        // diag plumb (the golden constructs PlayerDiag through the compat ctor, so a
        // literal 1.0 in fromStates would keep every rendering test green).
        assertTrue(fabric.contains("state.getPingBackstop().observe("),
                "Fabric must run the backstop observe pass");
        assertTrue(paper.contains("state.getPingBackstop().observe("),
                "Paper twin must run the backstop observe pass");
        String formatter = Files.readString(Path.of(
                "../common/src/main/java/dev/vox/lss/common/DiagnosticsFormatter.java"));
        assertTrue(formatter.contains("state.getPingBackstop().factor()"),
                "the diag builder must read the LIVE factor into pingf=");
    }

    @Test
    void bothPlatformsWireTheSendPacingConfigIntoTheFlush() throws Exception {
        // send-pacing-plan.md v2: only the fullest overload arms pacing (S-9a), so a
        // dropped config pass-through reverts the fleet to unpaced bank dumps with
        // every unit test green. Plus the paced= diag plumb (the golden constructs
        // PlayerDiag through the compat ctor, so a literal 0 would stay green).
        String fabric = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(fabric.contains("config.enableSendPacing"),
                "Fabric must pass enableSendPacing into the flush");
        String paper = Files.readString(Path.of(
                "../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        assertTrue(paper.contains("this.config.enableSendPacing"),
                "Paper twin must pass enableSendPacing into the flush");
        String formatter = Files.readString(Path.of(
                "../common/src/main/java/dev/vox/lss/common/DiagnosticsFormatter.java"));
        assertTrue(formatter.contains("state.getPacedTicks()"),
                "the diag builder must read the LIVE paced counter");
        // The move-tracer boot-row echoes both transport-shaping kill switches (the
        // m5 partition-the-collections rationale) — deletable with every unit test
        // green otherwise.
        String bootstrap = Files.readString(Path.of(
                "src/main/java/dev/vox/lss/trace/MoveTraceBootstrap.java"));
        assertTrue(bootstrap.contains("enablePingBackstop")
                        && bootstrap.contains("enableSendPacing"),
                "the boot row must echo both transport-shaping kill switches");
    }

    @Test
    void bothPlatformsInstallTheChannelPressureProbeAtRegistration() throws Exception {
        String fabric = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/RequestProcessingService.java"));
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
                dev.vox.lss.testutil.SourcePaths.mainSource("dev/vox/lss/networking/server/RequestProcessingService.java"));
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
        // Stage B (disk-read gate): the third argument is the RESOLVED store-conditional
        // K — gateCapacity is computed from effectiveMaxConcurrentDiskReads against the
        // post-degrade store right above the echo, so pinning the argument NAME pins the
        // resolution path.
        var echoCall = java.util.regex.Pattern.compile(
                "LSSLogger\\.info\\(config\\.effectiveConfigEcho\\(readerThreads,\\s*"
                        + "wireCompressionLive,\\s*gateCapacity\\)\\)");
        String fabric = Files.readString(
                dev.vox.lss.testutil.SourcePaths.mainSource("dev/vox/lss/networking/server/RequestProcessingService.java"));
        assertTrue(echoCall.matcher(fabric).find(),
                "Fabric must echo effectiveConfigEcho(readerThreads, wireCompressionLive, gateCapacity)");
        String paper = Files.readString(
                Path.of("../paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        assertTrue(echoCall.matcher(paper).find(),
                "Paper twin must echo effectiveConfigEcho(readerThreads, wireCompressionLive, gateCapacity)");
        // Ordering half (v1.3 review MAJOR): the echo must sit AFTER store attachment on
        // both platforms — an echo before LodStores.createOrNull reports K computed
        // store-less on every store-armed server, in a script-consumed contract (the
        // same bug class the echo's "deliberately AFTER the zstd probe" comment covers).
        for (var entry : java.util.Map.of("Fabric", fabric, "Paper", paper).entrySet()) {
            String src = entry.getValue();
            int storeAttach = src.indexOf("LodStores.createOrNull");
            var echoAt = echoCall.matcher(src);
            assertTrue(echoAt.find() && storeAttach >= 0, entry.getKey() + " source anchors");
            assertTrue(echoAt.start() > storeAttach,
                    entry.getKey() + ": the config echo must run AFTER store attachment"
                            + " (the echoed K is the store-conditional resolution)");
        }
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
        String fabric = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/RequestProcessingService.java"));
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
        String fabric = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/LSSServerCommands.java"));
        assertTrue(yieldAttach.matcher(fabric).find(),
                "LSSServerCommands must feed the LIVE config flag to yieldDiagLineOrNull");
        String paper = Files.readString(
                Path.of("../paper/src/main/java/dev/vox/lss/paper/PaperCommands.java"));
        assertTrue(yieldAttach.matcher(paper).find(),
                "PaperCommands must feed the LIVE config flag to yieldDiagLineOrNull");
    }
}
