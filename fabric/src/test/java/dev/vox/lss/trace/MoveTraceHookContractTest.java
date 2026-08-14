package dev.vox.lss.trace;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract pins for the tracer's mixin layer (move-desync-tracer-plan.md §3):
 *
 * <p><b>Source-regex half</b> (the {@code SaveHookContractTest} idiom — mixin classes
 * refuse classloading under fabric-loader-junit): every inject targets
 * {@code handleMovePlayer} with {@code require = 0}, the slf4j {@code @At}s carry
 * {@code remap = false}, every body delegates its logic to {@code MoveDesyncHooks} (plus the trivial
 * {@code @Unique} captures), and the mixins live
 * in {@code lss-trace.mixins.json} with {@code "required": false} — the F-1 crash-surface
 * fix; a trace mixin in the REQUIRED config would hard-crash tracer-disabled servers on
 * MC drift.
 *
 * <p><b>ASM half</b> (review F-11 — the constant-pool idiom cannot count per-method
 * INVOKEs): scans the real {@code handleMovePlayer} bytecode and asserts the INVOKE
 * census the targeting scheme depends on — exactly one {@code warn(String,Object[])},
 * exactly one {@code warn(String,Object)}, exactly three {@code teleport(DDDFF)V}, and
 * the relative order (the wrongly-warn between teleports #2 and #3) — the slice-anchor's
 * tripwire against a vanilla reorder.
 */
class MoveTraceHookContractTest {

    // Three nested paren levels tolerated: the rejection inject carries
    // slice = @Slice(from = @At(target = "...warn(...)V")) — the @Slice parens hold the
    // @At parens which hold a paren-bearing JVM descriptor string.
    private static final Pattern INJECT = Pattern.compile(
            "@Inject\\(((?:[^()]|\\((?:[^()]|\\((?:[^()]|\\([^()]*\\))*\\))*\\))*)\\)",
            Pattern.DOTALL);

    @Test
    void mixinSourceCarriesTheContractedTargetingScheme() throws Exception {
        String source = Files.readString(mixinSource("MovementRejectHook.java"));

        var injectBodies = new ArrayList<String>();
        Matcher m = INJECT.matcher(source);
        while (m.find()) {
            injectBodies.add(m.group(1));
        }
        assertEquals(4, injectBodies.size(), "exactly four injects: post-ensure entry,"
                + " too-quickly warn, wrongly warn, rejection teleport");

        for (String body : injectBodies) {
            assertTrue(Pattern.compile("method\\s*=\\s*\"handleMovePlayer\"").matcher(body).find(),
                    "every inject targets handleMovePlayer: " + body);
            assertTrue(Pattern.compile("require\\s*=\\s*0").matcher(body).find(),
                    "every inject carries require = 0 (degrade, never crash): " + body);
        }

        // Three slf4j warn @Ats: the too-quickly inject, the wrongly inject, and the
        // slice's from-anchor — every one descriptor-targeted with remap = false.
        assertEquals(3, count(source,
                        "target\\s*=\\s*\"Lorg/slf4j/Logger;warn\\(Ljava/lang/String;\\[?Ljava/lang/Object;\\)V\",\\s*remap\\s*=\\s*false"),
                "all slf4j warn @Ats are descriptor-targeted with remap = false");
        assertEquals(1, count(source, "@Slice\\("),
                "the rejection teleport is slice-anchored, not bare-ordinal (review F-6)");
        assertTrue(source.contains(
                        "target = \"Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V\""),
                "the rejection anchor is the teleport(DDDFF)V descriptor");
        assertEquals(4, count(source, "MoveDesyncHooks\\.on"),
                "every body delegates to MoveDesyncHooks (logic in a mixin is untestable logic)");
        assertTrue(source.contains("@Mixin(ServerGamePacketListenerImpl.class)"));

        // The entry hook must NOT be @At("HEAD"): a HEAD inject runs on the netty event
        // loop before ensureRunningOnSameThread's re-dispatch throw (review A-1) — it is
        // anchored AFTER that call instead.
        assertEquals(0, count(source, "@At\\(\\s*\"HEAD\"\\s*\\)"),
                "no HEAD inject — it would run on the netty thread (review A-1)");
        assertTrue(source.contains(
                        "target = \"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                        + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/server/level/ServerLevel;)V\"")
                        && source.contains("shift = At.Shift.AFTER"),
                "the entry hook anchors AFTER ensureRunningOnSameThread");

        // Descriptor ASSIGNMENT, not just presence (review C-4): the Object[] descriptor
        // belongs to the too-quickly body, the single-Object to the wrongly body — a swap
        // would mislabel every event row.
        String tooQuicklyBody = injectBodyOf(injectBodies, "lss\\$onMovedTooQuickly", source);
        String wronglyBody = injectBodyOf(injectBodies, "lss\\$onMovedWrongly", source);
        String rejectedBody = injectBodyOf(injectBodies, "lss\\$onMoveRejected", source);
        assertTrue(tooQuicklyBody.contains("warn(Ljava/lang/String;[Ljava/lang/Object;)V"),
                "too-quickly targets the Object[] warn descriptor");
        assertTrue(wronglyBody.contains("warn(Ljava/lang/String;Ljava/lang/Object;)V")
                        && !wronglyBody.contains("[Ljava/lang/Object;"),
                "wrongly targets the single-Object warn descriptor");
        assertTrue(Pattern.compile("ordinal\\s*=\\s*0").matcher(rejectedBody).find(),
                "the sliced teleport pins ordinal = 0 within the slice");
    }

    /** The inject body whose following handler method carries the given name. */
    private static String injectBodyOf(List<String> bodies, String handlerRegex, String source) {
        Matcher m = INJECT.matcher(source);
        int i = 0;
        while (m.find()) {
            String tail = source.substring(m.end(), Math.min(source.length(), m.end() + 250));
            if (Pattern.compile(handlerRegex).matcher(tail).find()) {
                return bodies.get(i);
            }
            i++;
        }
        throw new AssertionError("no inject found for handler " + handlerRegex);
    }

    @Test
    void traceMixinConfigIsSeparateAndNonRequired() throws Exception {
        String config = Files.readString(resource("lss-trace.mixins.json"));
        assertTrue(Pattern.compile("\"required\"\\s*:\\s*false").matcher(config).find(),
                "the trace config must be non-required — @Accessor cannot soft-fail, and a"
                        + " required config would crash tracer-DISABLED servers on MC drift (F-1)");
        assertTrue(config.contains("\"MovementRejectHook\""));
        assertTrue(config.contains("\"AccessorServerGamePacketListener\""));
        assertTrue(Pattern.compile("\"defaultRequire\"\\s*:\\s*0").matcher(config).find());

        String fabricModJson = Files.readString(resource("fabric.mod.json"));
        assertTrue(fabricModJson.contains("\"lss-trace.mixins.json\""),
                "the trace config must be registered in fabric.mod.json or it never applies");
    }

    @Test
    void handleMovePlayerBytecodeMatchesTheTargetingCensus() throws Exception {
        MethodNode method = loadHandleMovePlayer();

        var warnsArray = new ArrayList<Integer>();   // warn(String, Object[])
        var warnsSingle = new ArrayList<Integer>();  // warn(String, Object)
        var teleports = new ArrayList<Integer>();    // teleport(DDDFF)V on the listener
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals("org/slf4j/Logger") && call.name.equals("warn")) {
                    if (call.desc.equals("(Ljava/lang/String;[Ljava/lang/Object;)V")) {
                        warnsArray.add(index);
                    } else if (call.desc.equals("(Ljava/lang/String;Ljava/lang/Object;)V")) {
                        warnsSingle.add(index);
                    }
                } else if (call.name.equals("teleport") && call.desc.equals("(DDDFF)V")) {
                    teleports.add(index);
                }
            }
            index++;
        }

        assertEquals(1, warnsArray.size(),
                "exactly one warn(String,Object[]) — the 'moved too quickly!' site");
        assertEquals(1, warnsSingle.size(),
                "exactly one warn(String,Object) — the 'moved wrongly!' site");
        assertEquals(3, teleports.size(), "exactly three teleport(DDDFF)V INVOKEs");
        int wrongly = warnsSingle.get(0);
        assertTrue(teleports.get(1) < wrongly && wrongly < teleports.get(2),
                "the wrongly-warn must sit between teleports #2 and #3 — the slice anchor's"
                        + " premise; a reorder here means re-verifying the whole targeting scheme."
                        + " Order: teleports=" + teleports + " wrongly=" + wrongly);
    }

    @Test
    void ensureRunningOnSameThreadIsTheFirstInvoke() throws Exception {
        // The A-1 premise: everything before ensureRunningOnSameThread runs on the netty
        // event loop too. The entry hook anchors AFTER it; if vanilla ever hoists another
        // call above it, the anchor must be re-verified.
        MethodNode method = loadHandleMovePlayer();
        var beforeEnsure = new ArrayList<String>();
        int ensureCount = 0;
        boolean seenEnsure = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (call.name.equals("ensureRunningOnSameThread")) {
                    ensureCount++;
                    seenEnsure = true;
                } else if (!seenEnsure) {
                    beforeEnsure.add(call.name);
                }
            }
        }
        assertEquals(1, ensureCount, "exactly one ensureRunningOnSameThread INVOKE");
        // Argument plumbing (this.player.level()) legitimately precedes the call; any
        // OTHER preceding INVOKE means vanilla hoisted real work above the thread gate
        // and the anchor's premise must be re-verified.
        assertTrue(beforeEnsure.stream().allMatch(n -> n.equals("level")),
                "only the level() argument getter may precede ensureRunningOnSameThread —"
                        + " the entry hook's thread-safety anchor (review A-1); saw: " + beforeEnsure);
    }

    @Test
    void ringFlushPrecedesEventEmissionInBothEventBodies() throws Exception {
        // The v2.1 headline mechanism (Fable F2-4): the 5 Hz ring must flush AHEAD of the
        // event row, or the §1.6 REFUTE arm is structurally unsatisfiable (review C-3).
        String source = Files.readString(hooksSource());
        for (String method : new String[] {"onMovedTooQuickly", "emitCollisionEvent"}) {
            int start = source.indexOf("static void " + method);
            assertTrue(start >= 0, method + " must exist in MoveDesyncHooks");
            int end = source.indexOf("\n    }", start);
            String body = source.substring(start, end);
            int flush = body.indexOf("flushRingBeforeEvent(");
            int emit = body.indexOf("tracer.emit(");
            assertTrue(flush >= 0 && emit >= 0 && flush < emit,
                    method + " must flush the ring BEFORE emitting the event row");
        }
    }

    private static MethodNode loadHandleMovePlayer() throws Exception {
        try (InputStream in = ServerGamePacketListenerImpl.class.getResourceAsStream(
                "/net/minecraft/server/network/ServerGamePacketListenerImpl.class")) {
            assertNotNull(in, "listener bytecode must be resource-loadable in the named namespace");
            var node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            List<MethodNode> matches = node.methods.stream()
                    .filter(mn -> mn.name.equals("handleMovePlayer")).toList();
            assertEquals(1, matches.size(), "exactly one handleMovePlayer overload expected");
            return matches.get(0);
        }
    }

    private static int count(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static Path hooksSource() {
        return dev.vox.lss.testutil.SourcePaths.mainSource("dev/vox/lss/trace/MoveDesyncHooks.java");
    }

    /** The trace @Inject shim stays per-loader; its @Accessor sibling lives in xplat
     *  since N-1b — SourcePaths resolves either tree, from either CWD. */
    private static Path mixinSource(String fileName) {
        return dev.vox.lss.testutil.SourcePaths.mainSource("dev/vox/lss/mixin/trace/" + fileName);
    }

    private static Path resource(String fileName) {
        var moduleRelative = Path.of("src/main/resources").resolve(fileName);
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("fabric").resolve(moduleRelative);
    }
}
