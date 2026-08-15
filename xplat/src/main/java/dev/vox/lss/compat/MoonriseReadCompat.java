package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Reflective bridge to Moonrise-Fabric's prioritised chunk IO, so LOD disk reads can run at
 * {@code Priority.LOW} — deferring to gameplay's NORMAL loads — exactly like the compile-time
 * Paper integration in {@code PaperChunkDiskReader.moonriseReader}. Zero compile-time
 * dependency (the {@code VoxyCompat}/{@code AntiXrayCompat} pattern): Moonrise's classes are
 * resolved at runtime, and any failure shape degrades to {@code null} = unavailable, which
 * leaves {@code ChunkDiskReader}'s existing ladder bit-identical to today.
 *
 * <p>The target overload (verified against moonrise-opt 1.1.0 bytecode, MC 26.2):
 * <pre>
 * public static Cancellable loadDataAsync(ServerLevel, int, int, RegionFileType,
 *         BiConsumer&lt;CompoundTag, Throwable&gt;, boolean intendingToBlock, Priority)
 * </pre>
 * The support types are SHADED on Fabric ({@code ca.spottedleaf.moonrise.libs...Priority}),
 * unlike Paper — so {@code RegionFileType} and {@code Priority} are taken from the matched
 * method's own parameter types, never hardcoded: the bridge is immune to re-shading. The only
 * hardcoded names are the entry class, the method name, and the two enum constants — all
 * Moonrise-owned API identifiers no remapper touches. MC types in the match use direct class
 * literals per the project rule (never {@code Class.forName} an MC type).
 *
 * <p>Resolution failure with the mod present warns once and latches unavailable for the JVM
 * (mirroring the C2ME one-way incompatible latch); per-read failures are NOT this class's
 * concern — the invoke rethrows raw and {@code ChunkDiskReader} classifies (typed latch
 * domain vs per-chunk triage).
 */
public final class MoonriseReadCompat {

    private static final String REGION_FILE_IO_CLASS =
            "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO";

    /** A LOW-priority Moonrise chunk-data read. {@code tag == null} completes as
     *  {@code Optional.empty()} — the authoritative not-found, same as Paper. */
    @FunctionalInterface
    public interface LowPriorityRead {
        CompletableFuture<Optional<CompoundTag>> read(ServerLevel level, int cx, int cz);
    }

    /** Class resolution seam — production is {@code Class::forName}; tests inject
     *  wrong-shape classes without classpath collisions. */
    @FunctionalInterface
    interface ClassLookup {
        Class<?> lookup(String name) throws Throwable;
    }

    /** Drift-warning sink — production logs via {@link LSSLogger}; tests count. Only ever
     *  invoked when the mod IS loaded but resolution failed (the shape worth reporting). */
    @FunctionalInterface
    interface DriftWarn {
        void warn(String detail, Throwable cause);
    }

    private final LowPriorityRead read;

    private MoonriseReadCompat(LowPriorityRead read) {
        this.read = read;
    }

    /** Nullable — the resolved LOW-priority read, or null when unavailable. */
    LowPriorityRead readOrNull() {
        return this.read;
    }

    /** Lazy per-JVM holder: the mod set cannot change at runtime, so resolution runs once,
     *  on the first consult (which only happens when the reader's ladder actually wants the
     *  rung — a config-off server never resolves and never logs). */
    private static final class Holder {
        static final LowPriorityRead READ = buildProduction();
    }

    /** Nullable — non-null only when Moonrise is loaded and its IO API resolved. */
    public static LowPriorityRead resolveOrNull() {
        return Holder.READ;
    }

    /** True when the Moonrise mod is present at all (independent of API resolution) —
     *  lets the vanilla-ladder fallback warning name Moonrise instead of C2ME. Never
     *  triggers resolution or Moonrise classloading. */
    public static boolean moonriseDetected() {
        try {
            return dev.vox.lss.platform.LoaderServices.get().isModLoaded("moonrise");
        } catch (Throwable t) {
            return false;
        }
    }

    private static LowPriorityRead buildProduction() {
        var compat = build(moonriseDetected(), Class::forName, (detail, cause) ->
                LSSLogger.warn("Moonrise detected but its IO API could not be resolved (" + detail
                        + ") — LOD disk reads fall back to the standard read ladder. A Moonrise"
                        + " update likely changed its internals; please report this.", cause));
        var read = compat.readOrNull();
        if (read != null) {
            LSSLogger.info("Moonrise detected — LOD disk reads routed through Moonrise's"
                    + " prioritised IO at LOW priority (defers to gameplay chunk loads)");
        }
        return read;
    }

    /**
     * Pure resolution ladder, instance-scoped (the {@code ScopedCarrier.buildCarrier} shape)
     * so tests are order-independent: mod absent → unavailable with no classloading at all;
     * any resolution failure → unavailable + exactly one drift warning (build runs once per
     * instance, so once-ness is structural). Never throws.
     */
    static MoonriseReadCompat build(boolean modLoaded, ClassLookup lookup, DriftWarn warn) {
        if (!modLoaded) return new MoonriseReadCompat(null);
        try {
            Class<?> io = lookup.lookup(REGION_FILE_IO_CLASS);
            Method method = findLoadDataAsync(io);
            if (method == null) {
                warn.warn("no matching 7-arg loadDataAsync overload", null);
                return new MoonriseReadCompat(null);
            }
            Object chunkData = enumConstant(method.getParameterTypes()[3], "CHUNK_DATA");
            Object low = enumConstant(method.getParameterTypes()[6], "LOW");
            if (chunkData == null || low == null) {
                warn.warn("missing CHUNK_DATA/LOW enum constant", null);
                return new MoonriseReadCompat(null);
            }
            MethodHandle handle = MethodHandles.lookup().unreflect(method);
            return new MoonriseReadCompat(
                    (level, cx, cz) -> invokeLoadDataAsync(handle, level, cx, cz, chunkData, low));
        } catch (Throwable t) {
            warn.warn(String.valueOf(t), t);
            return new MoonriseReadCompat(null);
        }
    }

    /**
     * Match the exact overload by shape: static, named {@code loadDataAsync}, 7 params of
     * {@code (ServerLevel, int, int, <enum>, BiConsumer, boolean, <enum>)}. The enum classes
     * come out of the match (shaded-package immune). Verified unambiguous in 1.1.0 — the
     * other loadDataAsync overloads have 5 or 6 params.
     */
    private static Method findLoadDataAsync(Class<?> io) {
        for (Method m : io.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals("loadDataAsync")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 7) continue;
            if (p[0] != ServerLevel.class || p[1] != int.class || p[2] != int.class) continue;
            if (!p[3].isEnum() || p[4] != BiConsumer.class || p[5] != boolean.class || !p[6].isEnum()) continue;
            return m;
        }
        return null;
    }

    /** The named constant of an enum class, or null when absent (raw-typed on purpose —
     *  the class is only known at runtime). */
    private static Object enumConstant(Class<?> enumClass, String name) {
        for (Object constant : enumClass.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        return null;
    }

    /**
     * The per-read invoke, mirroring {@code PaperChunkDiskReader.moonriseReader} exactly:
     * the callback only completes our future (it may fire synchronously or on a Moonrise IO
     * thread — no chunk-system reentrancy); {@code err != null} completes exceptionally into
     * the base's standard per-chunk error triage; {@code intendingToBlock = false} matches
     * Paper's call byte-for-byte (in 1.1.0's 7-arg overload the parameter is ignored — the
     * body never reads it — so this is documentation of intent, not an active lever: we block
     * on our own reader-pool thread and want no blocking-priority escalation overriding LOW).
     * The {@code Cancellable} return is ignored (Paper ignores it too).
     *
     * <p>A synchronous throw from the invoke propagates RAW (checked shapes wrapped) — the
     * caller owns classification: linkage/adaptation errors latch its one-way incompatible
     * flag, everything else (Moonrise's own runtime-state throws, e.g. a read racing server
     * shutdown) is per-chunk triage, no latch.
     */
    private static CompletableFuture<Optional<CompoundTag>> invokeLoadDataAsync(
            MethodHandle handle, ServerLevel level, int cx, int cz, Object chunkData, Object low) {
        var future = new CompletableFuture<Optional<CompoundTag>>();
        BiConsumer<CompoundTag, Throwable> callback = (tag, err) -> {
            if (err != null) future.completeExceptionally(err);
            else future.complete(Optional.ofNullable(tag));
        };
        try {
            handle.invoke(level, cx, cz, chunkData, callback, false, low);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Moonrise loadDataAsync invoke failed", t);
        }
        return future;
    }
}
