package dev.vox.lss.networking.server;

import dev.vox.lss.compat.MoonriseReadCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import org.junit.jupiter.api.Test;

import java.lang.invoke.WrongMethodTypeException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            BackgroundIoSubmit.Handles resolveBackgroundHandles(ChunkMap chunkMap) {
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

    /** Reader with injected Moonrise-bridge behavior + recording foreground/accessor seams.
     *  Accessor consults are COUNTED (never thrown from — {@code backgroundReaderOrFallback}'s
     *  {@code catch (Throwable)} swallows resolver throws by design, so an AssertionError
     *  there would be vacuous): tests that forbid the accessor assert the count stays 0. */
    // Not final: SplitRigReader (the Phase 3 dispatcher pins) subclasses it.
    private static class MoonriseRigReader extends ChunkDiskReader {
        final AtomicReference<MoonriseReadCompat.LowPriorityRead> bridge = new AtomicReference<>();
        final AtomicInteger bridgeConsults = new AtomicInteger();
        final AtomicInteger bridgeReads = new AtomicInteger();
        final AtomicInteger foregroundReads = new AtomicInteger();
        final AtomicInteger moonriseWarns = new AtomicInteger();
        final AtomicInteger accessorConsults = new AtomicInteger();

        MoonriseRigReader(boolean useBackgroundReadPriority) {
            super(1, useBackgroundReadPriority);
        }

        MoonriseRigReader(boolean useBackgroundReadPriority, boolean useBackgroundReadSplit) {
            super(1, useBackgroundReadPriority, true, useBackgroundReadSplit);
        }

        /** Install bridge behavior wrapped in the read counter — {@code bridgeReads} is the
         *  pin proving the execution-time latch re-check short-circuits BEFORE the bridge. */
        void setBridge(MoonriseReadCompat.LowPriorityRead behavior) {
            bridge.set(behavior == null ? null : (level, cx, cz) -> {
                bridgeReads.incrementAndGet();
                return behavior.read(level, cx, cz);
            });
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
        BackgroundIoSubmit.Handles resolveBackgroundHandles(ChunkMap chunkMap) {
            accessorConsults.incrementAndGet();
            return new BackgroundIoSubmit.Handles(null, null); // a Moonrise-like server: nulled worker
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
        reader.setBridge((level, cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(served)));
        try {
            var read = reader.chooseReadPath(null, null);
            assertSame(served, read.read(3, -4).get(5, TimeUnit.SECONDS).orElseThrow(),
                    "the Moonrise rung serves the read");
            assertEquals(0, reader.accessorConsults.get(),
                    "the IOWorker accessor is never consulted while the Moonrise rung is available");
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
        reader.setBridge((level, cx, cz) ->
                CompletableFuture.completedFuture(Optional.empty()));
        try {
            var read = reader.chooseReadPath(null, null);
            assertTrue(read.read(0, 0).get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(0, reader.bridgeConsults.get(),
                    "useBackgroundReadPriority=false is a TRUE full rollback — the Moonrise rung"
                            + " sits under the flag, mirroring Paper");
            assertEquals(0, reader.accessorConsults.get(), "full rollback skips the accessor too");
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
        reader.setBridge(null);
        try {
            assertTrue(reader.getDiagnostics().contains("read_path=bg-split, raw_serves=0"),
                    "no Moonrise + split on = the split receipt token (B3 review F2 — this"
                            + " deliberately replaced the old no-token golden)");
            reader.chooseReadPath(null, null);
            assertEquals(1, reader.accessorConsults.get(),
                    "with a null bridge the ladder reaches the IOWorker accessor exactly as today");
            assertTrue(reader.isBackgroundIncompatibleForTest(),
                    "the rig simulates a nulled worker, which latches the C2ME-style fallback");
            assertTrue(reader.adaptiveThrottleLimitOrDisabled() >= 0);
            assertFalse(reader.getDiagnostics().contains("read_path="),
                    "a latched incompatible clears every read_path token");
        } finally {
            reader.shutdown();
        }
    }

    /**
     * The TYPED latch domain: a linkage/adaptation throw (the deterministic "this handle
     * doesn't fit" shape) latches the rung, warns once, and falls back INLINE — the
     * triggering read and any already-queued closures must not burst disk.errors (an A7
     * always-fail in the soak checker). All three typed shapes must latch: narrowing the
     * catch (e.g. dropping LinkageError — the likely real-world drift shape, a
     * NoSuchMethodError) would repeat-burst per-read errors with no latch.
     */
    @Test
    void wrongMethodTypeThrowLatchesWarnsOnceAndFallsBackInline() throws Exception {
        assertLatchDomainThrowLatches(new WrongMethodTypeException("resolved handle does not fit"));
    }

    @Test
    void linkageErrorThrowLatchesWarnsOnceAndFallsBackInline() throws Exception {
        assertLatchDomainThrowLatches(new NoSuchMethodError("Moonrise internals moved"));
    }

    @Test
    void adaptationClassCastThrowLatchesWarnsOnceAndFallsBackInline() throws Exception {
        assertLatchDomainThrowLatches(new ClassCastException("enum constant of a foreign class"));
    }

    private void assertLatchDomainThrowLatches(Throwable boom) throws Exception {
        var reader = new MoonriseRigReader(true);
        reader.setBridge((level, cx, cz) -> {
            if (boom instanceof RuntimeException re) throw re;
            throw (Error) boom;
        });
        try {
            var read = reader.chooseReadPath(null, null);

            // Triggering read: latch + warn + inline foreground fallback, no error surfaced.
            assertTrue(read.read(1, 2).get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(reader.isMoonriseIncompatibleForTest());
            assertEquals(1, reader.moonriseWarns.get());
            assertEquals(1, reader.foregroundReads.get());
            assertEquals(1, reader.bridgeReads.get());

            // An in-flight closure bound BEFORE the latch, run after it: the execution-time
            // re-check short-circuits to the foreground read WITHOUT touching the bridge —
            // bridgeReads is the pin (without the re-check the typed catch would quietly
            // reproduce the same observable fallback while re-invoking the bridge).
            assertTrue(read.read(3, 4).get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(2, reader.foregroundReads.get());
            assertEquals(1, reader.bridgeReads.get(),
                    "a post-latch closure must not touch the bridge (execution-time re-check)");
            // (The warn CAS itself is a concurrency-only belt — two pool threads racing the
            // same latch-domain throw — unreachable single-threaded, so not pinned here.)
            assertEquals(1, reader.moonriseWarns.get(), "warns once across repeated reads");

            // The next submit's ladder skips the bridge entirely and degrades down the vanilla
            // ladder (on a real Moonrise server: nulled worker → C2ME-style latch + throttle —
            // exactly the pre-bridge behavior, reached automatically).
            int consultsBefore = reader.bridgeConsults.get();
            reader.chooseReadPath(null, null);
            assertEquals(consultsBefore, reader.bridgeConsults.get(),
                    "a latched rung is never re-consulted");
            assertEquals(1, reader.accessorConsults.get(), "the vanilla ladder takes over");
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
        reader.setBridge((level, cx, cz) -> { throw boom; });
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

    /**
     * An ASYNC failure — the bridge's future completing exceptionally, even with a
     * linkage-SHAPED error — is per-chunk triage, never a latch: only a synchronous throw
     * from the invoke itself is in the typed latch domain. (A plausible "hardening"
     * regression would inspect the future's cause and latch on it — breaking the
     * round-1-review contract that async errors are Paper-parity per-chunk containment.)
     */
    @Test
    void asyncLinkageShapedFailureIsPerChunkTriageAndDoesNotLatch() {
        var reader = new MoonriseRigReader(true);
        reader.setBridge((level, cx, cz) ->
                CompletableFuture.failedFuture(new NoClassDefFoundError("async shape")));
        try {
            var read = reader.chooseReadPath(null, null);
            var future = read.read(0, 0);
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(thrown.getCause() instanceof NoClassDefFoundError);
            assertFalse(reader.isMoonriseIncompatibleForTest(),
                    "an exceptional future must NOT latch — only synchronous invoke throws are typed");
            assertEquals(0, reader.moonriseWarns.get());
        } finally {
            reader.shutdown();
        }
    }

    // ---- Phase 3 (R1) split-dispatcher ladder agreement -------------------------------
    //
    // chooseRawReadOrNull must be non-null EXACTLY when chooseReadPath would select the
    // vanilla-IOWorker BACKGROUND rung and the split is on. The two ladders share their
    // rung conditions by construction; these pins red a drift. The handle-resolution
    // half is exercised by the null-handle case (raw returns null WITHOUT latching —
    // one latch site, owned by backgroundReaderOrFallback).

    /** Rig with resolvable-looking dispatch: rawBackgroundReaderOrNull returns a sentinel
     *  so the DISPATCHER's own conditions are the thing under test (real handles need a
     *  live IOWorker no test environment has). */
    private static final class SplitRigReader extends MoonriseRigReader {
        static final NbtSectionSerializer.ChunkRawRead SENTINEL =
                (cx, cz) -> CompletableFuture.completedFuture(Optional.empty());

        SplitRigReader(boolean bgPriority, boolean split) {
            super(bgPriority, split);
        }

        @Override
        NbtSectionSerializer.ChunkRawRead rawBackgroundReaderOrNull(ChunkMap chunkMap) {
            return SENTINEL;
        }
    }

    /**
     * The AGREEMENT pin (review F3): for each state, assert the PAIR — what
     * chooseRawReadOrNull returns AND which rung chooseReadPath actually takes
     * (identified through the rig's rung counters, evaluated in production order:
     * raw first, then the nbt ladder). The one state the rigs cannot fabricate is
     * real resolvable IOWorker handles; there, "both dispatchers judge the state as
     * background-rung" is pinned via the accessor-consult counter, and the LIVENESS
     * of the real raw path is the parity gametest's raw_serves receipt.
     */
    @Test
    void rawDispatcherAgreesWithTheNbtLadderOnEveryRung() {
        // Background-rung state + split on: raw chosen AND the nbt ladder would consult
        // the IOWorker accessor (both dispatchers call the state "background rung").
        var reader = new SplitRigReader(true, true);
        try {
            assertSame(SplitRigReader.SENTINEL, reader.chooseRawReadOrNull(null, null),
                    "the vanilla BACKGROUND rung must take the split path");
            reader.chooseReadPath(null, null);
            assertEquals(1, reader.accessorConsults.get(),
                    "the nbt ladder agrees: this state is the background rung");
        } finally {
            reader.shutdown();
        }

        // Split kill switch: raw null, but the nbt ladder STILL judges background rung
        // (accessor consulted) — the split alone stepped aside, pre-split shape.
        var off = new SplitRigReader(true, false);
        try {
            assertNull(off.chooseRawReadOrNull(null, null),
                    "useBackgroundReadSplit=false must fully disable the raw path");
            off.chooseReadPath(null, null);
            assertEquals(1, off.accessorConsults.get(),
                    "with the split off, the nbt ladder's background rung serves");
        } finally {
            off.shutdown();
        }

        // Foreground rollback: raw null AND the nbt ladder never touches the accessor.
        var fg = new SplitRigReader(false, true);
        try {
            assertNull(fg.chooseRawReadOrNull(null, null),
                    "useBackgroundReadPriority=false must keep the raw path off");
            var read = fg.chooseReadPath(null, null);
            read.read(0, 0);
            assertEquals(1, fg.foregroundReads.get(), "the nbt ladder agrees: foreground");
            assertEquals(0, fg.accessorConsults.get());
        } finally {
            fg.shutdown();
        }

        // Moonrise rung available: raw null AND the bridge serves (its future carries
        // PARSED NBT by contract — parse placement is the bridge's, not ours).
        var moonrise = new SplitRigReader(true, true);
        var served = new CompoundTag();
        moonrise.setBridge((level, cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(served)));
        try {
            assertNull(moonrise.chooseRawReadOrNull(null, null),
                    "the Moonrise rung wins over the raw split, mirroring chooseReadPath");
            moonrise.chooseReadPath(null, null).read(0, 0);
            assertEquals(1, moonrise.bridgeReads.get(), "the nbt ladder agrees: Moonrise");
            assertEquals(0, moonrise.accessorConsults.get());
        } finally {
            moonrise.shutdown();
        }

        // Latched incompatible (review F3's missing fifth case): once the nbt ladder
        // latches, the raw dispatcher must go null on the LATCH CONDITION itself —
        // before any handle resolution.
        var latched = new SplitRigReader(true, true);
        try {
            // MoonriseRigReader resolves handles to (null, null): this latches.
            latched.backgroundReaderOrFallback(null);
            assertTrue(latched.isBackgroundIncompatibleForTest(), "precondition: latched");
            int consultsAfterLatch = latched.accessorConsults.get();
            assertNull(latched.chooseRawReadOrNull(null, null),
                    "a latched incompatible must keep the raw path off");
            assertEquals(consultsAfterLatch, latched.accessorConsults.get(),
                    "…without re-consulting the accessor (the latch short-circuits)");
        } finally {
            latched.shutdown();
        }
    }

    @Test
    void unresolvableHandlesReturnNullRawPathWithoutLatchingOrWarning() {
        // The REAL rawBackgroundReaderOrNull with null handles (a Moonrise/C2ME-shaped
        // server): raw must step aside silently — the ChunkNbtRead ladder owns the
        // one-shot latch + throttle + warn, so nothing here may fire it.
        var reader = new MoonriseRigReader(true); // resolveBackgroundHandles -> (null, null)
        try {
            assertNull(reader.rawBackgroundReaderOrNull(null),
                    "null handles must yield no raw path");
            assertFalse(reader.isBackgroundIncompatibleForTest(),
                    "the raw probe must NOT latch — backgroundReaderOrFallback owns the latch");
            assertEquals(-1, reader.adaptiveThrottleLimitOrDisabled(),
                    "…nor engage the throttle");
        } finally {
            reader.shutdown();
        }
    }
}
