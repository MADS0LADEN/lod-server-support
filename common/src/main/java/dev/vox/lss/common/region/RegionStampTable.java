package dev.vox.lss.common.region;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Per-dimension region freshness stamps (region-summary-sync-plan.md §3/§5): for each
 * region file, the last observed mtime, a memoized copy of the region header's per-chunk
 * save-second table (the 8 KiB {@code readHeaderTimestamps} shape the store sweep
 * pioneered), and a {@code liveSaveMark} bumped by the dirty-mark choke point at save/edit
 * SUBMISSION time (closing the save-submitted-but-write-pending mtime lag).
 *
 * <p>P1 consumer: the disk reader's header freshness rung —
 * {@link #chunkStampSecondsOrUnknown} answers "content at this position last changed no
 * later than second S" so a ts&gt;0 resync whose client stamp is strictly newer can be
 * answered {@code up_to_date} without the region read. Every rung fails toward STALE
 * (serving): unresolvable dimension, missing/unreadable region file, absent chunk
 * (header location 0), and degenerate header seconds (non-positive, or beyond now + skew
 * allowance) all report {@link #NEVER_CLEAN} or {@link #UNKNOWN}, which no client stamp
 * can beat. The P2 summary sweeper shares this table ({@code maxHeaderSecond} +
 * {@code liveSaveMark} are its per-tile stamp).
 *
 * <p>Threading: read by the disk reader pool (stat + header IO happen there, never on
 * the processing thread), bumped by dirty-mark producers on arbitrary threads (Fabric's
 * save hook may run off-main under C2ME/Moonrise; Paper events arrive on region threads
 * under Folia). All state is concurrent; the per-entry refresh is synchronized so one
 * thread reads a changed header, not N.
 *
 * <p>Memory: the permanent per-region triple is ~48 B; the memoized {@code int[1024]}
 * header snapshots (4 KiB each) are bounded by {@link #MAX_HEADER_SNAPSHOTS} with FIFO
 * strip-and-relearn eviction (a stripped region costs one re-read on next demand).
 */
public final class RegionStampTable {

    /** No honest answer available — the caller must fall through to the real read. */
    public static final long UNKNOWN = -1L;
    /** The position can never be validated from header knowledge (absent chunk,
     *  degenerate stamp): reported as a stamp no client timestamp can exceed. */
    public static final long NEVER_CLEAN = Long.MAX_VALUE;

    /** How long a memoized header/stat is trusted before the file is re-statted. The
     *  liveSaveMark choke point covers marked edits instantly; this horizon bounds the
     *  staleness of everything else (Paper's unfired-event saves, external tools) at
     *  well under the dirty-broadcast interval it must stay comparable to. */
    private static final long STAT_HORIZON_NANOS = 5_000_000_000L;
    /** Header seconds beyond now + this are clock damage, not saves (adversarial A10:
     *  a u32 read as a negative int must never compare "older than every client"). */
    private static final long FUTURE_SKEW_ALLOWANCE_SECONDS = 3600;
    /** Retained header snapshots across all dimensions (4 KiB each — ~4 MB cap). */
    private static final int MAX_HEADER_SNAPSHOTS = 1024;

    private record HeaderSnapshot(long mtimeMillis, boolean mtimeSettled, long[] saveSeconds) {}

    /** File statted and absent — cached like a header so a rejoin storm over a
     *  never-generated area stats once per horizon, not once per ask. */
    private static final HeaderSnapshot ABSENT = new HeaderSnapshot(Long.MIN_VALUE, true, null);

    static final class RegionEntry {
        final AtomicLong liveSaveMarkSeconds = new AtomicLong();
        volatile HeaderSnapshot header;          // null = never read / stripped
        volatile long statDeadlineNanos = Long.MIN_VALUE;
        volatile long maxHeaderSecond;           // P2's per-tile stamp half (0 until read)
    }

    private final Function<String, Path> regionDirResolver;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, RegionEntry>> byDimension =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> unresolvableWarned = new ConcurrentHashMap<>();
    // FIFO of entries holding a retained header array; strip-and-relearn beyond the cap.
    private final ConcurrentLinkedQueue<RegionEntry> retainedHeaders = new ConcurrentLinkedQueue<>();
    private final AtomicInteger retainedHeaderCount = new AtomicInteger();

    public RegionStampTable(Function<String, Path> regionDirResolver) {
        this.regionDirResolver = regionDirResolver;
    }

    /**
     * The P1 header rung's question: the second at/before which this chunk's on-disk
     * content last changed, per the region header (max of the chunk's header save second
     * and the region's liveSaveMark), or {@link #UNKNOWN} when no honest claim exists.
     * A caller may answer {@code up_to_date} iff the returned stamp is {@code >= 0} and
     * STRICTLY below the client's stamp (same-second fails toward serving — the store
     * deposit's R1-M2 discipline). May perform one stat and one 8 KiB read; reader-pool
     * threads only.
     */
    public long chunkStampSecondsOrUnknown(String dimension, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        var entry = entryFor(dimension, PositionUtil.packRegionOf(packed));
        HeaderSnapshot h = refreshedHeader(dimension, entry, cx >> 5, cz >> 5);
        if (h == null) return UNKNOWN;
        if (h == ABSENT) {
            // Region file missing: the chunk is not on disk — the real read's
            // authoritative-miss ladder (generation) owns this, never a freshness claim.
            return UNKNOWN;
        }
        // The REGION HEADER's index layout — z-major, the store sweep's exact formula.
        // Deliberately NOT PositionUtil.tileSlotOf, which is x-major (the tscache tile
        // layout): transposing here reads the wrong chunk's stamp.
        long headerSecond = h.saveSeconds()[(cx & 31) + ((cz & 31) << 5)];
        if (headerSecond == NEVER_CLEAN) return NEVER_CLEAN;
        return Math.max(headerSecond, entry.liveSaveMarkSeconds.get());
    }

    /**
     * Record that content in this chunk's region changed no earlier than
     * {@code epochSeconds} (a hash-confirmed save on Fabric, a dirty-marking Bukkit
     * event on Paper — both strictly no later than the change reaching the region
     * file). Monotonic max; any thread. This is what closes the mtime-lag window: a
     * claim is blocked the moment the mark lands, before the write hits the header.
     */
    public void bumpLiveSaveMark(String dimension, int cx, int cz, long epochSeconds) {
        var entry = entryFor(dimension, PositionUtil.packRegionOf(PositionUtil.packPosition(cx, cz)));
        entry.liveSaveMarkSeconds.accumulateAndGet(epochSeconds, Math::max);
    }

    private RegionEntry entryFor(String dimension, long regionKey) {
        return this.byDimension
                .computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionKey, k -> new RegionEntry());
    }

    /** The entry's header, statted/re-read when the horizon lapsed. Null = no honest
     *  data (unresolvable dimension, unreadable header). */
    private HeaderSnapshot refreshedHeader(String dimension, RegionEntry entry, int rx, int rz) {
        long now = System.nanoTime();
        HeaderSnapshot h = entry.header;
        if (h != null && now - entry.statDeadlineNanos < 0) return h;
        synchronized (entry) {
            h = entry.header;
            now = System.nanoTime();
            if (h != null && now - entry.statDeadlineNanos < 0) return h;
            Path dir;
            try {
                dir = this.regionDirResolver == null ? null : this.regionDirResolver.apply(dimension);
            } catch (Throwable t) {
                dir = null;
            }
            if (dir == null) {
                if (this.unresolvableWarned.putIfAbsent(dimension, Boolean.TRUE) == null) {
                    LSSLogger.info("Region freshness unavailable for dimension " + dimension
                            + " (region directory unresolved) — resyncs there fall through to"
                            + " full reads (logged once)");
                }
                entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
                return null;
            }
            Path mca = dir.resolve("r." + rx + "." + rz + ".mca");
            long mtime;
            try {
                mtime = Files.getLastModifiedTime(mca).toMillis();
            } catch (Exception e) {
                // Missing (or unstattable) region file — cache the absence for a horizon.
                entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
                setHeader(entry, ABSENT, h);
                return ABSENT;
            }
            if (h != null && h != ABSENT && h.mtimeSettled() && h.mtimeMillis() == mtime) {
                // Unchanged since the last examined read: keep the memo, push the horizon.
                entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
                return h;
            }
            long[] seconds = readNormalizedHeader(mca);
            entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
            if (seconds == null) {
                // Unreadable header: no honest claim; retry after the horizon.
                setHeader(entry, null, h);
                return null;
            }
            long maxSecond = 0;
            for (long s : seconds) {
                if (s != NEVER_CLEAN && s > maxSecond) maxSecond = s;
            }
            entry.maxHeaderSecond = Math.max(entry.maxHeaderSecond, maxSecond);
            // The store sweep's raced-mtime discipline (its R1 review): only trust an
            // mtime as "this header was examined at this stamp" when a post-read re-stat
            // matches AND the stamp's second is strictly past — on 1 s-granularity
            // filesystems an in-second save produces an EQUAL mtime the == compare would
            // then skip forever. An unsettled mtime just re-reads next horizon.
            boolean settled = false;
            try {
                settled = Files.getLastModifiedTime(mca).toMillis() == mtime
                        && mtime / 1000L < System.currentTimeMillis() / 1000L;
            } catch (Exception ignored) {
            }
            var fresh = new HeaderSnapshot(mtime, settled, seconds);
            setHeader(entry, fresh, h);
            return fresh;
        }
    }

    /** Swap the entry's header, maintaining the retained-array bound. */
    private void setHeader(RegionEntry entry, HeaderSnapshot fresh, HeaderSnapshot old) {
        entry.header = fresh;
        boolean hadArray = old != null && old.saveSeconds() != null;
        boolean hasArray = fresh != null && fresh.saveSeconds() != null;
        if (hasArray && !hadArray) {
            this.retainedHeaders.add(entry);
            if (this.retainedHeaderCount.incrementAndGet() > MAX_HEADER_SNAPSHOTS) {
                var victim = this.retainedHeaders.poll();
                if (victim != null) {
                    this.retainedHeaderCount.decrementAndGet();
                    // Strip-and-relearn: dropping the header alone would let the next
                    // stat see an unchanged mtime and trust a memo that no longer
                    // exists, so the whole snapshot goes (one 8 KiB re-read on demand).
                    // The victim can be THIS entry under churn — harmless, same relearn.
                    // Accepted race: a victim mid-refresh on its own monitor can
                    // overwrite this null with a fresh array it believes is still
                    // tracked — one untracked 4 KiB snapshot per occurrence, bounded by
                    // strip frequency, never a correctness issue (both are honest).
                    victim.header = null;
                    victim.statDeadlineNanos = Long.MIN_VALUE;
                }
            }
        } else if (hadArray && !hasArray) {
            // Replaced by null/ABSENT. Decrement only on a successful remove: a
            // concurrent strip may have polled this entry already (and decremented),
            // and a blind second decrement would loosen the cap permanently.
            if (this.retainedHeaders.remove(entry)) {
                this.retainedHeaderCount.decrementAndGet();
            }
        }
    }

    /**
     * The store sweep's header shape ({@code SqliteLodStore.readHeaderTimestamps}),
     * normalized for freshness claims: location 0 (chunk absent from a present region)
     * and degenerate seconds (non-positive, or implausibly far in the future — a u32
     * read as a negative int, tool-damaged headers) become {@link #NEVER_CLEAN}, so the
     * compare can only ever fail toward serving. Null = unreadable.
     */
    private static long[] readNormalizedHeader(Path mca) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long ceiling = nowSec + FUTURE_SKEW_ALLOWANCE_SECONDS;
        try (FileChannel ch = FileChannel.open(mca)) {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int read = 0;
            while (read < 8192) {
                int n = ch.read(buf, read);
                if (n < 0) return null;
                read += n;
            }
            long[] stamps = new long[1024];
            for (int i = 0; i < 1024; i++) {
                int loc = buf.getInt(i * 4);
                if (loc == 0) {
                    stamps[i] = NEVER_CLEAN;
                    continue;
                }
                long sec = buf.getInt(4096 + i * 4) & 0xFFFF_FFFFL;
                stamps[i] = (sec <= 0 || sec > ceiling) ? NEVER_CLEAN : sec;
            }
            return stamps;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- test seams ----

    int retainedHeaderCountForTest() {
        return this.retainedHeaderCount.get();
    }

    long liveSaveMarkForTest(String dimension, int cx, int cz) {
        var dims = this.byDimension.get(dimension);
        if (dims == null) return 0;
        var entry = dims.get(PositionUtil.packRegionOf(PositionUtil.packPosition(cx, cz)));
        return entry == null ? 0 : entry.liveSaveMarkSeconds.get();
    }

    /** Collapse the stat horizon so tests observe mtime-driven re-reads immediately.
     *  Sets the deadline to "now" (nanoTime deltas overflow against MIN_VALUE). */
    void expireStatHorizonForTest(String dimension, int cx, int cz) {
        var dims = this.byDimension.get(dimension);
        if (dims == null) return;
        var entry = dims.get(PositionUtil.packRegionOf(PositionUtil.packPosition(cx, cz)));
        if (entry != null) entry.statDeadlineNanos = System.nanoTime();
    }
}
