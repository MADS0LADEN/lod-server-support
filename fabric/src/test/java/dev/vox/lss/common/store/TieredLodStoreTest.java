package dev.vox.lss.common.store;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The {@code lodStore=full} composition: memory tier in front of SQLite, one shared
 * diagnostics object, deposits/invalidations fanned to BOTH tiers, and deliberately no
 * promotion-on-read (the Phase 2 memory-vs-SQLite A/B decides the tier's fate on
 * measurements). Built through {@link LodStores#createOrNull} — the production factory.
 */
class TieredLodStoreTest {

    private static final String OW = "minecraft:overworld";

    @TempDir
    Path tmp;

    private TieredLodStore openTiered() throws Exception {
        var env = new SqliteLodStore.Environment(this.tmp.resolve("store"), "26.2-test", 18,
                d -> this.tmp.resolve("region"), d -> "", List.of(OW), 0);
        LodStoreService store = LodStores.createOrNull(LodStoreMode.FULL, 8 << 20, env);
        TieredLodStore tiered = assertInstanceOf(TieredLodStore.class, store,
                "FULL must compose the tiered store when SQLite is available");
        assertTrue(tiered.sqliteTier().awaitSweep(10_000));
        return tiered;
    }

    private static void awaitSqliteVisible(TieredLodStore store, long pos) throws Exception {
        for (int i = 0; i < 400; i++) {
            if (store.sqliteTier().get(OW, pos) != null) return;
            Thread.sleep(25);
        }
    }

    @Test
    void depositFansOutToBothTiersAndMemoryAnswersFirst() throws Exception {
        TieredLodStore store = openTiered();
        long p = PositionUtil.packPosition(0, 0);
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        store.deposit(OW, p, data, 100);
        awaitSqliteVisible(store, p);

        assertNotNull(store.sqliteTier().get(OW, p), "deposit must reach the SQLite tier");
        long memHitsBefore = store.diagnostics().getMemHits();
        var hit = store.get(OW, p);
        assertNotNull(hit);
        assertArrayEquals(data, hit.sectionBytes());
        assertEquals(100, hit.columnTimestamp());
        assertEquals(memHitsBefore + 1, store.diagnostics().getMemHits(),
                "a resident column must be answered by the memory tier");
        store.shutdown();
    }

    @Test
    void sqliteAnswersWhenMemoryMissesAndIsNotPromoted() throws Exception {
        TieredLodStore store = openTiered();
        long p = PositionUtil.packPosition(1, 0);
        byte[] data = new byte[]{9, 8, 7};
        // Deposit into the SQLite tier ONLY — the shape of a memory eviction (or restart).
        store.sqliteTier().deposit(OW, p, data, 100);
        awaitSqliteVisible(store, p);

        long memHitsBefore = store.diagnostics().getMemHits();
        var first = store.get(OW, p);
        assertNotNull(first, "SQLite must answer a memory miss");
        assertArrayEquals(data, first.sectionBytes());
        var second = store.get(OW, p);
        assertNotNull(second);
        assertEquals(memHitsBefore, store.diagnostics().getMemHits(),
                "no promotion-on-read: repeat gets stay SQLite-tier answers");
        store.shutdown();
    }

    @Test
    void invalidateAndDeleteReachBothTiers() throws Exception {
        TieredLodStore store = openTiered();
        long p1 = PositionUtil.packPosition(2, 0);
        long p2 = PositionUtil.packPosition(3, 0);
        store.deposit(OW, p1, new byte[]{1}, 100);
        store.deposit(OW, p2, new byte[]{2}, 100);
        awaitSqliteVisible(store, p1);
        awaitSqliteVisible(store, p2);

        store.invalidate(OW, new long[]{p1});
        assertNull(store.get(OW, p1), "invalidate must be effective in the composed get");
        assertNull(store.sqliteTier().get(OW, p1), "…including the SQLite tier directly");

        store.delete(OW, p2);
        assertNull(store.get(OW, p2), "delete must be effective in the composed get");
        assertNull(store.sqliteTier().get(OW, p2));
        store.shutdown();
    }

    /** The Paper unfired-event staleness bound end-to-end at unit level: a periodic
     *  resweep that drops a stale SQLite row must ALSO evict the memory tier's copy —
     *  the front tier answers first, so without the sweep-drop fan-out the composed
     *  get() would keep serving the stale bytes the sweep just culled. */
    @Test
    void resweepDropEvictsTheMemoryTierCopy() throws Exception {
        java.nio.file.Files.createDirectories(this.tmp.resolve("region"));
        var env = new SqliteLodStore.Environment(this.tmp.resolve("store"), "26.2-test", 18,
                d -> this.tmp.resolve("region"), d -> "", List.of(OW), 1);
        TieredLodStore store = (TieredLodStore) LodStores.createOrNull(
                LodStoreMode.FULL, 8 << 20, env);
        assertNotNull(store);
        assertTrue(store.sqliteTier().awaitSweep(10_000));
        long p = PositionUtil.packPosition(5, 0);
        store.deposit(OW, p, new byte[]{7, 7, 7}, 100);
        awaitSqliteVisible(store, p);
        assertNotNull(store.get(OW, p), "memory tier must be serving before the sweep");

        // The chunk has no region file at all — the resweep's vanished-region rule
        // drops the SQLite row; the fan-out must take the memory copy with it.
        boolean gone = false;
        for (int i = 0; i < 400 && !gone; i++) {
            gone = store.get(OW, p) == null;
            Thread.sleep(25);
        }
        assertTrue(gone, "the composed get() must stop serving once the resweep drops "
                + "the row (memory tier evicted via the sweep-drop fan-out)");
        store.shutdown();
    }

    @Test
    void factoryDegradesToMemoryOnlyWhenSqliteInitFails() {
        // An impossible store dir (a FILE in the path) forces SQLite init to fail.
        var env = new SqliteLodStore.Environment(Path.of("/dev/null/impossible"),
                "26.2-test", 18, d -> this.tmp, d -> "", List.of(OW), 0);
        LodStoreService store = LodStores.createOrNull(LodStoreMode.FULL, 8 << 20, env);
        assertInstanceOf(MemoryLodStore.class, store,
                "SQLite init failure must degrade to the memory tier, never crash");
        store.shutdown();
    }
}
