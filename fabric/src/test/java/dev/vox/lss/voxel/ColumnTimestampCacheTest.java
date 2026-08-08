package dev.vox.lss.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ColumnTimestampCacheTest {
    private static final long DEFAULT_MAX = 32L * 1024 * 1024; // bytes (D0 tile cache)

    /** Epoch-relative stamp: the tile cache stores u32 offsets from TS_EPOCH_SECONDS,
     *  so fixture stamps must be in-range to round-trip losslessly (pre-epoch values
     *  clamp UP by design — pinned in the truth table, not scattered through here). */
    private static long ts(long n) {
        return ColumnTimestampCache.TS_EPOCH_SECONDS + n;
    }

    private ColumnTimestampCache cache;
    private long now;

    @BeforeEach
    void setUp() {
        cache = new ColumnTimestampCache(DEFAULT_MAX, 0);
        now = LSSConstants.epochSeconds();
    }

    @Test
    void putAndGet() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        assertEquals(ts(100L), cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
    }

    @Test
    void getMissingReturnsZero() {
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 999L));
    }

    @Test
    void getMissingDimensionReturnsZero() {
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_THE_NETHER, 1L));
    }

    @Test
    void invalidateRemovesEntries() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L});
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(ts(200L), cache.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
    }

    @Test
    void invalidateReturnsRemovedCount() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);
        assertEquals(2, cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L, 2L, 3L}),
                "returns the count actually removed (3L was never cached)");
        assertEquals(0, cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L}),
                "already-removed positions count zero — the debounced save trips only on a real removal");
        assertEquals(0, cache.invalidate("lss_test:absent", new long[]{1L}), "unknown dimension removes nothing");
    }

    @Test
    void invalidateCleansInsertionTimeToo() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L});
        assertEquals(0, cache.size());
        // Re-insert — should work cleanly with no stale insertion time
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(200L), now);
        assertEquals(ts(200L), cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(1, cache.size());
    }

    @Test
    void sizeCountsAcrossDimensions() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, 3L, ts(300L), now);
        assertEquals(3, cache.size());
    }

    // ---- The §2.2 clamp truth table ----

    @Test
    void clampTruthTableEveryDirectionIsUpOrAbsent() {
        String DIM = LSSConstants.DIM_STR_OVERWORLD;
        // D0 §2.2: overstating a stamp forces a redundant re-serve (safe); understating
        // can answer up_to_date against a stale claim (forbidden). Every clamp rounds
        // UP or refuses to fabricate.
        long epoch = ColumnTimestampCache.TS_EPOCH_SECONDS;
        cache.put(DIM, 1L, epoch, now);
        assertEquals(epoch + 1, cache.get(DIM, 1L),
                "the epoch itself collides with the absent sentinel — +1 s overstate");
        cache.put(DIM, 2L, epoch - 500_000, now);
        assertEquals(epoch + 1, cache.get(DIM, 2L),
                "pre-epoch (skewed clock) clamps UP to epoch+1 — the claim came from the"
                        + " same skewed clock, so epoch+1 <= claim fails and it re-serves");
        cache.put(DIM, 3L, 0L, now);
        assertEquals(0, cache.get(DIM, 3L), "ts 0 stores NOTHING (no fabricated claim)");
        cache.put(DIM, 4L, -5L, now);
        assertEquals(0, cache.get(DIM, 4L), "negative ts stores nothing");
        int sizeBefore = cache.size();
        cache.put(DIM, 5L, ts(100L), now);
        assertEquals(sizeBefore + 1, cache.size(), "a fresh in-range put counts");
        cache.put(DIM, 5L, 0L, now);
        assertEquals(0, cache.get(DIM, 5L),
                "a ts<=0 put CLEARS an existing slot (router-equivalent to the old map)");
        assertEquals(sizeBefore, cache.size(),
                "the clear decrements size — the §8 ts<=0 delta assertion");
        cache.put(DIM, 6L, epoch + 0xFFFF_FFFFL + 99, now);
        assertEquals(epoch + 0xFFFF_FFFFL, cache.get(DIM, 6L),
                "offset overflow (~year 2161) clamps to the u32 max — documented unreachable");
        long real = epoch + 50_000_000;
        cache.put(DIM, 7L, real, now);
        assertEquals(real, cache.get(DIM, 7L), "every in-range stamp is LOSSLESS");
    }

    @Test
    void tsZeroPutStillFiresTheMissClearChokePoint() {
        // §8's "clearMiss still fires" row needs a memo-ENABLED cache (the shared
        // fixture runs ttl 0). clearMiss sits OUTSIDE put's if/else by design: a
        // clearing put is still a statement about the position, and a refactor moving
        // it into the positive branch would leave an authoritative miss memoized for a
        // position the server just cleared.
        var memoCache = new ColumnTimestampCache(DEFAULT_MAX, 60_000_000_000L);
        String DIM = LSSConstants.DIM_STR_OVERWORLD;
        memoCache.putMiss(DIM, 9L, 0L);
        assertTrue(memoCache.isFreshMiss(DIM, 9L, 1L));
        memoCache.put(DIM, 9L, 0L, now);
        assertFalse(memoCache.isFreshMiss(DIM, 9L, 1L),
                "a ts<=0 put must clear the miss memo exactly like a positive stamp");
    }

    @Test
    void skewedClockPreEpochClampLatchesTheWarnOnce() {
        // §2.2's visibility requirement: the pre-epoch clamp permanently costs affected
        // columns their up_to_date resolution (the wire carries the unclamped stamp, so
        // equality never holds) — accepted ONLY on condition it is visible. The latch
        // is the observable; in-range traffic must never trip it.
        String DIM = LSSConstants.DIM_STR_OVERWORLD;
        cache.put(DIM, 1L, ts(100L), now);
        assertFalse(cache.preEpochWarnedForTest(), "in-range stamps never warn");
        cache.put(DIM, 2L, 0L, now);
        assertFalse(cache.preEpochWarnedForTest(), "a clear is not a clamp — no warn");
        cache.put(DIM, 3L, 1_000L, now);
        assertTrue(cache.preEpochWarnedForTest(), "the first pre-epoch clamp warns");
        cache.put(DIM, 4L, ColumnTimestampCache.TS_EPOCH_SECONDS, now);
        assertTrue(cache.preEpochWarnedForTest(),
                "the epoch-collision clamp shares the latch — once per session, not per stamp");
    }

    // ---- Size-based eviction ----

    @Test
    void evictIfOversizedDoesNothingUnderLimit() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);
        assertEquals(0, cache.evictIfOversized());
        assertEquals(2, cache.size());
    }

    @Test
    void evictIfOversizedRemovesOldestTiles() {
        // Tile-granular eviction (D0): budget = 2 tiles (the ctor floors bytes at one
        // TILE_HEAP_BYTES, so 2*4352 buys exactly two). Four regions, oldest-touched
        // first — the victims are whole tiles, ranked by lastTouch (the put's now arg).
        // The OLDEST tile holds THREE entries: the return value and the mirror must
        // report the SUMMED liveCount (a per-tile count would under-report the soak's
        // tscache.evictions by up to 1024x).
        var smallCache = new ColumnTimestampCache(2L * 4352, 0);
        smallCache.put(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(1, 1), ts(2L), now);
        smallCache.put(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(2, 2), ts(3L), now);
        for (int i = 0; i < 4; i++) {
            long pos = dev.vox.lss.common.PositionUtil.packPosition(i * 40, 0);
            smallCache.put(LSSConstants.DIM_STR_OVERWORLD, pos, ts(i * 100L + 1), now + i);
        }
        assertEquals(6, smallCache.size());

        int evicted = smallCache.evictIfOversized();
        assertEquals(4, evicted,
                "two oldest-touched tiles: the 3-entry region-(0,0) tile + the 1-entry "
                        + "region-(1,0) tile — the count is the SUMMED liveCount");
        assertEquals(4L, smallCache.getEvictionCount(), "the cumulative counter sums too");
        assertEquals(2, smallCache.size());
        assertEquals(Map.of(LSSConstants.DIM_STR_OVERWORLD, 2), smallCache.sizesPerDimension(),
                "the cross-thread mirror drops by the summed liveCount");
        assertEquals(0L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(0, 0)));
        assertEquals(0L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(1, 1)));
        assertEquals(0L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(40, 0)));
        assertEquals(ts(301L), smallCache.get(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(120, 0)));
    }

    @Test
    void evictOnEmptyCacheReturnsZero() {
        assertEquals(0, cache.evictIfOversized());
    }

    // ---- Persistence ----

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tempDir) {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, 3L, ts(300L), now);

        cache.save(tempDir);

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        loaded.load(tempDir);

        assertEquals(3, loaded.size());
        assertEquals(ts(100L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(ts(200L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
        assertEquals(ts(300L), loaded.get(LSSConstants.DIM_STR_THE_NETHER, 3L));
    }

    @Test
    void saveAndLoadRoundTripLargeCrossesBufferBoundaries(@TempDir Path tempDir) {
        // 12k columns spread across raw packed keys 0..5999 land in ~189 v2 tiles per
        // dimension (~4108 B each, ~15.9 per 64 KB buffer), so the save flushes and the
        // load refills the stream buffer many times, with tile records straddling every
        // buffer edge — a small corpus fits ONE buffer and never crosses a boundary.
        var big = new ColumnTimestampCache(DEFAULT_MAX, 0);
        for (long i = 0; i < 6000; i++) {
            big.put(LSSConstants.DIM_STR_OVERWORLD, i, ts(i + 1_000_000L), now);
            big.put(LSSConstants.DIM_STR_THE_NETHER, i, ts(i + 2_000_000L), now);
        }
        big.save(tempDir);

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        loaded.load(tempDir);
        assertEquals(12000, loaded.size());
        for (long i : new long[]{0, 1, 4095, 4096, 4097, 5999}) {
            assertEquals(ts(i + 1_000_000L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, i));
            assertEquals(ts(i + 2_000_000L), loaded.get(LSSConstants.DIM_STR_THE_NETHER, i));
        }
    }

    @Test
    void loadFromNonexistentDirDoesNothing(@TempDir Path tempDir) {
        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        loaded.load(tempDir.resolve("nonexistent"));
        assertEquals(0, loaded.size());
    }

    @Test
    void loadFromEmptyDirDoesNothing(@TempDir Path tempDir) {
        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        loaded.load(tempDir);
        assertEquals(0, loaded.size());
    }

    @Test
    void saveEmptyCacheIsNoOp(@TempDir Path tempDir) {
        cache.save(tempDir);
        // No file should be created
        assertFalse(tempDir.resolve("lss-timestamps.bin").toFile().exists());
    }

    @Test
    void saveOverwritesPreviousFile(@TempDir Path tempDir) {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.save(tempDir);

        // Overwrite with different data
        var cache2 = new ColumnTimestampCache(DEFAULT_MAX, 0);
        cache2.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(999L), now);
        cache2.put(LSSConstants.DIM_STR_OVERWORLD, 5L, ts(500L), now);
        cache2.save(tempDir);

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        loaded.load(tempDir);
        assertEquals(2, loaded.size());
        assertEquals(ts(999L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(ts(500L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 5L));
    }

    @Test
    void snapshotForSaveIsIndependent() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        var snapshot = cache.snapshotForSave();

        // Modify original — snapshot should be unaffected
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(999L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);

        assertEquals(ts(100L), snapshot.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(0L, snapshot.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
    }

    @Test
    void loadPreservesExistingEntries(@TempDir Path tempDir) {
        // The additive-merge contract at SLOT granularity: (0,0) and (1,1) share a
        // REGION TILE, so a whole-tile put on load would silently delete the in-memory
        // stamp — the merge must go slot by slot. (5,5) pins the file-wins direction
        // for a slot both sides hold; 99L pins the different-tile case.
        long pFile = dev.vox.lss.common.PositionUtil.packPosition(0, 0);
        long pMem = dev.vox.lss.common.PositionUtil.packPosition(1, 1);
        long pBoth = dev.vox.lss.common.PositionUtil.packPosition(5, 5);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, pFile, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, pBoth, ts(300L), now);
        cache.save(tempDir);

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, pMem, ts(999L), now);
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, pBoth, ts(111L), now);
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 99L, ts(888L), now);
        loaded.load(tempDir);

        assertEquals(4, loaded.size());
        assertEquals(ts(100L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, pFile));
        assertEquals(ts(999L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, pMem),
                "an in-memory stamp sharing a tile with file entries survives the load");
        assertEquals(ts(300L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, pBoth),
                "a slot both sides hold takes the FILE's stamp (loaded entries overwrite)");
        assertEquals(ts(888L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 99L));
        assertEquals(Map.of(LSSConstants.DIM_STR_OVERWORLD, 4), loaded.sizesPerDimension(),
                "the mirror agrees after a merging load");
    }

    @Test
    void v2TruncatedFileKeepsCompleteTilesAndTheMirrorAgrees(@TempDir Path tempDir) throws IOException {
        // §8's v2-truncation pin: power loss mid-tile keeps every COMPLETE tile — and
        // the liveSizes mirror must account for them (the mirror is bumped per tile,
        // inside the loop; a batched end-of-dimension bump left truncation-survivor
        // tiles invisible, and the next invalidation drove the exporter's
        // per-dimension gauge negative for the rest of the session).
        // Hand-written v2 so the tile ORDER is deterministic (a save()'s map iteration
        // order is not): one complete 2-entry tile, then a second tile chopped mid-slots.
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(2); // v2
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(2); // declares two tiles...
            out.writeLong(0L); // region (0,0)
            out.writeInt(2);
            for (int s = 0; s < 1024; s++) out.writeInt(s == 0 ? 100 : s == 1 ? 150 : 0);
            out.writeLong(1L << 32); // region (1,0)...
            out.writeInt(1);
            for (int s = 0; s < 500; s++) out.writeInt(0); // ...power loss mid-slots
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(2, loaded.size(), "the complete first tile (2 entries) survives");
        assertEquals(ts(100L), loaded.get(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(0, 0)));
        assertEquals(Map.of(LSSConstants.DIM_STR_OVERWORLD, 2), loaded.sizesPerDimension(),
                "the mirror sees truncation survivors — size() and the gauge must agree");
        int removed = loaded.invalidate(LSSConstants.DIM_STR_OVERWORLD,
                new long[]{dev.vox.lss.common.PositionUtil.packPosition(0, 0),
                        dev.vox.lss.common.PositionUtil.packPosition(0, 1)});
        assertEquals(2, removed);
        assertEquals(Map.of(LSSConstants.DIM_STR_OVERWORLD, 0), loaded.sizesPerDimension(),
                "post-truncation invalidation lands at zero, never negative");
    }

    // ---- Corrupt-file load guards ----
    // load() runs in the OffThreadProcessor constructor at server boot: a corrupt
    // lss-timestamps.bin (power-loss truncation, disk garbage) must never throw —
    // it may only degrade to a cold cache that rebuilds itself.

    private static DataOutputStream cacheFileOut(Path tempDir) throws IOException {
        return new DataOutputStream(Files.newOutputStream(tempDir.resolve("lss-timestamps.bin")));
    }

    @Test
    void loadTruncatedFileKeepsCompleteEntriesAndStaysUsable(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1); // FORMAT_VERSION
            out.writeInt(1); // one dimension
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(4); // declares 4 entries...
            out.writeLong(11L); out.writeLong(ts(100L));
            out.writeLong(22L); out.writeLong(ts(200L));
            out.writeLong(33L); // ...but power loss truncated mid-entry
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(ts(100L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 11L));
        assertEquals(ts(200L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 22L));
        assertEquals(0L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 33L),
                "the truncated entry must not be loaded");
        // The cache must remain fully usable after the failed load
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 33L, ts(300L), now);
        assertEquals(ts(300L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 33L));
    }

    @Test
    void loadGarbageFileShorterThanHeaderIsDiscarded(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("lss-timestamps.bin"),
                new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE}); // EOF inside the version int

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size());
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        assertEquals(ts(100L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
    }

    @Test
    void loadUnsupportedVersionIsDiscarded(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(99); // future/corrupt format version
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1);
            out.writeLong(1L); out.writeLong(ts(100L));
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size(), "a foreign format version must not be interpreted");
    }

    @Test
    void loadNegativeEntryCountStopsCleanly(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(-5); // corrupt count: stream can no longer be positioned
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size());
    }

    @Test
    void loadOverCapButFileValidCountLoadsThenIsEvictable(@TempDir Path tempDir) throws IOException {
        // 6 real entries with a cap of 5: this is a LEGITIMATE overshoot (save()/snapshotForSave
        // never evict, so a file can hold more than the cap). It must load — not be discarded as
        // corrupt — and the normal eviction pass then trims it back to the cap.
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(6);
            for (int i = 0; i < 6; i++) {
                // i*40 chunks apart: entries span multiple regions, so tile-granular
                // eviction has more than one victim candidate.
                out.writeLong(dev.vox.lss.common.PositionUtil.packPosition(i * 40, 0));
                out.writeLong(ts(i * 100L + 1));
            }
        }

        // Budget = ONE tile (the ctor floors at TILE_HEAP_BYTES): the six entries span
        // SIX regions (i*40 → region x 0,1,2,3,5,6), so the load must ACCEPT all six
        // tiles and the next eviction pass trims EXACTLY back to the byte budget — the
        // §8 trim-to-byte-budget re-expression of the old exact-trim-to-cap arm (a loop
        // that stops one tile early leaves the dimension permanently over budget, the
        // store-cap history's vacuous-pin lesson).
        var small = new ColumnTimestampCache(1, 0);
        assertDoesNotThrow(() -> small.load(tempDir));
        assertEquals(6, small.size(), "a valid over-cap count must load, not be discarded");
        assertEquals(5, small.evictIfOversized(),
                "five of the six one-entry tiles must go — trimmed exactly to the one-tile budget");
        assertEquals(1, small.size(), "exactly the budgeted tile survives");
        assertEquals(0, small.evictIfOversized(), "at budget: a second pass evicts nothing");
    }

    @Test
    void loadEntryCountBeyondFileContentsIsDiscarded(@TempDir Path tempDir) throws IOException {
        // A count larger than the file can physically hold is real corruption (the stream would
        // desync into the next dimension), so the rest is discarded.
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1_000_000); // file holds only a handful of entries
            out.writeLong(1L); out.writeLong(ts(100L));
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size(), "an impossible count must not allocate/load");
    }

    @Test
    void loadBadCountInSecondDimensionKeepsFirstDimension(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(2); // two dimensions
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1);
            out.writeLong(7L); out.writeLong(ts(70L));
            out.writeUTF(LSSConstants.DIM_STR_THE_NETHER);
            out.writeInt(-1); // corrupt second dimension: discard the rest, keep what loaded
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(ts(70L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 7L));
        assertEquals(1, loaded.size());
    }

    // ---- SP-051: per-dimension isolation (same packed position, two dimensions) ----

    @Test
    void putGetInvalidateAreDimensionScoped() {
        long p = 42L;
        cache.put(LSSConstants.DIM_STR_OVERWORLD, p, ts(100L), now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, p, ts(200L), now);
        assertEquals(ts(100L), cache.get(LSSConstants.DIM_STR_OVERWORLD, p));
        assertEquals(ts(200L), cache.get(LSSConstants.DIM_STR_THE_NETHER, p));

        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{p});
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, p), "invalidate is dimension-scoped");
        assertEquals(ts(200L), cache.get(LSSConstants.DIM_STR_THE_NETHER, p),
                "the same packed position in another dimension survives");
    }

    // ---- SP-052: eviction refreshes age on re-put and evicts per dimension ----

    @Test
    void rePutProtectsItsWholeTileIncludingColdTileMatesFromEviction() {
        // The DELIBERATE §5 weakening, stated as a pin: eviction age is per-TILE
        // lastTouch, and per-entry age is GONE. Touching one column therefore keeps its
        // 1023 tile-mates resident — including a column older than everything in the
        // evicted tile — and conversely a hot column's cold tile-mates ride its
        // protection. A future "fix" back toward per-entry age must red HERE.
        long pA1 = dev.vox.lss.common.PositionUtil.packPosition(0, 0);   // tile A
        long pA2 = dev.vox.lss.common.PositionUtil.packPosition(1, 1);   // tile A (cold mate)
        long pB = dev.vox.lss.common.PositionUtil.packPosition(40, 0);   // tile B
        long pC = dev.vox.lss.common.PositionUtil.packPosition(80, 0);   // tile C
        var small = new ColumnTimestampCache(2L * 4352, 0); // budget: 2 tiles
        small.put(LSSConstants.DIM_STR_OVERWORLD, pA2, ts(5L), 1L);  // the OLDEST column
        small.put(LSSConstants.DIM_STR_OVERWORLD, pA1, ts(10L), 1L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, pB, ts(20L), 2L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, pA1, ts(11L), 10L); // refresh: tile A, not pA1
        small.put(LSSConstants.DIM_STR_OVERWORLD, pC, ts(30L), 11L);  // 3 tiles, budget 2
        assertEquals(1, small.evictIfOversized(), "one excess tile (one entry) evicted");
        assertEquals(ts(11L), small.get(LSSConstants.DIM_STR_OVERWORLD, pA1),
                "the re-put refreshed tile A's age, so it survives");
        assertEquals(ts(5L), small.get(LSSConstants.DIM_STR_OVERWORLD, pA2),
                "pA2 is the OLDEST column in the cache yet survives — tile-mate protection "
                        + "is the accepted cost of tile-granular age");
        assertEquals(0L, small.get(LSSConstants.DIM_STR_OVERWORLD, pB),
                "the oldest TILE (B) is the victim, though its column is younger than pA2");
    }

    @Test
    void evictionIsScopedToTheOversizedDimension() {
        // Budget: 1 tile per dimension. Overworld holds two tiles (over budget), the
        // nether one (within) — eviction must only touch the oversized dimension.
        var small = new ColumnTimestampCache(1, 0); // floors to one TILE_HEAP_BYTES
        small.put(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(0, 0), ts(10L), 1L);
        small.put(LSSConstants.DIM_STR_OVERWORLD,
                dev.vox.lss.common.PositionUtil.packPosition(40, 0), ts(30L), 3L);
        small.put(LSSConstants.DIM_STR_THE_NETHER, 9L, ts(90L), 1L);
        small.evictIfOversized();
        assertEquals(ts(90L), small.get(LSSConstants.DIM_STR_THE_NETHER, 9L),
                "a within-cap dimension is untouched by another dimension's eviction");
    }

    // ---- SP-054: save fails gracefully; snapshotForSave is isolated from later mutation ----

    @Test
    void saveToAPathThatIsAFileFailsGracefullyLeavingNoTempFile(@TempDir Path tempDir) throws IOException {
        var blocker = tempDir.resolve("blocker"); // a regular file where save expects a directory
        Files.writeString(blocker, "x");
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        assertDoesNotThrow(() -> cache.save(blocker), "the IO failure is swallowed, not thrown");
        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("lss-timestamps.bin.tmp")),
                    "the half-written temp file is cleaned up / never created");
        }
    }

    @Test
    void snapshotForSaveIsUnaffectedByLaterMutation() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        var snap = cache.snapshotForSave();
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L}); // mutate the original after snapshotting
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, ts(200L), now);
        assertEquals(ts(100L), snap.get(LSSConstants.DIM_STR_OVERWORLD, 1L),
                "the snapshot keeps the entry the original invalidated");
        assertEquals(0L, snap.get(LSSConstants.DIM_STR_OVERWORLD, 2L),
                "the snapshot excludes a put made after it was taken");
    }

    // ---- Miss memo (docs/planning/miss-memo-design.md) ----

    private static final long TTL = 30_000_000_000L; // 30 s in nanos
    private static final String OW = LSSConstants.DIM_STR_OVERWORLD;

    private static ColumnTimestampCache memoCache() {
        return new ColumnTimestampCache(1000, TTL);
    }

    @Test
    void missIsFreshUntilExactlyTheTtlBoundary() {
        var c = memoCache();
        c.putMiss(OW, 5L, 1_000L);
        assertTrue(c.isFreshMiss(OW, 5L, 1_000L), "fresh immediately");
        assertTrue(c.isFreshMiss(OW, 5L, 1_000L + TTL - 1), "fresh one nano before the deadline");
        assertFalse(c.isFreshMiss(OW, 5L, 1_000L + TTL), "expired AT the deadline");
        assertEquals(0, c.missCount(), "the expired entry was lazily removed");
    }

    @Test
    void missDeadlineComparisonSurvivesNanoTimeWrap() {
        // nanoTime is an arbitrary-origin long: a deadline computed near Long.MAX_VALUE
        // wraps negative. The overflow-safe idiom (deadline - now > 0) keeps a wrapped
        // deadline fresh; a regression to the naive (deadline > now) compares a wrapped
        // negative deadline against a still-positive clock and expires it instantly.
        var c = memoCache();
        long now = Long.MAX_VALUE - TTL / 2; // deadline = now + TTL wraps negative
        c.putMiss(OW, 5L, now);
        assertTrue(c.isFreshMiss(OW, 5L, now + 1),
                "fresh while the clock is pre-wrap and the deadline has wrapped");
        assertFalse(c.isFreshMiss(OW, 5L, now + TTL), "expires exactly at the wrapped deadline");
    }

    @Test
    void invalidateStampsPreservesTheMiss() {
        // The disk-drain's not-found branch runs the STAMPS-ONLY invalidation in the same
        // cycle that just memoized the miss — re-merging it into the full invalidate()
        // erases every memo the instant it is written (the processor churn-loop test pins
        // that end-to-end; this pins the cache-level split directly).
        var c = memoCache();
        c.put(OW, 5L, 100L, 0L);
        c.putMiss(OW, 5L, 1_000L);
        assertEquals(1, c.invalidateStamps(OW, new long[]{5L}), "the stamp is invalidated");
        assertTrue(c.isFreshMiss(OW, 5L, 1_000L), "the miss survives the stamps-only path");
    }

    @Test
    void positiveStampIsTheMissClearChokePoint() {
        var c = memoCache();
        c.putMiss(OW, 5L, 1_000L);
        c.put(OW, 5L, 100L, 0L); // any serve path lands here
        assertFalse(c.isFreshMiss(OW, 5L, 1_000L),
                "a served column can never stay memoized absent");
    }

    @Test
    void invalidateClearsTheMissEvenWithNoStampPresent() {
        var c = memoCache();
        c.putMiss(OW, 5L, 1_000L);
        assertEquals(0, c.invalidate(OW, new long[]{5L}),
                "no timestamp entry existed — invalidate still returns the stamp count");
        assertFalse(c.isFreshMiss(OW, 5L, 1_000L),
                "an invalidation means a save happened: the memoized absence is falsified");
    }

    @Test
    void clearMissDropsExactlyThatPositionAndDimensionsAreIsolated() {
        var c = memoCache();
        c.putMiss(OW, 5L, 1_000L);
        c.putMiss(OW, 6L, 1_000L);
        c.putMiss(LSSConstants.DIM_STR_THE_NETHER, 5L, 1_000L);
        c.clearMiss(OW, 5L);
        assertFalse(c.isFreshMiss(OW, 5L, 1_000L));
        assertTrue(c.isFreshMiss(OW, 6L, 1_000L), "sibling position untouched");
        assertTrue(c.isFreshMiss(LSSConstants.DIM_STR_THE_NETHER, 5L, 1_000L),
                "same packed position in another dimension untouched");
    }

    @Test
    void ttlZeroDisablesTheMemoWholesale() {
        var c = new ColumnTimestampCache(1000, 0);
        c.putMiss(OW, 5L, 1_000L);
        assertFalse(c.isFreshMiss(OW, 5L, 1_000L), "ttl 0 is the config kill switch");
        assertEquals(0, c.missCount(), "nothing is even stored");
    }

    @Test
    void missesNeverSurviveSaveLoadOrSnapshot(@TempDir Path tempDir) {
        var c = memoCache();
        c.put(OW, 1L, ts(100L), now);
        c.putMiss(OW, 5L, 1_000L);
        c.save(tempDir);
        var reloaded = memoCache();
        reloaded.load(tempDir);
        assertEquals(ts(100L), reloaded.get(OW, 1L), "stamps persist");
        assertFalse(reloaded.isFreshMiss(OW, 5L, 1_000L),
                "misses are seconds-fresh info and must not survive a process boundary");
        assertFalse(c.snapshotForSave().isFreshMiss(OW, 5L, 1_000L),
                "the save snapshot excludes misses too");
    }

    @Test
    void oversizedMissMemoIsEvictedWholesaleBeforeStampEviction() {
        // Memo overflow cap = bytes/64 (the retired mbToEntries value): a 200-byte
        // budget (floored to one tile for STAMPS) gives a memo cap of 4352/64 = 68 —
        // exceed it and the map clears wholesale while the stamp tile survives.
        var c = new ColumnTimestampCache(200, TTL);
        for (long p = 0; p < 100; p++) c.putMiss(OW, p, 1_000L);
        c.put(OW, 100L, ts(1L), now); // within the stamp budget (one tile)
        c.evictIfOversized();
        assertEquals(0, c.missCount(),
                "an over-cap miss map clears wholesale — a miss is always cheaper to lose than a stamp");
        assertEquals(ts(1L), c.get(OW, 100L), "the within-cap stamp survives");
    }

    @Test
    void loadSweepsOrphanedOldTmpFilesButKeepsFreshOnes(@TempDir Path dir) throws IOException {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(100L), now);
        cache.save(dir);

        // A process killed mid-write (kill -9, OOM, power loss) leaves its unique-named tmp
        // behind: the IOException cleanup never ran and no later save reuses the name — a
        // multi-MB orphan per crash, unbounded across the server's life without this sweep.
        Path orphan = dir.resolve("lss-timestamps.bin.tmp.deadbeef");
        Files.write(orphan, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 2 * 60 * 60 * 1000L)); // 2 h old — past the 1 h floor
        // A FRESH tmp may be a /reload predecessor's final save still being written — must survive.
        Path fresh = dir.resolve("lss-timestamps.bin.tmp.cafebabe");
        Files.write(fresh, new byte[]{4, 5, 6});

        var reloaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        reloaded.load(dir);

        assertFalse(Files.exists(orphan), "an old orphaned tmp is swept at load");
        assertTrue(Files.exists(fresh), "a fresh tmp (possible in-flight /reload writer) survives the sweep");
        assertEquals(ts(100L), reloaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L),
                "the sweep does not disturb the load itself");
    }

    // ---- D0 §8: the semantics-preservation proof + v2 persistence hardening ----

    @Test
    void modelDifferentialFuzzMatchesAReferenceMapExactly() {
        // Random put/get/invalidate/invalidateStamps sequences (no eviction) against a
        // reference Long2LongOpenHashMap — the tile structure must be semantically
        // invisible for in-range stamps. THE proof the redesign changed no router-visible
        // behavior (design §8); a fixed seed keeps it deterministic.
        var rng = new java.util.Random(0x20260808L);
        var reference = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        var tiles = new ColumnTimestampCache(1024L * 1024 * 1024, 0);
        String dim = LSSConstants.DIM_STR_OVERWORLD;
        for (int op = 0; op < 20_000; op++) {
            long pos = dev.vox.lss.common.PositionUtil.packPosition(
                    rng.nextInt(200) - 100, rng.nextInt(200) - 100);
            switch (rng.nextInt(4)) {
                case 0 -> {
                    long stamp = ts(rng.nextInt(1_000_000) + 1);
                    tiles.put(dim, pos, stamp, now);
                    reference.put(pos, stamp);
                }
                case 1 -> assertEquals(reference.getOrDefault(pos, 0L),
                        tiles.get(dim, pos), "op " + op + " get " + pos);
                case 2 -> {
                    tiles.invalidate(dim, new long[]{pos});
                    reference.remove(pos);
                }
                case 3 -> {
                    tiles.invalidateStamps(dim, new long[]{pos});
                    reference.remove(pos);
                }
            }
        }
        assertEquals(reference.size(), tiles.size(), "final size parity");
        // The cross-thread liveSizes MIRROR is a second, independently-maintained
        // accounting path (incremental merges, not a recount) — the fuzz must prove it
        // too, or a single wrong bump delta ships as a permanently-skewed exporter
        // gauge (§8: size()/sizesPerDimension parity with the model).
        assertEquals(Map.of(dim, reference.size()), tiles.sizesPerDimension(),
                "mirror parity after 20k mixed ops");
        for (var e : reference.long2LongEntrySet()) {
            assertEquals(e.getLongValue(), tiles.get(dim, e.getLongKey()));
        }
    }

    @Test
    void v1MigrationGoldenLoadsExactStampsAndTileShapes(@TempDir Path tempDir) throws IOException {
        // A hand-written RELEASED-format v1 stream (design §6's migration golden):
        // entries across two dimensions and two regions must land in the right tiles
        // with exact stamps, and the NEXT save must write v2 (round-trip re-load pins
        // it — an accidental v1 re-write would warn-discard here).
        long pA = dev.vox.lss.common.PositionUtil.packPosition(5, 7);
        long pB = dev.vox.lss.common.PositionUtil.packPosition(-40, 100);
        long pZero = dev.vox.lss.common.PositionUtil.packPosition(70, 70);
        long pNeg = dev.vox.lss.common.PositionUtil.packPosition(71, 71);
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1); // released FORMAT_VERSION
            out.writeInt(2);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(4);
            out.writeLong(pA); out.writeLong(ts(123L));
            out.writeLong(pB); out.writeLong(ts(456L));
            // §8: v1 files could carry non-positive stamps (the old map stored anything);
            // migration must DROP them — under tiles a stored 0/negative would either
            // fabricate a slot or corrupt the liveCount.
            out.writeLong(pZero); out.writeLong(0L);
            out.writeLong(pNeg); out.writeLong(-4L);
            out.writeUTF(LSSConstants.DIM_STR_THE_NETHER);
            out.writeInt(1);
            out.writeLong(pA); out.writeLong(ts(789L));
        }
        var migrated = new ColumnTimestampCache(DEFAULT_MAX, 0);
        migrated.load(tempDir);
        assertEquals(ts(123L), migrated.get(LSSConstants.DIM_STR_OVERWORLD, pA));
        assertEquals(ts(456L), migrated.get(LSSConstants.DIM_STR_OVERWORLD, pB));
        assertEquals(ts(789L), migrated.get(LSSConstants.DIM_STR_THE_NETHER, pA));
        assertEquals(0L, migrated.get(LSSConstants.DIM_STR_OVERWORLD, pZero),
                "a v1 zero-stamp record must migrate to ABSENT, not a fabricated slot");
        assertEquals(0L, migrated.get(LSSConstants.DIM_STR_OVERWORLD, pNeg),
                "a v1 negative-stamp record must migrate to ABSENT");
        assertEquals(3, migrated.size());

        migrated.save(tempDir); // must write v2
        var reloaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        reloaded.load(tempDir);
        assertEquals(ts(123L), reloaded.get(LSSConstants.DIM_STR_OVERWORLD, pA),
                "the post-migration save must be a loadable v2 file");
        assertEquals(3, reloaded.size());
    }

    @Test
    void v2CorruptTileCountDiscardsCleanly(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(2);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1_000_000); // tiles the file cannot possibly hold
        }
        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size());
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 1L, ts(5L), now);
        assertEquals(ts(5L), loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L),
                "usable after the discarded load");
    }

    @Test
    void v2LiveCountDisagreementDiscardsTheRest(@TempDir Path tempDir) throws IOException {
        // liveCount is the tile's own integrity claim: a mismatch against the scanned
        // slots means the stream is unreliable — stop, keep what loaded.
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(2);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1);
            out.writeLong(0L);   // region key
            out.writeInt(7);     // claims 7 live...
            for (int s = 0; s < 1024; s++) out.writeInt(s == 3 ? 55 : 0); // ...has 1
        }
        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size(), "the lying tile is not admitted");
    }

    @Test
    void v2LiveCountOutOfRangeDiscardsTheRest(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(2);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1);
            out.writeLong(0L);
            out.writeInt(4096); // outside 1..1024
            for (int s = 0; s < 1024; s++) out.writeInt(0);
        }
        var loaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size());
    }

    /** TS_EPOCH_SECONDS is a PERSISTED-FORMAT anchor: every v2 file's slots are offsets
     *  from it, so an accidental edit re-times every stamp loaded from existing
     *  lss-timestamps.bin files — and a DOWNWARD drift reads old stamps as newer,
     *  the false-up_to_date direction §2.2 forbids. Every other expectation in this
     *  suite derives from the constant, so only a literal pins it (review L3-10). */
    @Test
    void tsEpochAnchorIsLiterallyPinned() {
        assertEquals(1_735_689_600L, ColumnTimestampCache.TS_EPOCH_SECONDS,
                "2025-01-01T00:00:00Z — changing this re-times every persisted stamp");
    }

    /** The design-§5 constants coherence pin (WantSetBudgetInvariantTest-style): AUTO
     *  sizing budgets {@code TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN} per column, and that
     *  claim is honest only while a full tile's real cost per column stays at or under
     *  it — the drift guard the retired per-entry constant's comment claimed to have. */
    @Test
    void autoSizingPerColumnConstantCoversTheRealTileCost() {
        assertTrue(ColumnTimestampCache.TILE_HEAP_BYTES / 1024.0
                        <= LSSConstants.TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN,
                "TILE_HEAP_BYTES/1024 columns must not exceed the AUTO per-column budget — "
                        + "re-derive TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN if the Tile grows");
    }

    /** The no-empty-tile invariant at every decrement site (design §2/§8): a tile whose
     *  last slot clears must be REMOVED, whichever path cleared it — a leaked empty tile
     *  costs eviction budget forever, and a persisted one is a liveCount-0 record the v2
     *  loader rejects. Observable without internals: save() persists only LIVE tiles, so
     *  an all-emptied cache must save a file that loads back EMPTY (a leaked empty tile
     *  would write a liveCount-0 record and the load would warn-discard). The file must
     *  still be WRITTEN — an emptied cache that skipped its save would leave stale
     *  stamps on disk to resurrect invalidated positions on the next boot. */
    @Test
    void emptyTilesDieAtAllThreeDecrementSitesAndNeverPersist(@TempDir Path tempDir) {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 10L, ts(1L), now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 10L, 0L, now);          // site 1: put ts<=0
        cache.put(LSSConstants.DIM_STR_THE_NETHER, 11L, ts(2L), now);
        cache.invalidate(LSSConstants.DIM_STR_THE_NETHER, new long[]{11L});       // site 2
        cache.put(LSSConstants.DIM_STR_THE_END, 12L, ts(3L), now);
        cache.invalidateStamps(LSSConstants.DIM_STR_THE_END, new long[]{12L});    // site 3
        assertEquals(0, cache.size());
        cache.save(tempDir);
        assertTrue(Files.exists(tempDir.resolve("lss-timestamps.bin")),
                "an emptied (not never-touched) cache must still save — a skipped save "
                        + "leaves stale stamps on disk to resurrect on the next boot");
        var emptied = new ColumnTimestampCache(DEFAULT_MAX, 0);
        emptied.load(tempDir);
        assertEquals(0, emptied.size(),
                "no tile persisted — a leaked empty tile would be a liveCount-0 record");

        cache.put(LSSConstants.DIM_STR_OVERWORLD, 20L, ts(9L), now);
        cache.save(tempDir);
        var reloaded = new ColumnTimestampCache(DEFAULT_MAX, 0);
        reloaded.load(tempDir);
        assertEquals(ts(9L), reloaded.get(LSSConstants.DIM_STR_OVERWORLD, 20L));
        assertEquals(1, reloaded.size(), "exactly the one live entry persists");
    }
}
