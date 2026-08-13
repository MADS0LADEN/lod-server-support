package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * awaitDecodeIdle pins (v0.11.0 stage D review n6): the /lss reset sequence's step-1
 * await. Idle returns true immediately; a held processing flag times out false; an
 * interrupt returns false AND restores the interrupt flag. The flag is driven via
 * reflection — the production set sites live inside the drain scheduling.
 */
class ClientColumnProcessorAwaitTest {

    private static AtomicBoolean processingFlag(ClientColumnProcessor p) throws Exception {
        var f = ClientColumnProcessor.class.getDeclaredField("processing");
        f.setAccessible(true);
        return (AtomicBoolean) f.get(p);
    }

    @Test
    void idleReturnsTrueImmediately() {
        var p = new ClientColumnProcessor((dimension, cx, cz) -> {}, () -> null);
        assertTrue(p.awaitDecodeIdle(1_000), "no in-flight drain -> immediate true");
    }

    @Test
    void heldProcessingFlagTimesOutFalse() throws Exception {
        var p = new ClientColumnProcessor((dimension, cx, cz) -> {}, () -> null);
        var flag = processingFlag(p);
        flag.set(true);
        try {
            long start = System.nanoTime();
            assertFalse(p.awaitDecodeIdle(80), "a stuck drain must time out, not hang");
            assertTrue(System.nanoTime() - start >= 80_000_000L, "the bound was honored");
        } finally {
            flag.set(false);
        }
    }

    @Test
    void interruptReturnsFalseAndRestoresTheFlag() throws Exception {
        var p = new ClientColumnProcessor((dimension, cx, cz) -> {}, () -> null);
        var flag = processingFlag(p);
        flag.set(true);
        try {
            Thread.currentThread().interrupt();
            assertFalse(p.awaitDecodeIdle(5_000), "an interrupt ends the wait early");
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored (and is cleared here)");
        } finally {
            flag.set(false);
        }
    }
}
