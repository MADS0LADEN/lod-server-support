package dev.vox.lss;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the LAN-hook mixin's @Inject descriptor to a REAL {@code IntegratedServer} method.
 *
 * <p>The regression class this guards: at the MC 26.2 port the hook was pinned to the
 * 4-arg {@code publishServer} overload, but 26.2's LAN screen calls the 2-arg overload
 * directly (the 4-arg is a delegating wrapper) — so the mixin applied cleanly, resolved
 * cleanly, and simply never fired for "Open to LAN". A descriptor naming a method that
 * exists but is not on the GUI path cannot be caught reflectively; what CAN be pinned is
 * (a) the descriptor resolves against a declared overload at all (goes red on the next MC
 * bump if the signature drifts, instead of silently never firing), and (b) the descriptor
 * is exactly the 2-arg overload the 26.2 GUI calls (bytecode-verified 2026-07-28), so a
 * future edit cannot quietly re-target the wrapper.
 *
 * <p>Reads the SOURCE file (the {@code GameTestEntrypointContractTest} idiom): classes in
 * a defined mixin package refuse direct classloading under fabric-loader-junit's mixin
 * bootstrap.
 */
class LanHookContractTest {

    // Arg-order- and line-break-tolerant (DOTALL): a pure reformat must not red this test.
    private static final Pattern INJECT_METHOD =
            Pattern.compile("@Inject\\([^)]*?method\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /** Survives both the Gradle CWD (module dir) and an IDE repo-root CWD. */
    private static Path mixinSource() {
        var moduleRelative = Path.of("src/main/java/dev/vox/lss/mixin/IntegratedServerLanHook.java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("fabric").resolve(moduleRelative);
    }

    @Test
    void injectDescriptorTargetsTheTwoArgOverloadTheLanGuiCalls() throws Exception {
        String source = Files.readString(mixinSource());
        var matcher = INJECT_METHOD.matcher(source);
        assertTrue(matcher.find(), "the mixin declares exactly one @Inject with a method descriptor");
        String descriptor = matcher.group(1);
        assertFalse(matcher.find(), "exactly one @Inject expected");

        assertEquals("publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
                descriptor,
                "the hook must target the 2-arg overload — the 4-arg is a delegating wrapper "
                        + "the LAN GUI never calls (the shipped v0.6.0-v0.8.0 bug)");

        // The descriptor must resolve against a REAL declared overload — the next MC bump
        // that changes the signature turns this red instead of silently unhooking LAN.
        List<String> declared = new ArrayList<>();
        for (Method m : net.minecraft.client.server.IntegratedServer.class.getDeclaredMethods()) {
            if (m.getName().equals("publishServer")) {
                declared.add("publishServer" + descriptorOf(m));
            }
        }
        assertTrue(declared.contains(descriptor),
                "the @Inject descriptor must match a declared IntegratedServer.publishServer "
                        + "overload; declared overloads: " + declared);
    }

    private static String descriptorOf(Method m) {
        var sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(jvmName(p));
        return sb.append(')').append(jvmName(m.getReturnType())).toString();
    }

    private static String jvmName(Class<?> c) {
        if (c == int.class) return "I";
        if (c == boolean.class) return "Z";
        if (c == void.class) return "V";
        if (c.isArray()) return c.getName().replace('.', '/');
        if (c.isPrimitive()) throw new AssertionError("unmapped primitive " + c);
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
