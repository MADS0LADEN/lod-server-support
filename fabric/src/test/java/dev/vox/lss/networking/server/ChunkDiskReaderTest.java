package dev.vox.lss.networking.server;

import dev.vox.lss.compat.MoonriseReadCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import org.junit.jupiter.api.Test;

import java.lang.invoke.WrongMethodTypeException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the background-read fail-safe. The live branch — a chunk-IO-overhaul mod (C2ME's chunkio
 * rewrite, and structurally similar mods) replacing vanilla's IOWorker executor, leaving
 * {@code consecutiveExecutor}/{@code storage} null — cannot be reached from a gametest or soak run,
 * because no test environment loads such a mod (dev/CI run plain vanilla chunk IO, so the executor
 * is always non-null). Without this the whole feature NPE-storms on every read on a C2ME server;
 * only a live server surfaced it. These unit tests pin the exact decision (the {@code ||} is not
 * "simplified" to {@code &&}, neither handle's null check is dropped) and the A→B fallback wiring
 * (a throwing resolver latches incompatible, engages the throttle, warns once).
 */
class ChunkDiskReaderTest {

    @Test
    void backgroundReadIsUnavailableWhenEitherHandleIsNull() {
        var executor = new Object();
        var storage = new Object();
        assertFalse(ChunkDiskReader.backgroundReadUnavailable(executor, storage),
                "both handles present — the IOWorker executor path is usable");
        assertTrue(ChunkDiskReader.backgroundReadUnavailable(null, storage),
                "null executor (C2ME replaced the IO system) — must fall back, not NPE");
        assertTrue(ChunkDiskReader.backgroundReadUnavailable(executor, null),
                "null storage — must fall back, not NPE");
        assertTrue(ChunkDiskReader.backgroundReadUnavailable(null, null),
                "both null — must fall back");
    }

    /**
     * The fail-safe must survive not only a null handle but any Throwable thrown while resolving the
     * accessor (an unanticipated chunk-IO mod could make the reach itself fail). Detection must not
     * propagate the throwable; it must latch the server-wide incompatible flag, engage the adaptive
     * read throttle (Approach B), and warn exactly once across repeated triggering reads.
     */
    @Test
    void aThrowingHandleResolverLatchesIncompatibleAndEngagesTheThrottleWarningOnce() {
        var warnCount = new AtomicInteger();
        var reader = new ChunkDiskReader(1, true) {
            @Override
            ChunkDiskReader.Handles resolveBackgroundHandles(ChunkMap chunkMap) {
                throw new NoSuchMethodError("simulated chunk-IO-overhaul mod changed vanilla internals");
            }
            @Override
            void warnBackgroundUnavailable() {
                warnCount.incrementAndGet();
            }
        };
        try {
            assertFalse(reader.isBackgroundIncompatibleForTest(), "not latched until a read resolves incompatible");
            assertEquals(-1, reader.adaptiveThrottleLimitOrDisabled(), "throttle off until the fallback engages");

            // Two triggering reads (the resolver throws each time). Detection must catch the
            // throwable, not propagate it; the latch + throttle-enable + warn-once must all hold.
            reader.backgroundReaderOrFallback(null);
            reader.backgroundReaderOrFallback(null);

            assertTrue(reader.isBackgroundIncompatibleForTest(),
                    "a throwing resolver latches incompatible (fail-safe against any Throwable)");
            assertTrue(reader.adaptiveThrottleLimitOrDisabled() >= 0,
                    "the adaptive throttle is engaged on fallback so LOD reads still yield to gameplay");
            assertEquals(1, warnCount.get(),
                    "the fallback warning fires exactly once across repeated triggering reads");
        } finally {
            reader.shutdown();
        }
    }

    // ---- The Moonrise rung (reflective LOW-priority reads — live-only, so the ladder is
    // ---- pinned here; the bridge's own resolution ladder is pinned in MoonriseReadCompatTest).

    /** Reader with injected Moonrise-bridge behavior + recording foreground seam. The
     *  IOWorker accessor throws by default: with the Moonrise rung available it must never
     *  be consulted. */
    private static final class MoonriseRigReader extends ChunkDiskReader {
        final AtomicReference<MoonriseReadCompat.LowPriorityRead> bridge = new AtomicReference<>();
        final AtomicInteger bridgeConsults = new AtomicInteger();
        final AtomicInteger foregroundReads = new AtomicInteger();
        final AtomicInteger moonriseWarns = new AtomicInteger();
        volatile boolean accessorAllowed = false;

        MoonriseRigReader(boolean useBackgroundReadPriority) {
            super(1, useBackgroundReadPriority);
        }

        @Override
        MoonriseReadCompat.LowPriorityRead moonriseBridgeOrNull() {
            bridgeConsults.incrementAndGet();
            return bridge.get();
        }

        @Override
        NbtSectionSerializer.ChunkNbtRead foregroundRead(ChunkMap chunkMap) {
            return (cx, cz) -> {
                foregroundReads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            };
        }

        @Override
        Handles resolveBackgroundHandles(ChunkMap chunkMap) {
            if (!accessorAllowed) {
                throw new AssertionError("the IOWorker accessor must not be consulted on this path");
            }
            return new Handles(null, null); // a Moonrise-like server: nulled worker
        }

        @Override
        void warnMoonriseIncompatible(Throwable t) {
            moonriseWarns.incrementAndGet();
        }

        @Override
        void warnBackgroundUnavailable() {
            // silenced; the existing test pins its once-ness
        }
    }

    @Test
    void moonriseRungIsChosenBeforeTheIOWorkerAccessorWithoutEngagingTheThrottle() throws Exception {
        var reader = new MoonriseRigReader(true);
        var served = new CompoundTag();
        reader.bridge.set((level, cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(served)));
        try {
            var read = reader.chooseReadPath(null, null);
            assertSame(served, read.read(3, -4).get().orElseThrow(),
                    "the Moonrise rung serves the read (accessor override proves it was never consulted)");
            assertEquals(-1, reader.adaptiveThrottleLimitOrDisabled(),
                    "Moonrise LOW is the read protection — the adaptive throttle stays disengaged");
            assertFalse(reader.isMoonriseIncompatibleForTest());
            assertFalse(reader.isBackgroundIncompatibleForTest());
            assertTrue(reader.getDiagnostics().contains("read_path=moonrise-low"),
                    "the live-only rung is observable in /lsslod diag");
        } finally {
            reader.shutdown();
        }
    }

    @Test
    void configRollbackKeepsTheMoonriseRungFullyOff() throws Exception {
        var reader = new MoonriseRigReader(false);
        reader.bridge.set((level, cx, cz) ->
                CompletableFuture.completedFuture(Optional.empty()));
        try {
            var read = reader.chooseReadPath(null, null);
            assertTrue(read.read(0, 0).get().isEmpty());
            assertEquals(0, reader.bridgeConsults.get(),
                    "useBackgroundReadPriority=false is a TRUE full rollback — the Moonrise rung"
                            + " sits under the flag, mirroring Paper");
            assertEquals(1, reader.foregroundReads.get(), "the foreground path served the read");
            assertFalse(reader.getDiagnostics().contains("read_path="),
                    "no rung token when the flag is off");
        } finally {
            reader.shutdown();
        }
    }

    @Test
    void nullBridgeFallsThroughToTheExistingLadderUnchanged() {
        var reader = new MoonriseRigReader(true);
        reader.bridge.set(null);
        reader.accessorAllowed = true;
        try {
            assertFalse(reader.getDiagnostics().contains("read_path="),
                    "no Moonrise = no token — diagnostics goldens do not move");
            reader.chooseReadPath(null, null);
            assertTrue(reader.isBackgroundIncompatibleForTest(),
                    "with a null bridge the ladder reaches the IOWorker accessor exactly as today"
                            + " (here simulating a nulled worker, which latches the C2ME-style fallback)");
            assertTrue(reader.adaptiveThrottleLimitOrDisabled() >= 0);
        } finally {
            reader.shutdown();
        }
    }

    /**
     * The TYPED latch domain: a linkage/adaptation throw (the deterministic "this handle
     * doesn't fit" shape) latches the rung, warns once, and falls back INLINE — the
     * triggering read and any already-queued closures must not burst disk.errors (an A7
     * always-fail in the soak checker).
     */
    @Test
    void linkageDomainThrowLatchesWarnsOnceAndFallsBackInline() throws Exception {
        var reader = new MoonriseRigReader(true);
        reader.bridge.set((level, cx, cz) -> {
            throw new WrongMethodTypeException("resolved handle does not fit");
        });
        try {
            var read = reader.chooseReadPath(null, null);

            // Triggering read: latch + warn + inline foreground fallback, no error surfaced.
            assertTrue(read.read(1, 2).get().isEmpty());
            assertTrue(reader.isMoonriseIncompatibleForTest());
            assertEquals(1, reader.moonriseWarns.get());
            assertEquals(1, reader.foregroundReads.get());

            // An in-flight closure bound BEFORE the latch, run after it: the execution-time
            // re-check short-circuits to the foreground read without touching the bridge.
            assertTrue(read.read(3, 4).get().isEmpty());
            assertEquals(2, reader.foregroundReads.get());
            assertEquals(1, reader.moonriseWarns.get(), "warns once across repeated reads");

            // The next submit's ladder skips the bridge entirely and degrades down the vanilla
            // ladder (on a real Moonrise server: nulled worker → C2ME-style latch + throttle —
            // exactly the pre-bridge behavior, reached automatically).
            int consultsBefore = reader.bridgeConsults.get();
            reader.accessorAllowed = true;
            reader.chooseReadPath(null, null);
            assertEquals(consultsBefore, reader.bridgeConsults.get(),
                    "a latched rung is never re-consulted");
            assertTrue(reader.isBackgroundIncompatibleForTest());
            assertTrue(reader.adaptiveThrottleLimitOrDisabled() >= 0);
            assertTrue(reader.getDiagnostics().contains("read_path=moonrise-incompatible"),
                    "the latch is observable in /lsslod diag");
        } finally {
            reader.shutdown();
        }
    }

    /**
     * The NON-latch domain: Moonrise's own synchronous runtime throws (e.g. a read racing
     * server shutdown hits PrioritisedTask.queue()'s IllegalStateException) are per-chunk
     * error triage — no latch, no warn, and the rung stays active for the next read. On
     * Paper the identical throw is per-read triage; this rung mirrors Paper.
     */
    @Test
    void moonriseRuntimeThrowIsPerChunkTriageAndDoesNotLatch() {
        var reader = new MoonriseRigReader(true);
        var boom = new IllegalStateException("Executor is retired");
        reader.bridge.set((level, cx, cz) -> { throw boom; });
        try {
            var read = reader.chooseReadPath(null, null);
            var thrown = assertThrows(IllegalStateException.class, () -> read.read(0, 0),
                    "the throw propagates to the base's per-chunk triage");
            assertSame(boom, thrown);
            assertFalse(reader.isMoonriseIncompatibleForTest(), "runtime-state throws must NOT latch");
            assertEquals(0, reader.moonriseWarns.get());

            // The rung stays active: the next ladder pass consults the bridge again.
            int consultsBefore = reader.bridgeConsults.get();
            reader.chooseReadPath(null, null);
            assertTrue(reader.bridgeConsults.get() > consultsBefore,
                    "an un-latched rung is consulted on the next submit");
        } finally {
            reader.shutdown();
        }
    }
}
