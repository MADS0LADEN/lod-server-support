package dev.vox.lss.api;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract suite for {@link LSSApi#maxReportedIngestBacklog()} (issue #71,
 * docs/planning/ingest-backpressure-design.md §3.1): max-over-consumers aggregation (the
 * slowest consumer paces the stream), -1 = no signal, per-consumer throw containment with
 * the per-JVM warn-once latch, and Error propagation matching the dispatch path's ladder.
 */
class LSSApiBacklogTest {

    private final List<VoxelColumnConsumer> registered = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (var consumer : registered) LSSApi.removeColumnConsumer(consumer);
        registered.clear();
        LSSApi.resetBacklogWarnLatchForTest();
    }

    private VoxelColumnConsumer register(VoxelColumnConsumer consumer) {
        LSSApi.registerColumnConsumer(consumer);
        registered.add(consumer);
        return consumer;
    }

    /** A consumer whose backlog report is driven by {@code backlog} (throws if it throws). */
    private VoxelColumnConsumer reporting(IntSupplier backlog) {
        return register(new VoxelColumnConsumer() {
            @Override
            public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                              int chunkX, int chunkZ, VoxelColumnData columnData) {}

            @Override
            public int pendingIngestBacklog() { return backlog.getAsInt(); }
        });
    }

    @Test
    void noConsumersReportsNoSignal() {
        assertEquals(-1, LSSApi.maxReportedIngestBacklog(),
                "no registered consumers = no signal");
    }

    @Test
    void lambdaConsumerKeepsTheNoReportDefault() {
        // The exact shape every pre-#71 consumer has (soak recorder, gametests, mods that
        // never heard of the method): a lambda CANNOT override the default — the no-signal
        // path is pinned by construction.
        register((level, dimension, chunkX, chunkZ, columnData) -> {});
        assertEquals(-1, LSSApi.maxReportedIngestBacklog(),
                "a default (non-reporting) consumer contributes no signal");
    }

    @Test
    void singleReporterValueSurfacesVerbatim() {
        reporting(() -> 1234);
        assertEquals(1234, LSSApi.maxReportedIngestBacklog());
    }

    @Test
    void zeroIsARealReportMeaningEmpty() {
        reporting(() -> 0);
        assertEquals(0, LSSApi.maxReportedIngestBacklog(),
                "0 = a real 'my queue is empty' report, distinct from -1 no-signal");
    }

    @Test
    void maxAcrossConsumersTheSlowestPaces() {
        reporting(() -> 3);
        reporting(() -> 7);
        register((level, dimension, chunkX, chunkZ, columnData) -> {}); // non-reporting alongside
        assertEquals(7, LSSApi.maxReportedIngestBacklog(),
                "every consumer ingests the SAME columns — the deepest backlog must pace");
    }

    @Test
    void throwingReporterIsContainedAndOthersStillWin() {
        reporting(() -> { throw new IllegalStateException("broken gauge"); });
        reporting(() -> 42);
        assertFalse(LSSApi.backlogWarnLatchedForTest(), "latch starts unarmed");
        assertEquals(42, LSSApi.maxReportedIngestBacklog(),
                "a broken gauge degrades to no-signal, never kills the healthy one");
        assertTrue(LSSApi.backlogWarnLatchedForTest(), "the contained throw arms the warn latch");
    }

    @Test
    void warnLatchFiresAtMostOncePerJvmAndTheResetSeamRearms() {
        reporting(() -> { throw new IllegalStateException("broken gauge"); });
        assertEquals(-1, LSSApi.maxReportedIngestBacklog());
        assertTrue(LSSApi.backlogWarnLatchedForTest());
        // Second poll: still contained, latch already armed — the warn cannot re-fire
        // (the log call is guarded by the compareAndSet that armed it).
        assertEquals(-1, LSSApi.maxReportedIngestBacklog());
        assertTrue(LSSApi.backlogWarnLatchedForTest());
        LSSApi.resetBacklogWarnLatchForTest();
        assertFalse(LSSApi.backlogWarnLatchedForTest(), "the test seam re-arms the latch");
    }

    @Test
    void assertionErrorsAreContainedTrueErrorsPropagate() {
        // Mirrors dispatchColumn's ladder: AssertionError is a consumer bug worth containing;
        // a true Error (OOME-class) must propagate to the tick loop.
        reporting(() -> { throw new AssertionError("consumer assertion"); });
        assertEquals(-1, LSSApi.maxReportedIngestBacklog(),
                "AssertionError is contained like an exception");

        LSSApi.resetBacklogWarnLatchForTest();
        var err = new Error("true error");
        reporting(() -> { throw err; });
        assertSame(err, assertThrows(Error.class, LSSApi::maxReportedIngestBacklog),
                "a true Error must propagate, not be swallowed into no-signal");
    }
}
