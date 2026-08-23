package dev.vox.lss.config.menu;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review-scoped "golden arm" (sodium-options-page-generations-plan.md §4): every
 * row of {@link LegacySodiumPage#SURFACE} — the member table the reflective builder
 * binds — is checked by NAME + ARITY against the REAL legacy Sodium bytecode, read as a
 * zip with ASM from the jar Gradle's plain {@code sodiumLegacyGolden} configuration
 * resolved (gradle.properties {@code sodium_legacy_golden}; never on any classpath —
 * the stub package would shadow it, and a Sodium jar on the T1 runtime would register
 * as a mod). Descriptor-agnostic on purpose: MC type names differ per line.
 *
 * <p>Skips itself (assumption) when the jar is not available — offline builds still
 * run Tier 1; CI resolves it. This is the automated proof that the table matches real
 * bytecode; the live gate stays the only end-to-end proof.
 */
class SodiumLegacySurfaceResolvesTest {

    @Test
    void everySurfaceMemberExistsInTheRealJar() throws IOException {
        String jarPath = System.getProperty("lss.sodiumLegacyGoldenJar", "");
        Assumptions.assumeTrue(!jarPath.isBlank() && Files.isRegularFile(Path.of(jarPath)),
                "sodiumLegacyGolden jar not resolved (offline?) — skipping the real-bytecode check");
        String prefix = SodiumGeneration.CAFFEINE_PREFIX.replace('.', '/');
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jarPath)) {
            Map<String, ClassNode> nodes = new HashMap<>();
            for (LegacySodiumPage.Member m : LegacySodiumPage.SURFACE) {
                ClassNode node = nodes.computeIfAbsent(m.owner(), owner -> {
                    String entryName = prefix + owner.replace('.', '/') + ".class";
                    ZipEntry e = zip.getEntry(entryName);
                    if (e == null) {
                        return null;
                    }
                    try (InputStream in = zip.getInputStream(e)) {
                        ClassNode n = new ClassNode();
                        new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                        return n;
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
                if (node == null) {
                    missing.add(m.owner() + " (class)");
                    continue;
                }
                boolean ok = switch (m.kind()) {
                    case STATIC_METHOD -> hasMethod(node, m.name(), m.arity(), true);
                    case METHOD -> hasMethod(node, m.name(), m.arity(), false);
                    case CONSTRUCTOR -> hasMethod(node, "<init>", m.arity(), false);
                    case INTERFACE_METHOD -> (node.access & Opcodes.ACC_INTERFACE) != 0
                            && hasMethod(node, m.name(), m.arity(), false);
                    case ENUM -> (node.access & Opcodes.ACC_ENUM) != 0;
                };
                if (!ok) {
                    missing.add(m.owner() + "#" + m.name() + "/" + m.arity() + " (" + m.kind() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "LegacySodiumPage.SURFACE members absent from " + jarPath + ": " + missing);
    }

    private static boolean hasMethod(ClassNode node, String name, int arity, boolean isStatic) {
        for (MethodNode m : node.methods) {
            if (!m.name.equals(name) || Type.getArgumentTypes(m.desc).length != arity) {
                continue;
            }
            if (((m.access & Opcodes.ACC_STATIC) != 0) != isStatic) {
                continue;
            }
            if ((m.access & Opcodes.ACC_PUBLIC) == 0) {
                continue;
            }
            return true;
        }
        return false;
    }
}
