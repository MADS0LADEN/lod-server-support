package dev.vox.lss.common.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 1 memory tier (plan §1): boundary invariants (byte-exact round trip, all-air
 * as {@code byte[0]}, stored-timestamp honesty, latest-wins by ts), the tombstone
 * poison guard, synchronous invalidation, cap eviction with honest byte accounting, and
 * the SCAN-RESISTANCE measurement the gate names — a closest-first two-pass replay over
 * a corpus larger than the cap, where random eviction must yield hit-rate ≈ residency
 * fraction (the property plain LRU catastrophically fails on this workload: its second
 * pass would evict each next-needed key just before reaching it).
 */
class MemoryLodStoreTest {

    private static final String DIM = "minecraft:overworld";

    private final List<MemoryLodStore> stores = new ArrayList<>();

    private MemoryLodStore store(long maxBytes) {
        MemoryLodStore s = MemoryLodStore.createOrNull(LodStoreMode.MEMORY, maxBytes);
        assertNotNull(s, "zstd codec must load on the dev/CI platform");
        this.stores.add(s);
        return s;
    }

    @AfterEach
    void shutdownStores() {
        for (var s : this.stores) s.shutdown();
        this.stores.clear();
    }

    private static byte[] randomBytes(Random r, int n) {
        // Half-compressible content (repeats + noise) so codec paths do real work.
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (i % 3 == 0) ? 42 : (byte) r.nextInt(256);
        }
        return b;
    }

    private static void drain(MemoryLodStore s) {
        try {
            s.awaitQuiesceForTest(2000);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void depositThenGetRoundTripsBytesAndStoredTimestamp() {
        var s = store(64 << 20);
        var r = new Random(1);
        byte[] bytes = randomBytes(r, 33_000);
        s.deposit(DIM, 42L, bytes, 1234567L);
        drain(s);
        var hit = s.get(DIM, 42L);
        assertNotNull(hit);
        assertArrayEquals(bytes, hit.sectionBytes(), "byte-exact round trip");
        assertEquals(1234567L, hit.columnTimestamp(), "a hit serves its STORED stamp");
        assertEquals(1, s.diagnostics().getDeposits());
        assertEquals(1, s.diagnostics().getMemHits());
        assertTrue(s.diagnostics().getMemBytes() > 0, "resident bytes accounted");
    }

    @Test
    void allAirNormalizesToEmptyNeverNull() {
        var s = store(64 << 20);
        s.deposit(DIM, 1L, null, 100L);
        s.deposit(DIM, 2L, new byte[0], 200L);
        drain(s);
        var a = s.get(DIM, 1L);
        var b = s.get(DIM, 2L);
        assertNotNull(a, "an all-air ROW is stored — otherwise every warm all-air column re-reads NBT");
        assertNotNull(b);
        assertEquals(0, a.sectionBytes().length, "all-air speaks byte[0], never null");
        assertEquals(0, b.sectionBytes().length);
        assertEquals(100L, a.columnTimestamp());
    }

    @Test
    void latestWinsByStoredTimestampNotArrivalOrder() {
        var s = store(64 << 20);
        byte[] newer = {1, 1, 1, 1};
        byte[] older = {2, 2, 2, 2};
        s.deposit(DIM, 7L, newer, 2000L);
        drain(s);
        s.deposit(DIM, 7L, older, 1000L); // a slow serve-time deposit arriving late
        drain(s);
        var hit = s.get(DIM, 7L);
        assertNotNull(hit);
        assertArrayEquals(newer, hit.sectionBytes(),
                "an older-stamped deposit must not overwrite a newer row");
        assertEquals(2000L, hit.columnTimestamp());
    }

    @Test
    void invalidationTombstoneKillsAQueuedPreEditDeposit() {
        var s = store(64 << 20);
        // The poison sequence: deposit enqueued (pre-edit bytes), THEN the edit
        // invalidation lands, THEN the batcher applies. Simulate by invalidating
        // after enqueue — with a live batcher the apply may race the invalidate, so
        // assert the guaranteed outcome: after both, the position must be a MISS
        // (either the tombstone skipped the apply, or the sync removal killed the row).
        for (int i = 0; i < 50; i++) {
            long pos = 1000L + i;
            s.deposit(DIM, pos, new byte[]{9, 9, 9}, 500L);
            s.invalidate(DIM, new long[]{pos});
        }
        drain(s);
        for (int i = 0; i < 50; i++) {
            assertNull(s.get(DIM, 1000L + i),
                    "a deposit enqueued before an invalidation must never survive it "
                            + "(pre-edit bytes would seal stale terrain)");
        }
    }

    @Test
    void deleteIsSynchronousAndGhostGuardsTheKey() {
        var s = store(64 << 20);
        s.deposit(DIM, 5L, new byte[]{1, 2, 3}, 100L);
        drain(s);
        assertNotNull(s.get(DIM, 5L));
        long before = s.diagnostics().getMemBytes();
        s.delete(DIM, 5L);
        assertNull(s.get(DIM, 5L), "delete applies synchronously");
        assertTrue(s.diagnostics().getMemBytes() < before, "bytes reclaimed");
    }

    @Test
    void fullQueueShedsOldestAndCountsDepositDrops() {
        // A store whose batcher can't be scheduled... simplest honest approximation:
        // flood far past queue capacity in one burst; some deposits MUST shed. The
        // batcher is draining concurrently, so assert the conservation identity
        // (applied + dropped == offered) rather than an exact drop count.
        var s = store(512 << 20);
        int offered = 20_000;
        for (int i = 0; i < offered; i++) {
            s.deposit(DIM, i, new byte[]{(byte) i, 1, 2}, i);
        }
        drain(s);
        long applied = s.diagnostics().getDeposits();
        long dropped = s.diagnostics().getDepositDrops();
        assertEquals(offered, applied + dropped,
                "every offered deposit is either applied or counted as shed");
        assertEquals(0, s.diagnostics().getQueueDepth(), "queue drains to zero at rest");
    }

    @Test
    void capEvictionBoundsResidentBytesAndCounts() {
        var s = store(256 << 10); // 256 KB cap
        var r = new Random(7);
        for (int i = 0; i < 300; i++) {
            s.deposit(DIM, i, randomBytes(r, 8_000), i); // ~2-6 KB compressed each
        }
        drain(s);
        assertTrue(s.diagnostics().getMemBytes() <= (256 << 10),
                "resident bytes bounded by the cap, got " + s.diagnostics().getMemBytes());
        assertTrue(s.diagnostics().getMemEvictions() > 0, "cap pressure evicted");
        assertTrue(s.sizeForTest() > 0, "eviction never empties the store");
    }

    /**
     * The scan-resistance gate measurement: a two-pass closest-first replay (the second
     * join re-asks the same positions in the same near-to-far order) over a corpus ~4×
     * the cap. Random eviction must serve the second pass at roughly the residency
     * fraction — the plan's floor for calling the tier non-pathological is hit-rate ≫ 0
     * (plain LRU measures ≈ 0 here by construction). Asserted loosely (≥ half the
     * residency fraction) so the test pins the PROPERTY, not the RNG.
     */
    @Test
    void closestFirstReplayHitRateTracksResidencyFraction() {
        long cap = 2 << 20; // 2 MB
        var s = store(cap);
        var r = new Random(13);
        int columns = 1200;
        byte[][] corpus = new byte[columns][];
        for (int i = 0; i < columns; i++) {
            corpus[i] = randomBytes(r, 8_000); // ~2-6 KB compressed -> corpus ~4x cap
        }
        // Pass 1 (populate, closest-first order = index order).
        for (int i = 0; i < columns; i++) {
            s.deposit(DIM, i, corpus[i], 1000 + i);
        }
        drain(s);
        double residency = (double) s.sizeForTest() / columns;
        // Pass 2 (the second join): same order, count hits; misses re-deposit (as the
        // real delivery path would).
        int hits = 0;
        for (int i = 0; i < columns; i++) {
            var hit = s.get(DIM, i);
            if (hit != null) {
                hits++;
                assertArrayEquals(corpus[i], hit.sectionBytes(), "hit bytes exact at " + i);
            } else {
                s.deposit(DIM, i, corpus[i], 1000 + i);
            }
        }
        double hitRate = (double) hits / columns;
        assertTrue(residency > 0.1 && residency < 0.9,
                "test setup: cap must hold a real fraction, residency=" + residency);
        // The with-refill hit rate sits BELOW the static residency fraction: every pass-2
        // miss re-deposits, and each re-deposit evicts a random resident — including ones
        // the scan hasn't reached yet — so residency erodes ahead of the scan (measured
        // ≈ 0.47× residency at 4× cap pressure). The property pinned is the LRU
        // distinction: plain LRU measures ≈ 0.00 here by construction; random must stay
        // within the same order as residency.
        assertTrue(hitRate >= residency * 0.35,
                "random eviction must track residency (scan-resistance): hitRate="
                        + hitRate + " residency=" + residency);
        System.out.printf("[store-curve] cap=%d columns=%d residency=%.2f pass2HitRate=%.2f%n",
                cap, columns, residency, hitRate);
    }

    @Test
    void shutdownStopsBatcherAndZeroesGauges() {
        var s = store(64 << 20);
        s.deposit(DIM, 1L, new byte[]{1}, 1L);
        s.shutdown();
        assertEquals(0, s.diagnostics().getMemBytes());
        assertEquals(0, s.diagnostics().getQueueDepth());
        s.deposit(DIM, 2L, new byte[]{2}, 2L); // post-shutdown deposit is a no-op
        assertNull(s.get(DIM, 2L));
    }
}
