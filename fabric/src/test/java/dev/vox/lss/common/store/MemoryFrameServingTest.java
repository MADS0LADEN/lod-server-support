package dev.vox.lss.common.store;

import dev.vox.lss.common.store.LodStoreService.FrameHit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Memory-tier frame serving (compressed-columns plan §3): {@code getFrame} hands back
 * the RESIDENT compressed entry verbatim (deliberately unvalidated — review A8,
 * accepted: session-lifetime RAM is not the bit-rot threat model), and
 * {@code depositFrame} stores the wire frame without a second compress.
 */
class MemoryFrameServingTest {

    private static final String OW = "minecraft:overworld";

    private static MemoryLodStore open() {
        var store = MemoryLodStore.createOrNull(LodStoreMode.MEMORY, 64L * 1024 * 1024);
        assumeTrue(store != null, "zstd native unavailable");
        return store;
    }

    @Test
    void getFrameReturnsTheResidentFrameVerbatim() throws Exception {
        var codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null);
        var store = open();
        try {
            byte[] raw = new byte[4096];
            store.deposit(OW, 42L, raw, 111L);
            store.awaitQuiesceForTest(3000);
            FrameHit hit = store.getFrame(OW, 42L);
            assertNotNull(hit);
            assertEquals(raw.length, hit.usize());
            assertEquals(111L, hit.columnTimestamp());
            assertArrayEquals(raw, codec.decompress(hit.frame(), hit.usize()));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void depositFrameSkipsTheSecondCompress() throws Exception {
        var codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null);
        var store = open();
        try {
            byte[] raw = new byte[4096];
            byte[] wireFrame = codec.compress(raw);
            assertTrue(store.depositFrame(OW, 7L, wireFrame, raw.length,
                    LodStoreService.contentHash(raw), LodStoreService.contentHash(wireFrame),
                    222L, 200L));
            store.awaitQuiesceForTest(3000);
            FrameHit hit = store.getFrame(OW, 7L);
            assertNotNull(hit);
            assertSame(wireFrame, hit.frame(),
                    "the resident entry IS the wire frame — no recompress on the batcher");
            // The raw read path still round-trips off the same entry.
            var rawHit = store.get(OW, 7L);
            assertNotNull(rawHit);
            assertArrayEquals(raw, rawHit.sectionBytes());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void allAirEntryServesTheEmptyFrameShape() throws Exception {
        var store = open();
        try {
            store.deposit(OW, 9L, null, 333L);
            store.awaitQuiesceForTest(3000);
            FrameHit hit = store.getFrame(OW, 9L);
            assertNotNull(hit);
            assertEquals(0, hit.usize());
            assertEquals(0, hit.frame().length);
        } finally {
            store.shutdown();
        }
    }
}
