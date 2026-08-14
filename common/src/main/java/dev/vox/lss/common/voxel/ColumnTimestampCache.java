package dev.vox.lss.common.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-dimension timestamp cache for served columns, stored as per-region FLAT TILES
 * (docs/planning/timestamp-cache-tile-redesign.md, D0): one {@code int[1024]} of
 * u32 epoch-second offsets per 32×32-chunk region instead of two parallel hash maps
 * per column — ~13x less live heap per column on the dense discs the spiral scan,
 * backfill, and resyncs produce by construction. Used for up-to-date checks without
 * disk IO.
 *
 * <p><b>Correctness frame (design §2.2, do not relax):</b> the router's rung is
 * {@code up_to_date iff cachedTs > 0 && cachedTs <= clientTimestamp}. OVERSTATING a
 * stored stamp only forces a redundant re-serve (safe); UNDERSTATING can answer
 * {@code up_to_date} against a stale claim (the delivery-honesty violation). Every
 * clamp here rounds UP or refuses to fabricate: {@code ts == TS_EPOCH_SECONDS}
 * collides with the absent sentinel and stores offset 1 (+1 s overstate);
 * pre-epoch stamps (skewed server clock) clamp to 1 — still safe end-to-end because
 * client claims are stamps the same skewed clock issued, so {@code epoch+1 <= claim}
 * fails and the column re-serves; {@code ts <= 0} CLEARS the slot (storing any
 * positive offset would fabricate a claim — the 0-stamp ghost history forbids it);
 * offsets past {@code 0xFFFFFFFF} (year ~2161) clamp down, documented unreachable.
 * For every in-range stamp the u32 offset is LOSSLESS — router semantics are
 * bit-identical to the map implementation this replaced.
 *
 * <p>Persistent: saved to disk periodically and on shutdown (format v2; v1 files
 * migrate in place at load), loaded on startup.
 *
 * <p>Single-threaded — must only be called from the processing thread.
 */
public class ColumnTimestampCache {

    private static final int FORMAT_VERSION = 2;
    private static final String FILE_NAME = "lss-timestamps.bin";
    private static final dev.vox.lss.common.LogThrottle SAVE_FAIL_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    /** 2025-01-01T00:00:00Z — every legitimate LSS stamp postdates it (design §2).
     *  Offsets are u32 seconds from here; 0 is the absent sentinel, so the epoch
     *  itself stores as 1 (+1 s overstate, the safe direction). Exhausts in ~2161. */
    public static final long TS_EPOCH_SECONDS = 1_735_689_600L;
    /** v1 on-disk record size (8B packed position + 8B stamp) — the migration loader's
     *  sanity bound. */
    private static final int V1_DISK_BYTES_PER_ENTRY = 16;
    /** v2 on-disk tile record: 8B regionKey + 4B liveCount + 1024×4B slots. */
    private static final int TILE_RECORD_BYTES = 8 + 4 + 4096;
    /** save()/load() stream buffer — see the buffering note in save(). */
    private static final int IO_BUFFER_BYTES = 1 << 16;
    /** Approximate LIVE heap per tile: 4096 B slot array + ~16 B array header + ~32 B
     *  Tile object + ~29 B map slot at 0.75 load factor ≈ 4.2 KB; 4352 includes slack.
     *  Replaces the per-entry model (64 B × two hash maps) — a dense tile costs
     *  ~4.25 B/column, and the byte cap binds on tiles, not entries. */
    public static final int TILE_HEAP_BYTES = 4352;

    /** One region's stamps: u32 epoch-offsets (0 = absent), a live count so size
     *  queries never scan, and the last-put age the tile-granular eviction ranks by. */
    private static final class Tile {
        final int[] slots = new int[1024];
        int liveCount;
        long lastTouchEpochSeconds;
    }

    private final Map<String, Long2ObjectOpenHashMap<Tile>> caches = new HashMap<>();
    private final long maxBytesPerDimension;

    // ---- Miss memo (docs/planning/miss-memo-design.md) ----
    // packedXZ -> expiry deadline (monotonic nanos), per dimension: "this chunk was
    // AUTHORITATIVELY absent on disk as of <deadline - ttl>". Deliberately a SIBLING
    // structure, never a sentinel in the timestamp value space (the 0-stamp ghost history
    // forbids overloading it). TTL-bounded because the falsifying hooks are known
    // incomplete (Paper walk-in generation unmarked by default, chunk-IO overhauls may
    // bypass the save hook): staleness must cost seconds of delay, never a session.
    // Excluded from save()/load()/snapshotForSave() — seconds-fresh info must not survive
    // a process boundary. ttl 0 disables the memo wholesale (config kill switch).
    // UNTOUCHED by the tile redesign (design §4) beyond the overflow cap's derivation,
    // which keeps the old numeric value (bytes/64 == the retired mbToEntries result).
    private final Map<String, Long2LongOpenHashMap> missExpiryByPosition = new HashMap<>();
    private final long missTtlNanos;
    private final int missMemoOverflowCap;

    // Cross-thread observability for the soak/benchmark exporters (the cache itself is
    // processing-thread-only): liveSizes mirrors each dimension's current entry count
    // after every mutation, evictionCount accumulates evictIfOversized removals.
    private final Map<String, Integer> liveSizes = new ConcurrentHashMap<>();
    private final AtomicLong evictionCount = new AtomicLong();

    // §2.2 visibility latch: the pre-epoch clamp permanently costs affected columns
    // their up_to_date resolution — accepted, but never silently. Processing-thread
    // written like all cache state.
    private boolean preEpochWarned;

    public ColumnTimestampCache(long maxBytesPerDimension, long missTtlNanos) {
        this.maxBytesPerDimension = Math.max(TILE_HEAP_BYTES, maxBytesPerDimension);
        this.missTtlNanos = Math.max(0, missTtlNanos);
        this.missMemoOverflowCap = (int) Math.min(Integer.MAX_VALUE,
                this.maxBytesPerDimension / 64);
    }

    // ---- stamp <-> slot conversion (design §2.2 — every clamp rounds UP) ----

    private static int offsetOf(long timestamp) {
        long off = timestamp - TS_EPOCH_SECONDS;
        if (off < 1) return 1;                    // epoch collision + pre-epoch clamp UP
        if (off > 0xFFFF_FFFFL) return -1;        // 0xFFFFFFFF as int — year ~2161 clamp
        return (int) off;
    }

    private static long stampOf(int slot) {
        return slot == 0 ? 0 : TS_EPOCH_SECONDS + (slot & 0xFFFF_FFFFL);
    }

    public void put(String dimension, long packed, long timestamp, long now) {
        long region = PositionUtil.packRegionOf(packed);
        int slot = PositionUtil.tileSlotOf(packed);
        if (timestamp <= 0) {
            // Never store a fabricated claim (design §2.2): a non-positive stamp CLEARS.
            // Router-equivalent to the old map (which stored it and get() reported it,
            // but the router requires cachedTs > 0 so it always read as absent).
            // get(), not computeIfAbsent: a clear must not mint a dimension map (it
            // would flip save()'s never-touched guard for a cache that holds nothing).
            var tiles = caches.get(dimension);
            Tile tile = tiles == null ? null : tiles.get(region);
            if (tile != null && tile.slots[slot] != 0) {
                tile.slots[slot] = 0;
                if (--tile.liveCount == 0) tiles.remove(region);
                bumpLiveSize(dimension, -1);
            }
        } else {
            if (timestamp <= TS_EPOCH_SECONDS && !preEpochWarned) {
                // §2.2: the pre-epoch clamp permanently costs those columns their
                // up_to_date resolution (the wire still carries the unclamped stamp, so
                // equality never holds) — an accepted regression, but it must be VISIBLE.
                preEpochWarned = true;
                LSSLogger.warn("Timestamp " + timestamp + " predates 2025-01-01 (skewed "
                        + "system clock, or pre-2025 world files) — clamping; affected "
                        + "columns re-serve instead of resolving up_to_date. "
                        + "Warned once per session.");
            }
            var tiles = caches.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
            Tile tile = tiles.get(region);
            if (tile == null) {
                tile = new Tile();
                tiles.put(region, tile);
            }
            if (tile.slots[slot] == 0) {
                tile.liveCount++;
                bumpLiveSize(dimension, 1);
            }
            tile.slots[slot] = offsetOf(timestamp);
            tile.lastTouchEpochSeconds = now;
        }
        // A positive stamp IS the miss-clear choke point: every serve path (probe, disk
        // success, generation delivery, resync) lands here, so a served column can never
        // stay memoized absent.
        clearMiss(dimension, packed);
    }

    /** Latched by the first pre-epoch clamp (§2.2's visibility requirement). */
    public boolean preEpochWarnedForTest() {
        return preEpochWarned;
    }

    private void bumpLiveSize(String dimension, int delta) {
        liveSizes.merge(dimension, delta, Integer::sum);
    }

    // ---- Miss memo API (processing thread only, like everything else here) ----

    /** Record an AUTHORITATIVE disk miss (storage answered "no such chunk" — never an
     *  error/timeout triage). No-op when the memo is disabled (ttl 0). */
    public void putMiss(String dimension, long packed, long nowNanos) {
        if (this.missTtlNanos <= 0) return;
        missExpiryByPosition.computeIfAbsent(dimension, k -> new Long2LongOpenHashMap())
                .put(packed, nowNanos + this.missTtlNanos);
    }

    /** True while a recorded miss is still fresh; lazily removes an expired entry.
     *  Overflow-safe deadline comparison (nanoTime wraps). */
    public boolean isFreshMiss(String dimension, long packed, long nowNanos) {
        if (this.missTtlNanos <= 0) return false;
        var misses = missExpiryByPosition.get(dimension);
        if (misses == null || !misses.containsKey(packed)) return false;
        if (misses.get(packed) - nowNanos > 0) return true;
        misses.remove(packed);
        return false;
    }

    /** Drop the memo entry for one position (generation outcome drained, serve, etc.). */
    public void clearMiss(String dimension, long packed) {
        var misses = missExpiryByPosition.get(dimension);
        if (misses != null) misses.remove(packed);
    }

    /** Total memo entries across all dimensions, including not-yet-lazily-expired ones.
     *  Test seam only — no production consumer: the diag line reports the volatile
     *  memo_hits counter instead, deliberately avoiding cross-thread map iteration. */
    public int missCount() {
        int total = 0;
        for (var misses : missExpiryByPosition.values()) total += misses.size();
        return total;
    }

    /**
     * Returns the stored timestamp, or 0 if absent. Lossless for every in-range stamp:
     * {@code get} after {@code put} returns the exact same second.
     */
    public long get(String dimension, long packed) {
        var tiles = caches.get(dimension);
        if (tiles == null) return 0;
        Tile tile = tiles.get(PositionUtil.packRegionOf(packed));
        if (tile == null) return 0;
        return stampOf(tile.slots[PositionUtil.tileSlotOf(packed)]);
    }

    /** Removes cached timestamps for the given positions. Returns the count actually removed.
     *  Also drops any miss-memo entries: an EDIT invalidation means a save happened, so the
     *  chunk exists and a memoized absence is falsified. The disk-drain's not-found stamp
     *  guard must use {@link #invalidateStamps} instead — that path's observation is the
     *  same one that just WROTE the memo, and clearing it here would erase every memo write. */
    public int invalidate(String dimension, long[] positions) {
        var misses = missExpiryByPosition.get(dimension);
        if (misses != null) {
            for (long pos : positions) misses.remove(pos);
        }
        return invalidateStamps(dimension, positions);
    }

    /** Stamp-only invalidation (ghost-terrain guard on a not-found delivery): removes cached
     *  timestamps but PRESERVES miss-memo entries — a not-found is the memo's own evidence.
     *  A tile whose live count reaches 0 is removed (dimension-trip sweeps must actually
     *  free memory — design §4). */
    public int invalidateStamps(String dimension, long[] positions) {
        var tiles = caches.get(dimension);
        if (tiles == null) return 0;
        int removed = 0;
        for (long pos : positions) {
            Tile tile = tiles.get(PositionUtil.packRegionOf(pos));
            if (tile == null) continue;
            int slot = PositionUtil.tileSlotOf(pos);
            if (tile.slots[slot] != 0) {
                tile.slots[slot] = 0;
                removed++;
                if (--tile.liveCount == 0) tiles.remove(PositionUtil.packRegionOf(pos));
            }
        }
        if (removed > 0) bumpLiveSize(dimension, -removed);
        return removed;
    }

    /**
     * Evicts the oldest-touched TILES when a dimension exceeds the byte budget
     * (design §5 — {@code tileCount * TILE_HEAP_BYTES} vs the configured bytes).
     * Granularity is a tile (up to 1024 columns); {@code lastTouch} is the most recent
     * put, so any region with recent activity is protected. Returns the total number
     * of column entries evicted across all dimensions (the counter keeps its meaning).
     */
    public int evictIfOversized() {
        // Miss-memo maps evict FIRST and wholesale when over-cap: a miss is always cheaper
        // to lose than a stamp (losing one costs a redundant not-found read; losing a stamp
        // costs a redundant serve), and the memo repopulates within one declaration cycle.
        for (var misses : missExpiryByPosition.values()) {
            if (misses.size() > this.missMemoOverflowCap) misses.clear();
        }
        int evicted = 0;
        for (var entry : caches.entrySet()) {
            var tiles = entry.getValue();
            long budgetTiles = this.maxBytesPerDimension / TILE_HEAP_BYTES;
            long excess = tiles.size() - budgetTiles;
            if (excess <= 0) continue;
            // ONE sorted pass, oldest lastTouch first — never a rescan per victim. A
            // per-victim linear scan is O(tiles²) exactly where eviction runs in bulk
            // (load() deliberately admits an over-budget file, so a config shrink from
            // distance 1024 to 256 evicts ~38k tiles on the processing thread) — the
            // multi-second freeze the deleted per-entry evictOldest was rewritten for.
            // Snapshot to plain values first: fastutil entries are LIVE VIEWS whose
            // getValue() nulls once their key is removed.
            record Victim(long key, int liveCount, long lastTouch) {}
            var victims = new ArrayList<Victim>(tiles.size());
            for (var e : tiles.long2ObjectEntrySet()) {
                victims.add(new Victim(e.getLongKey(),
                        e.getValue().liveCount, e.getValue().lastTouchEpochSeconds));
            }
            victims.sort(Comparator.comparingLong(Victim::lastTouch));
            int dimEvicted = 0;
            for (int i = 0; i < excess; i++) {
                tiles.remove(victims.get(i).key());
                dimEvicted += victims.get(i).liveCount();
            }
            if (dimEvicted > 0) {
                evicted += dimEvicted;
                bumpLiveSize(entry.getKey(), -dimEvicted);
            }
        }
        if (evicted > 0) evictionCount.addAndGet(evicted);
        return evicted;
    }

    /** Total entries across all dimensions. Sums per-tile live counts — a scan over the
     *  tile maps, so confined callers only (service ctor pre-start, the save executor's
     *  debug line over a snapshot); cross-thread readers use {@link #sizesPerDimension}. */
    public int size() {
        int total = 0;
        for (var tiles : caches.values()) {
            for (Tile tile : tiles.values()) total += tile.liveCount;
        }
        return total;
    }

    /**
     * Cumulative entries removed by {@link #evictIfOversized()} over this instance's
     * lifetime. Safe to read from any thread (exporter observability).
     */
    public long getEvictionCount() {
        return evictionCount.get();
    }

    /**
     * Current entry count per dimension, as of the last mutation. Safe to read from any
     * thread (exporter observability) — the returned map is an immutable copy.
     */
    public Map<String, Integer> sizesPerDimension() {
        return Map.copyOf(liveSizes);
    }

    // ---- Persistence ----

    /**
     * Saves the cache to {@code <dataDir>/lss-timestamps.bin} using atomic write.
     * v2 format: version (int) + dimensionCount (int) + per-dimension: key (UTF) +
     * tileCount (int) + per tile: regionKey (long) + liveCount (int) + 1024 slots (int).
     */
    public void save(Path dataDir) {
        // NEVER-TOUCHED (no dimension ever cached) skips the save — an idle server
        // creates no file. An EMPTIED cache must still write: a stale file left on disk
        // would resurrect invalidated stamps on the next boot (the debounced-save pin's
        // crash-window poison). Empty DIMENSION maps (lingering after their last tile
        // was removed) are skipped from the body — only live tiles are ever persisted.
        if (caches.isEmpty()) return;
        int liveDims = 0;
        for (var tiles : caches.values()) {
            if (liveTileCount(tiles) > 0) liveDims++;
        }

        var file = dataDir.resolve(FILE_NAME);
        // Unique tmp name: two writers overlapping across a Paper /reload (old instance's
        // final save vs new instance's periodic save) must not interleave into one tmp file
        // and publish a torn cache.
        var tmpFile = file.resolveSibling(FILE_NAME + ".tmp." + Long.toHexString(System.nanoTime()));
        try {
            Files.createDirectories(dataDir);
            // Buffered: an unbuffered DataOutputStream turns every writeInt into a
            // 4-byte write syscall — the save-duration half of issue #62.
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(tmpFile), IO_BUFFER_BYTES))) {
                out.writeInt(FORMAT_VERSION);
                out.writeInt(liveDims);
                for (var entry : caches.entrySet()) {
                    var tiles = entry.getValue();
                    int liveTiles = liveTileCount(tiles);
                    if (liveTiles == 0) continue;
                    out.writeUTF(entry.getKey());
                    out.writeInt(liveTiles);
                    for (var e : tiles.long2ObjectEntrySet()) {
                        Tile tile = e.getValue();
                        // Writer-side belt (§2): the decrement sites remove empty tiles,
                        // but a liveCount-0 record slipping through would make the
                        // loader's reject branch discard the REST OF THE FILE — one
                        // leaked tile must never cost every later dimension's warm boot.
                        if (tile.liveCount == 0) continue;
                        out.writeLong(e.getLongKey());
                        out.writeInt(tile.liveCount);
                        for (int slot : tile.slots) out.writeInt(slot);
                    }
                }
            }
            try {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystems without atomic rename (some network mounts — same fallback as
                // JsonConfig.save): a torn file on crash is tolerable, the loader discards it.
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            // debug, not info: besides the ~5-min periodic save, the invalidation debounce
            // saves within ~2s of any dirty-broadcast — on a busy server that is a console
            // line every few seconds (#32). Failures below still WARN.
            if (LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Saved " + size() + " timestamp cache entries to " + file);
            }
        } catch (IOException e) {
            // Throttled (log-sweep top finding): the invalidation debounce re-saves ~2 s
            // after any dirty broadcast, so on a full/read-only disk this warned (with
            // stack) every few seconds forever — the same cadence that demoted the
            // SUCCESS line to debug above.
            long n = SAVE_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.warn("Failed to save timestamp cache to " + file
                        + " (" + n + " failed save(s) since the last report)", e);
            }
            try { Files.deleteIfExists(tmpFile); } catch (IOException e2) {
                // covered by the aggregate above; the tmp file is retried next save
            }
        }
    }

    /** Tiles with at least one live slot — what save() actually persists. */
    private static int liveTileCount(Long2ObjectOpenHashMap<Tile> tiles) {
        int n = 0;
        for (Tile tile : tiles.values()) {
            if (tile.liveCount > 0) n++;
        }
        return n;
    }

    /** Orphaned-tmp sweep age floor (tests stage older/fresher mtimes directly). */
    private static final long ORPHAN_TMP_MAX_AGE_MILLIS = 60 * 60 * 1000L;

    /**
     * Deletes stale save tmp files ({@code lss-timestamps.bin.tmp.<hex>}). The unique tmp
     * names protect the /reload writer-overlap window, but they lose the fixed-name
     * writers' self-overwrite property: a process killed mid-write (kill -9, OOM-kill,
     * power loss) orphans its multi-MB tmp forever — the IOException cleanup never ran and
     * no later save reuses the name. The age guard keeps the /reload overlap safe: an old
     * instance's final save may still be writing its FRESH tmp while the new instance
     * loads, and an in-progress write keeps refreshing its mtime.
     */
    private static void sweepOrphanedTempFiles(Path dataDir) {
        if (!Files.isDirectory(dataDir)) return;
        long cutoffMillis = System.currentTimeMillis() - ORPHAN_TMP_MAX_AGE_MILLIS;
        // ".tmp*" (not ".tmp.*"): also catches a legacy fixed-name "lss-timestamps.bin.tmp"
        // orphan from pre-unique-name versions; the main file has no ".tmp" and never matches.
        try (var stream = Files.newDirectoryStream(dataDir, FILE_NAME + ".tmp*")) {
            for (Path tmp : stream) {
                try {
                    if (Files.getLastModifiedTime(tmp).toMillis() < cutoffMillis) {
                        Files.deleteIfExists(tmp);
                        LSSLogger.info("Deleted orphaned timestamp cache temp file " + tmp);
                    }
                } catch (IOException e) {
                    LSSLogger.warn("Failed to sweep timestamp cache temp file " + tmp, e);
                }
            }
        } catch (IOException e) {
            LSSLogger.warn("Failed to sweep timestamp cache temp files in " + dataDir, e);
        }
    }

    /**
     * Loads the cache from {@code <dataDir>/lss-timestamps.bin}. Existing entries are
     * preserved (loaded entries are added/overwritten — the additive-merge property is
     * test-pinned). v1 files (the released per-entry format) migrate in place at load
     * (design §6): each record buckets into its tile through the §2.2 clamp rules and
     * every loaded tile's lastTouch resets to load time — exactly the old behavior of
     * resetting insertionTimes. The v1 file is never rewritten; the next save writes v2.
     */
    public void load(Path dataDir) {
        // Before the missing-file early return: orphans next to a missing/corrupt main
        // file must still be swept.
        sweepOrphanedTempFiles(dataDir);
        var file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;

        long now = LSSConstants.epochSeconds();
        long fileSize;
        try {
            fileSize = Files.size(file);
        } catch (IOException e) {
            LSSLogger.warn("Failed to stat timestamp cache " + file, e);
            return;
        }

        try (var in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file), IO_BUFFER_BYTES))) {
            int version = in.readInt();
            if (version == 1) {
                loadV1(in, file, fileSize, now);
                return;
            }
            if (version != FORMAT_VERSION) {
                // Includes the DOWNGRADE path read the other way: an old build reading a
                // v2 file warns + starts cold (the cache is an optimization, a cold
                // start self-heals via re-serves) — same policy here for unknowns.
                LSSLogger.warn("Timestamp cache " + file + " has unsupported version " + version + ", discarding");
                return;
            }
            // Sanity-bound the declared tile counts by what the file can physically hold,
            // mirroring the v1 loader's defense; a liveCount outside 1..1024 or
            // disagreeing with the scanned slots aborts the rest ("discarding rest").
            // The +1 slop is DELIBERATE (review-verified): header/dimension-name bytes
            // mean a truncated file can legitimately declare N tiles while
            // fileSize/TILE_RECORD_BYTES reads N-1 — an exact bound would discard the
            // whole dimension at this rung instead of letting the tile loop keep every
            // COMPLETE tile and stop at the EOF. The bound only exists to reject absurd
            // counts before allocation.
            long maxPlausibleTiles = fileSize / TILE_RECORD_BYTES + 1;
            int dimCount = in.readInt();
            int totalLoaded = 0;
            for (int d = 0; d < dimCount; d++) {
                String dimension = in.readUTF();
                int tileCount = in.readInt();
                if (tileCount < 0 || tileCount > maxPlausibleTiles) {
                    LSSLogger.warn("Timestamp cache " + file + " has invalid tile count " + tileCount
                            + " for dimension " + dimension + ", discarding rest");
                    return;
                }
                var tiles = caches.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
                for (int t = 0; t < tileCount; t++) {
                    long regionKey = in.readLong();
                    int liveCount = in.readInt();
                    if (liveCount < 1 || liveCount > 1024) {
                        LSSLogger.warn("Timestamp cache " + file + " has invalid tile liveCount "
                                + liveCount + ", discarding rest");
                        return;
                    }
                    Tile tile = new Tile();
                    int nonzero = 0;
                    for (int s = 0; s < 1024; s++) {
                        tile.slots[s] = in.readInt();
                        if (tile.slots[s] != 0) nonzero++;
                    }
                    if (nonzero != liveCount) {
                        LSSLogger.warn("Timestamp cache " + file + " tile liveCount " + liveCount
                                + " disagrees with " + nonzero + " nonzero slots, discarding rest");
                        return;
                    }
                    tile.liveCount = liveCount;
                    tile.lastTouchEpochSeconds = now;
                    // Additive merge at SLOT granularity (the documented load contract:
                    // loaded entries add/overwrite, in-memory-only entries survive) — a
                    // whole-tile put would silently delete live stamps sharing a region
                    // with any file tile.
                    Tile previous = tiles.get(regionKey);
                    if (previous != null) {
                        for (int s = 0; s < 1024; s++) {
                            if (tile.slots[s] == 0 && previous.slots[s] != 0) {
                                tile.slots[s] = previous.slots[s];
                                tile.liveCount++;
                            }
                        }
                    }
                    tiles.put(regionKey, tile);
                    // Mirror bump PER TILE, inside the loop: a mid-tile EOF throws to the
                    // outer catch, and a batched end-of-dimension bump would leave every
                    // already-inserted tile invisible to liveSizes — the next invalidation
                    // then drives the exporter's per-dimension gauge negative for the rest
                    // of the session.
                    int delta = tile.liveCount - (previous == null ? 0 : previous.liveCount);
                    if (delta != 0) bumpLiveSize(dimension, delta);
                    totalLoaded += liveCount;
                }
            }
            LSSLogger.info("Loaded " + totalLoaded + " timestamp cache entries from " + file);
        } catch (IOException e) {
            LSSLogger.warn("Failed to load timestamp cache from " + file, e);
        }
    }

    /** The v1 (per-entry) migration loader — design §6. Streams the old records into
     *  tiles via put()'s clamp rules; one INFO line reports the migration. */
    private void loadV1(DataInputStream in, Path file, long fileSize, long now) throws IOException {
        long maxPlausibleEntries = fileSize / V1_DISK_BYTES_PER_ENTRY;
        int dimCount = in.readInt();
        int totalLoaded = 0;
        for (int d = 0; d < dimCount; d++) {
            String dimension = in.readUTF();
            int entryCount = in.readInt();
            if (entryCount < 0 || entryCount > maxPlausibleEntries) {
                LSSLogger.warn("Timestamp cache " + file + " has invalid entry count " + entryCount
                        + " for dimension " + dimension + ", discarding rest");
                return;
            }
            for (int i = 0; i < entryCount; i++) {
                long packed = in.readLong();
                long timestamp = in.readLong();
                // §6 rule (1): a non-positive v1 record is SKIPPED entirely, never
                // streamed through put() — put's ts<=0 branch CLEARS, which on an
                // additive-merge load would delete a live in-memory stamp the file
                // knows nothing about. (The router read stored 0-stamps as absent, so
                // skipping loses nothing.) Positive records ride put()'s §2.2 clamps.
                if (timestamp <= 0) continue;
                put(dimension, packed, timestamp, now);
                totalLoaded++;
            }
        }
        int tileTotal = 0;
        for (var tiles : caches.values()) tileTotal += tiles.size();
        LSSLogger.info("Migrated timestamp cache v1 → v2 (" + totalLoaded + " entries → "
                + tileTotal + " tiles)");
    }

    /**
     * Creates a defensive copy of the timestamp data for async saving — per-tile
     * {@code int[].clone()}, ~13x cheaper than the old per-entry map copy (the
     * transient save-time memory spike shrinks by the same factor). Should only be
     * used for {@link #save}.
     */
    public ColumnTimestampCache snapshotForSave() {
        // Miss memo deliberately NOT copied (ttl 0): misses never persist across a save.
        var snapshot = new ColumnTimestampCache(this.maxBytesPerDimension, 0);
        for (var entry : caches.entrySet()) {
            var tilesCopy = new Long2ObjectOpenHashMap<Tile>(entry.getValue().size());
            for (var e : entry.getValue().long2ObjectEntrySet()) {
                Tile src = e.getValue();
                Tile copy = new Tile();
                System.arraycopy(src.slots, 0, copy.slots, 0, 1024);
                copy.liveCount = src.liveCount;
                copy.lastTouchEpochSeconds = src.lastTouchEpochSeconds;
                tilesCopy.put(e.getLongKey(), copy);
            }
            snapshot.caches.put(entry.getKey(), tilesCopy);
        }
        return snapshot;
    }
}
