package dev.vox.lss.compat;

import ca.spottedleaf.moonrise.libs.ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reflective Moonrise-Fabric IO bridge. The live branch is unreachable from any
 * CI runtime (no test environment loads the Moonrise mod), so — like the C2ME fallback and
 * the AntiXray probe — the resolution ladder, the Paper-parity invoke arguments
 * ({@code CHUNK_DATA}, {@code intendingToBlock=false}, {@code LOW}), and every graceful-
 * degradation shape are pinned here against real-package-name stubs. Each test builds a
 * fresh instance ({@code build(modLoaded, lookup, warn)}) — no JVM-wide static state, so
 * the pins are test-order independent.
 */
class MoonriseReadCompatTest {

    private final AtomicInteger driftWarns = new AtomicInteger();
    private final MoonriseReadCompat.DriftWarn countingWarn =
            (detail, cause) -> driftWarns.incrementAndGet();

    @BeforeEach
    @AfterEach
    void resetStub() {
        MoonriseRegionFileIO.reset();
    }

    private MoonriseReadCompat.LowPriorityRead resolveAgainstStub() {
        var compat = MoonriseReadCompat.build(true, Class::forName, countingWarn);
        return compat.readOrNull();
    }

    @Test
    void happyPathResolvesAndInvokesWithPaperParityArguments() throws Exception {
        var read = resolveAgainstStub();
        assertNotNull(read, "the real-package-name stub must resolve through the production ladder");
        assertEquals(0, driftWarns.get(), "successful resolution must not warn");

        var tag = new CompoundTag();
        tag.putInt("marker", 42);
        MoonriseRegionFileIO.completeTag = tag;

        var future = read.read(null, 12, -7);
        var result = future.get();
        assertTrue(result.isPresent(), "a delivered tag completes as Optional.of");
        assertSame(tag, result.get(), "the tag passes through unaltered");

        var inv = MoonriseRegionFileIO.LAST_INVOCATION.get();
        assertNotNull(inv);
        assertEquals(12, inv.cx());
        assertEquals(-7, inv.cz());
        assertEquals(MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, inv.type(),
                "reads target CHUNK_DATA (not POI/ENTITY)");
        assertFalse(inv.intendingToBlock(),
                "intendingToBlock=false — we block on our own pool thread; no priority escalation past LOW");
        assertEquals(Priority.LOW, inv.priority(),
                "the whole point: LOD reads defer to gameplay at LOW (Paper parity)");
    }

    @Test
    void nullTagCompletesAsAuthoritativeNotFound() throws Exception {
        var read = resolveAgainstStub();
        assertNotNull(read);
        MoonriseRegionFileIO.completeTag = null;

        var result = read.read(null, 0, 0).get();
        assertTrue(result.isEmpty(),
                "tag == null is the authoritative not-found (Optional.empty), same as Paper");
    }

    @Test
    void callbackErrorCompletesExceptionally() {
        var read = resolveAgainstStub();
        assertNotNull(read);
        var boom = new RuntimeException("corrupt region");
        MoonriseRegionFileIO.completeError = boom;

        var future = read.read(null, 0, 0);
        var thrown = assertThrows(ExecutionException.class, future::get,
                "err != null completes exceptionally into the base's per-chunk triage");
        assertSame(boom, thrown.getCause(), "the original error surfaces to triage");
    }

    @Test
    void synchronousInvokeThrowPropagatesRawForCallerClassification() {
        var read = resolveAgainstStub();
        assertNotNull(read);
        var boom = new IllegalStateException("Executor is retired (read racing shutdown)");
        MoonriseRegionFileIO.throwOnInvoke = boom;

        var thrown = assertThrows(IllegalStateException.class, () -> read.read(null, 0, 0),
                "Moonrise's own synchronous runtime throws propagate raw — ChunkDiskReader"
                        + " owns classification (this shape must NOT be wrapped or swallowed)");
        assertSame(boom, thrown);
    }

    @Test
    void modNotLoadedResolvesUnavailableWithoutClassloadingOrWarning() {
        var compat = MoonriseReadCompat.build(false,
                name -> { throw new AssertionError("mod absent — must not classload " + name); },
                countingWarn);
        assertNull(compat.readOrNull(), "no Moonrise mod = unavailable");
        assertEquals(0, driftWarns.get(), "the drift warning is for present-but-unresolvable only");
    }

    @Test
    void missingClassResolvesUnavailableWithOneWarning() {
        var compat = MoonriseReadCompat.build(true,
                name -> { throw new ClassNotFoundException(name); }, countingWarn);
        assertNull(compat.readOrNull());
        assertEquals(1, driftWarns.get(), "mod present but class missing warns exactly once");
    }

    /** Wrong-shape carrier: has loadDataAsync overloads, none matching the 7-arg shape. */
    public static final class NoMatchingOverloadIO {
        public static void loadDataAsync(ServerLevel level, int cx, int cz) {}
        public static void loadDataAsync(ServerLevel level, int cx, int cz, String type,
                                         BiConsumer<CompoundTag, Throwable> cb, boolean b,
                                         Priority p, int extra) {}
    }

    @Test
    void missingOverloadResolvesUnavailableWithOneWarning() {
        var compat = MoonriseReadCompat.build(true, name -> NoMatchingOverloadIO.class, countingWarn);
        assertNull(compat.readOrNull());
        assertEquals(1, driftWarns.get());
    }

    /** Wrong-shape carrier: the 7-arg overload exists but the priority param is not an enum. */
    public static final class NonEnumPriorityIO {
        public static Object loadDataAsync(ServerLevel level, int cx, int cz,
                                           MoonriseRegionFileIO.RegionFileType type,
                                           BiConsumer<CompoundTag, Throwable> cb,
                                           boolean intendingToBlock, String priority) {
            return null;
        }
    }

    @Test
    void nonEnumPriorityParamResolvesUnavailable() {
        var compat = MoonriseReadCompat.build(true, name -> NonEnumPriorityIO.class, countingWarn);
        assertNull(compat.readOrNull());
        assertEquals(1, driftWarns.get());
    }

    /** Enum missing the constants the bridge needs. */
    public enum WrongConstants { FOO, BAR }

    /** Wrong-shape carrier: right shape, but the priority enum has no LOW. */
    public static final class MissingLowConstantIO {
        public static Object loadDataAsync(ServerLevel level, int cx, int cz,
                                           MoonriseRegionFileIO.RegionFileType type,
                                           BiConsumer<CompoundTag, Throwable> cb,
                                           boolean intendingToBlock, WrongConstants priority) {
            return null;
        }
    }

    @Test
    void missingLowConstantResolvesUnavailable() {
        var compat = MoonriseReadCompat.build(true, name -> MissingLowConstantIO.class, countingWarn);
        assertNull(compat.readOrNull());
        assertEquals(1, driftWarns.get());
    }

    @Test
    void throwingLookupIsContainedIncludingLinkageError() {
        var compat = MoonriseReadCompat.build(true,
                name -> { throw new NoClassDefFoundError("shaded internals moved"); }, countingWarn);
        assertNull(compat.readOrNull(), "a LinkageError from resolution is contained, never propagated");
        assertEquals(1, driftWarns.get());

        // The instance is the latch: the resolved-null result never retries (readOrNull is a
        // plain field read — nothing to re-trigger), so repeated consults stay null and silent.
        assertNull(compat.readOrNull());
        assertEquals(1, driftWarns.get(), "no re-resolution, no second warning");
    }
}
