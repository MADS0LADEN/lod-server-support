package dev.vox.lss.trace;

import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution-ladder pins for {@link ChunkSendState} against real-package-name stubs under
 * {@code fabric/src/test/java/ca/spottedleaf/} (the {@code MoonriseReadCompatTest}
 * precedent; stub shape verified against the real Moonrise-Fabric 1.1.0 jar 2026-08-06).
 * Each test builds a fresh instance — no JVM-wide static state, order-independent.
 */
class MoonriseSendStateCompatTest {

    private final AtomicInteger warns = new AtomicInteger();
    private final ChunkSendState.DriftWarn countingWarn = (detail, cause) -> warns.incrementAndGet();

    @Test
    void modAbsentUsesVanillaRungAndNeverClassloads() {
        var state = ChunkSendState.build(false,
                name -> { throw new AssertionError("mod absent — must not classload " + name); },
                countingWarn);
        assertEquals(MoveRow.RUNG_VANILLA, state.rungName());
        assertEquals(0, warns.get());
    }

    @Test
    void moonriseResolvesAgainstTheStubShape() throws Exception {
        var state = ChunkSendState.build(true, Class::forName, countingWarn);
        assertEquals(MoveRow.RUNG_MOONRISE, state.rungName());
        assertEquals(0, warns.get());

        var loader = new RegionizedPlayerChunkLoader.PlayerChunkLoaderData();
        loader.testSetLoaderState(10, 6, -13);
        // Anchor (6,-13) sent; a diagonal neighbor sent; a far corner sent.
        loader.testAddSent(MoveEventMath.mcChunkKey(6, -13));
        loader.testAddSent(MoveEventMath.mcChunkKey(7, -12));
        loader.testAddSent(MoveEventMath.mcChunkKey(4, -15));
        loader.testSetStage(MoveEventMath.mcChunkKey(6, -13), (byte) 5);
        loader.testQueue(MoveEventMath.mcChunkKey(8, -13));
        loader.testSetStage(MoveEventMath.mcChunkKey(8, -13), (byte) 3);

        var capture = state.captureResolved(loader, 6, -13);
        assertEquals(MoveRow.RUNG_MOONRISE, capture.rung());
        assertEquals(6, capture.anchorCx());
        assertEquals(-13, capture.anchorCz());
        int expected25 = (1 << MoveEventMath.maskBit5x5(0, 0))
                | (1 << MoveEventMath.maskBit5x5(1, 1))
                | (1 << MoveEventMath.maskBit5x5(-2, -2));
        assertEquals(expected25, (int) capture.mask5x5());
        int expected9 = (1 << MoveEventMath.maskBit3x3(0, 0)) | (1 << MoveEventMath.maskBit3x3(1, 1));
        assertEquals(expected9, (int) capture.maskR1(),
                "the diagonal neighbor must appear in sent_r1; the far corner must not");
        assertEquals(5, (int) capture.stage());
        assertEquals(10, (int) capture.sendRadius());
        assertEquals(6, (int) capture.loaderCx());
        assertEquals(-13, (int) capture.loaderCz());
        assertEquals(1, (int) capture.sendQueue());
        assertEquals(3, (int) capture.sendHeadStage(),
                "send_head_stage is the stage of sendQueue.firstLong()");
    }

    @Test
    void emptyQueueOmitsHeadStage() throws Exception {
        var state = ChunkSendState.build(true, Class::forName, countingWarn);
        var capture = state.captureResolved(
                new RegionizedPlayerChunkLoader.PlayerChunkLoaderData(), 0, 0);
        assertEquals(0, (int) capture.mask5x5());
        assertEquals(0, (int) capture.stage(), "absent stage key reads 0 == NONE — exact semantics");
        assertEquals(0, (int) capture.sendQueue());
        assertNull(capture.sendHeadStage(), "empty queue: head stage ABSENT, never a guess");
    }

    @Test
    void nullLoaderIsNonePerCallNotALatch() {
        var state = ChunkSendState.build(true, Class::forName, countingWarn);
        assertEquals(MoveRow.RUNG_NONE, state.captureResolved(null, 0, 0).rung());
        // The rung itself stays moonrise — the next call with a real loader works.
        assertEquals(MoveRow.RUNG_MOONRISE, state.rungName());
    }

    @Test
    void missingClassDegradesToNoneWithOneWarn() {
        var state = ChunkSendState.build(true,
                name -> { throw new ClassNotFoundException(name); }, countingWarn);
        assertEquals(MoveRow.RUNG_NONE, state.rungName());
        assertEquals(1, warns.get());
    }

    @Test
    void wrongShapeDegradesToNoneWithOneWarn() {
        var state = ChunkSendState.build(true, name -> WrongShapePlayer.class, countingWarn);
        assertEquals(MoveRow.RUNG_NONE, state.rungName());
        assertEquals(1, warns.get());
    }

    @Test
    void wrongLoaderFieldTypeDegradesToNoneWithOneWarn() {
        var state = ChunkSendState.build(true, name -> WrongLoaderFieldPlayer.class, countingWarn);
        assertEquals(MoveRow.RUNG_NONE, state.rungName());
        assertEquals(1, warns.get());
    }

    @Test
    void linkageErrorIsContained() {
        var state = ChunkSendState.build(true,
                name -> { throw new NoClassDefFoundError("shaded internals moved"); },
                countingWarn);
        assertEquals(MoveRow.RUNG_NONE, state.rungName());
        assertEquals(1, warns.get());
        // A resolved-to-none instance still answers captures safely.
        assertEquals(MoveRow.RUNG_NONE, state.captureResolved(new Object(), 0, 0).rung());
    }

    // ---- wrong-shape carriers (the MoonriseReadCompatTest idiom) ----

    /** Has the method name but no loader accessor worth resolving. */
    public interface WrongShapePlayer {
        String somethingElse();
    }

    /** Loader type whose getSentChunksRaw returns the wrong type. */
    public interface WrongLoaderFieldPlayer {
        BadLoader moonrise$getChunkLoader();

        interface BadLoader {
            String getSentChunksRaw();
        }
    }
}
