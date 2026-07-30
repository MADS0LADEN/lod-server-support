package ca.spottedleaf.moonrise.patches.chunk_system.io;

import ca.spottedleaf.moonrise.libs.ca.spottedleaf.concurrentutil.util.Priority;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Test stub with Moonrise-Fabric's real class name and the real 7-arg
 * {@code loadDataAsync} shape (the {@code me.drex} AntiXray-stub pattern): production
 * {@code Class.forName} resolves THIS class under fabric-loader-junit, so the happy-path
 * test drives the exact resolution + MethodHandle chain a live Moonrise server would.
 * Behavior is test-controlled through the static sink fields; {@link #reset()} between tests.
 */
public final class MoonriseRegionFileIO {

    private MoonriseRegionFileIO() {}

    public enum RegionFileType {
        CHUNK_DATA,
        POI_DATA,
        ENTITY_DATA
    }

    /** Everything the production bridge passed to the last invoke. */
    public record Invocation(ServerLevel level, int cx, int cz, RegionFileType type,
                             boolean intendingToBlock, Priority priority) {}

    public static final AtomicReference<Invocation> LAST_INVOCATION = new AtomicReference<>();
    public static final AtomicInteger INVOCATIONS = new AtomicInteger();
    /** When set, the invoke throws this instead of completing the callback. */
    public static volatile RuntimeException throwOnInvoke;
    /** Callback error to deliver ({@code err != null} shape) — wins over {@code completeTag}. */
    public static volatile Throwable completeError;
    /** Callback tag to deliver; null delivers the not-found {@code (null, null)} shape. */
    public static volatile CompoundTag completeTag;

    public static void reset() {
        LAST_INVOCATION.set(null);
        INVOCATIONS.set(0);
        throwOnInvoke = null;
        completeError = null;
        completeTag = null;
    }

    /** The exact overload the bridge matches; returns null where Moonrise returns a
     *  {@code Cancellable} (the bridge ignores it). */
    public static Object loadDataAsync(ServerLevel level, int cx, int cz, RegionFileType type,
                                       BiConsumer<CompoundTag, Throwable> onComplete,
                                       boolean intendingToBlock, Priority priority) {
        LAST_INVOCATION.set(new Invocation(level, cx, cz, type, intendingToBlock, priority));
        INVOCATIONS.incrementAndGet();
        var t = throwOnInvoke;
        if (t != null) throw t;
        var err = completeError;
        if (err != null) onComplete.accept(null, err);
        else onComplete.accept(completeTag, null);
        return null;
    }
}
