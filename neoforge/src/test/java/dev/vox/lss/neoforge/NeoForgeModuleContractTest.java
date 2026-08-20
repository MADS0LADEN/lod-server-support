package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N-2 contract suite (neoforge-support-plan.md §5.1) — pure file/source pins,
 * no MC classloading: the module's descriptor, mixin config, AT, and payload
 * registrar carry loader-bound invariants that compile green when violated and
 * fail only at runtime on a live NeoForge install.
 */
class NeoForgeModuleContractTest {

    // ---- descriptor ----

    @Test
    void modsTomlPinsIdentityAndMixins() throws IOException {
        String toml = read("neoforge/src/main/resources/META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("modId=\"lss\""),
                "the wire-compat contract: mod id stays lss on every loader");
        assertTrue(toml.contains("version=\"${mod_version}\""),
                "version must be the template token — processResources expands it"
                        + " (a literal here goes stale silently)");
        assertTrue(toml.contains("config=\"lss.neoforge.mixins.json\""),
                "the mixin config must be declared or the accessors/save hook never apply"
                        + " (disk reads + dirty detection silently dead)");
        assertTrue(toml.contains("modId=\"neoforge\"") && toml.contains("modId=\"minecraft\""),
                "dependency ranges drive the Modrinth/listing environment metadata");
    }

    // ---- registrar census (the §1.2 loader-bound invariants) ----

    @Test
    void registrarIsOptionalRunsOnMainAndNeverUsesTheLssProtocolAsChannelVersion() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/networking/LSSNetworking.java");
        String normalized = src.replaceAll("\\s+", " ");
        assertTrue(normalized.contains("event.registrar(\"1\") .optional() .executesOn(HandlerThread.MAIN)"),
                "the registrar chain must be exactly optional()+executesOn(MAIN) on the"
                        + " constant version \"1\": a MANDATORY payload refuses vanilla/Fabric"
                        + " clients at login (the community fork's mistake), a non-MAIN thread"
                        + " breaks the receiver thread contract, and the LSS protocol constant"
                        + " as channel version would make NeoForge negotiation fight our own"
                        + " handshake-driven compat rungs");
        assertFalse(src.contains("PROTOCOL_VERSION"),
                "never derive the registrar version from the LSS protocol constant");
    }

    @Test
    void registrarCensusCoversAllChannels() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/networking/LSSNetworking.java");
        List<String> c2s = List.of("HandshakeC2SPayload", "BatchChunkRequestC2SPayload",
                "ClientInfoC2SPayload", "FarPlayerPrefsC2SPayload",
                "RegionSummaryRequestC2SPayload");
        List<String> s2c = List.of("SessionConfigS2CPayload", "BatchResponseS2CPayload",
                "DirtyColumnsS2CPayload", "VoxelColumnS2CPayload",
                "FarPlayerRosterS2CPayload", "FarPlayerUpdatesS2CPayload",
                "RegionSummaryS2CPayload");
        for (String p : c2s) {
            assertTrue(src.contains("playToServer(" + p + ".TYPE, " + p + ".CODEC"),
                    p + " must register playToServer with its shared TYPE+CODEC");
        }
        for (String p : s2c) {
            assertTrue(src.contains("playToClient(" + p + ".TYPE, " + p + ".CODEC"),
                    p + " must register playToClient with its shared TYPE+CODEC");
        }
        assertEquals(5, count(src, Pattern.compile("playToServer\\(")),
                "exactly the 5 C2S channels — a new channel must be added to BOTH loaders"
                        + " and both WireParityTest censuses");
        assertEquals(7, count(src, Pattern.compile("playToClient\\(")),
                "exactly the 7 S2C channels");
        // CROSS-LOADER census (N-2 review): the registrar's total must track the shared
        // CHANNEL_* constant count — a fabric-side channel add reds THIS module's build
        // instead of shipping a NeoForge jar that silently lacks the new channel.
        long sharedChannels = Arrays.stream(dev.vox.lss.common.LSSConstants.class.getFields())
                .filter(f -> f.getName().startsWith("CHANNEL_"))
                .count();
        assertEquals(sharedChannels, 5 + 7,
                "LSSConstants declares " + sharedChannels + " CHANNEL_* constants but this"
                        + " registrar registers 12 — add the new channel to LSSNetworking"
                        + " (both loaders) and both WireParityTest censuses");
    }

    // ---- mixin config + AT ----

    @Test
    void mixinConfigListsTheSharedAccessorsAndTheSaveHookTwin() throws IOException {
        String config = read("neoforge/src/main/resources/lss.neoforge.mixins.json");
        for (String required : new String[]{
                "AccessorServerChunkCache", "AccessorSimpleRegionStorage", "AccessorIOWorker",
                "AccessorRegionFileStorage", "AccessorRegionFile", "AccessorConnection",
                "AccessorServerCommonPacketListener", "ChunkSaveDataHook",
                "AccessorClientPacketListener"}) {
            assertTrue(config.contains("\"" + required + "\""),
                    required + " missing from lss.neoforge.mixins.json — an unlisted accessor"
                            + " compiles but never applies, so every use ClassCastExceptions"
                            + " at runtime (disk reads / channel pressure / dirty detection)");
        }
        // Deliberately ABSENT: the fabric-only IntegratedServerLanHook (no LAN hook on
        // NeoForge v1 — plan §5.4) and the trace mixins (tracer deferred).
        assertFalse(config.contains("IntegratedServerLanHook"),
                "the LAN hook is fabric-only; listing it here would hard-fail mixin apply");
    }

    @Test
    void everyListedMixinClassExistsInXplatOrThisModule() throws IOException {
        String config = read("neoforge/src/main/resources/lss.neoforge.mixins.json");
        Matcher m = Pattern.compile("\"([A-Za-z$]+)\"").matcher(config);
        while (m.find()) {
            String name = m.group(1);
            if (!name.matches("[A-Z].*")) {
                continue; // JSON keys (required, package, ...), not class names
            }
            String rel = "dev/vox/lss/mixin/" + name + ".java";
            boolean exists = exists("xplat/src/main/java/" + rel)
                    || exists("neoforge/src/main/java/" + rel);
            assertTrue(exists, "mixin config lists " + name + " but no source exists in"
                    + " xplat or the neoforge module");
        }
    }

    @Test
    void accessTransformerMirrorsTheFabricAccessWidener() throws IOException {
        String at = read("neoforge/src/main/resources/META-INF/accesstransformer.cfg");
        assertTrue(at.contains("public net.minecraft.world.level.chunk.PalettedContainer$Data"),
                "the PalettedContainer$Data class widening (headless serialization)");
        assertTrue(at.contains("public net.minecraft.world.level.chunk.PalettedContainer data"),
                "the PalettedContainer.data field widening");
        String aw = read("fabric/src/main/resources/lss.accesswidener");
        // Count EVERY effective widener line (round-3 review: an 'accessible'-only
        // filter let a future mutable/extendable line grow the widener without
        // tripping the mirror — fabric works, the NeoForge twin IllegalAccessErrors).
        var effective = aw.lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")
                        && !l.startsWith("accessWidener"))
                .toList();
        assertEquals(2, effective.size(), "the fabric accesswidener grew — mirror the"
                + " new lines into accesstransformer.cfg (this pin is the drift tripwire)");
        assertTrue(effective.stream().allMatch(l -> l.startsWith("accessible")),
                "a non-'accessible' widener line needs a new AT mapping form "
                        + "(mutable → public-f) — extend the mirror and this pin together");
    }

    // ---- the save-hook twin (the fabric SaveHookContractTest's sibling) ----

    @Test
    void saveHookTwinTargetsCopyOfWithSoftFail() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/mixin/ChunkSaveDataHook.java");
        assertTrue(src.contains("@Mixin(SerializableChunkData.class)"),
                "the dirty hook must target SerializableChunkData (issue #69 — the choke"
                        + " point every chunk system funnels through)");
        assertTrue(src.contains("method = \"copyOf\"") && src.contains("require = 0"),
                "copyOf @ RETURN with require = 0 — a missing target degrades dirty"
                        + " detection, never crashes the server");
        assertTrue(src.contains("LSSServerNetworking.onChunkSaveData(level, chunk)"),
                "the body must delegate to this loader's holder -> the shared glue");
    }

    // ---- services registration ----

    @Test
    void loaderServicesServiceFilePointsAtTheNeoForgeImpl() throws IOException {
        String services = read(
                "neoforge/src/main/resources/META-INF/services/dev.vox.lss.platform.LoaderServices");
        assertEquals("dev.vox.lss.platform.NeoForgeLoaderServices", services.trim(),
                "ServiceLoader fallback registration (the fabric module's twin file)");
        assertTrue(exists("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java"),
                "the registered impl must exist");
    }

    // ---- helpers ----

    private static int count(String src, Pattern p) {
        Matcher m = p.matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static String read(String repoRelative) throws IOException {
        return Files.readString(resolve(repoRelative));
    }

    static boolean exists(String repoRelative) {
        try {
            resolve(repoRelative);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    @Test
    void scopedCarrierTwinsAreByteIdentical() throws IOException {
        // V-2/S5: unlike the renderer twins (which legitimately diverge — the neoforge
        // render path is cut), ScopedCarrier has no loader surface at all, so the twins
        // must stay BYTE-identical — a fix landing in one tree only is silent drift the
        // compile cannot see (both compile fine alone).
        byte[] fab = Files.readAllBytes(
                resolve("fabric/src/main/java/dev/vox/lss/compat/ScopedCarrier.java"));
        byte[] neo = Files.readAllBytes(
                resolve("neoforge/src/main/java/dev/vox/lss/compat/ScopedCarrier.java"));
        assertTrue(java.util.Arrays.equals(fab, neo),
                "the ScopedCarrier twins drifted — apply the change to BOTH trees "
                        + "(they are version-volatile whole-file swaps at port time, "
                        + "but on ONE line they are the same file)");
    }

    /** Repo-relative resolution surviving both the Gradle CWD (module dir) and repo root. */
    static Path resolve(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("cannot locate " + repoRelative + " from "
                + Path.of("").toAbsolutePath());
    }
}
