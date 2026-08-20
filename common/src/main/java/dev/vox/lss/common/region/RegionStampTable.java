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
 * pioneered — {@code int[1024]}, z-major), and a {@code liveSaveMark} bumped by the
 * dirty-mark choke point at save/edit SUBMISSION time.
 *
 * <p>P1 consumer: the disk reader's header freshness rung —
 * {@link #chunkStampSecondsOrUnknown} answers "content at this position last changed no
 * later than second S" so a ts&gt;0 resync whose client stamp is strictly newer (plus the
 * caller's serve-latency margin) can be answered {@code up_to_date} without the region
 * read. Every rung fails toward STALE (serving): unresolvable dimension, missing or
 * unreadable region file, absent chunk (header location 0), and degenerate header
 * seconds all report {@link #NEVER_CLEAN} or {@link #UNKNOWN}, which no client stamp
 * can beat. ALL doubt states are horizon-cached sentinels — a corrupt file or an
 * unresolvable dimension costs one stat per {@code STAT_HORIZON}, never one per ask.
 *
 * <p><b>The mark LATCH</b> (P1 review MAJOR — the write-pending race): the mark is
 * stamped when a change is SUBMITTED (Fabric's copyOf hook, Paper's edit events), but
 * the change reaches the region header later. While
 * {@code liveSaveMark > maxHeaderSecond} — a marked change no examined header reflects
 * yet — the whole region answers {@link #NEVER_CLEAN}: comparing client stamps against
 * a mark TIME is unsound, because a read that raced the pending write hands out an
 * acquisition stamp NEWER than the mark while carrying pre-change bytes. The latch
 * self-clears when the write lands (mtime change → header re-read raises
 * {@code maxHeaderSecond} to at least the write second ≥ the mark second). Once
 * cleared, per-chunk header seconds answer alone — the mark never degrades the region's
 * other 1023 chunks permanently. Residual corner (an unrelated same-region save
 * clearing the latch while the marked chunk's own write is still queued): absorbed by
 * the caller's serve-latency margin, which every claim must clear anyway.
 *
 * <p>Threading: read by the disk reader pool (stat + header IO happen there, never on
 * the processing thread — and BEFORE the disk-read gate: a deliberate exemption, the
 * memoized cost is one stat per region per horizon), bumped by dirty-mark producers on
 * arbitrary threads (Fabric's save hook may run off-main under C2ME/Moonrise; Paper
 * events arrive on region threads under Folia). All state is concurrent; the per-entry
 * refresh is synchronized so one thread reads a changed header, not N — and every doubt
 * state is a cached sentinel, so the monitor is never a per-ask IO funnel.
 *
 * <p>Memory: the permanent per-region record is ~64 B; the memoized {@code int[1024]}
 * header snapshots (4 KiB each) are bounded by {@link #MAX_HEADER_SNAPSHOTS} (16 MB —
 * covers several dimensions' full discs at distance 512) with FIFO strip-and-relearn
 * eviction enforced OUTSIDE the owning monitors (one monitor held at a time — no
 * lock-order inversion; a stripped region costs one re-read on next demand).
 */
public final class RegionStampTable {

    /** No honest answer available — the caller must fall through to the real read. */
    public static final long UNKNOWN = -1L;
    /** The position can never be validated from header knowledge (absent chunk,
     *  degenerate stamp, latched pending write): reported as a stamp no client
     *  timestamp can exceed. */
    public static final long NEVER_CLEAN = Long.MAX_VALUE;

    /** How long a memoized header/stat/sentinel is trusted before the file is
     *  re-statted. The liveSaveMark latch covers marked edits instantly; this horizon
     *  bounds the staleness of everything else (Paper's unfired-event saves, external
     *  tools) at well under the dirty-broadcast interval it must stay comparable to. */
    private static final long STAT_HORIZON_NANOS = 5_000_000_000L;
    /** Header seconds beyond now + this are clock damage, not saves (adversarial A10:
     *  a u32 read as a negative int must never compare "older than every client"). */
    private static final long FUTURE_SKEW_ALLOWANCE_SECONDS = 3600;
    /** Retained header snapshots across all dimensions (4 KiB each — 16 MB cap,
     *  covering several dimensions' full region discs at distance 512). */
    static final int MAX_HEADER_SNAPSHOTS = 4096;
    /** In-array sentinel for "no honest per-chunk claim" (absent chunk, degenerate
     *  second) — the store sweep's Integer.MAX_VALUE discipline. */
    private static final int NEVER_SECOND = Integer.MAX_VALUE;

    /** Examined state of one region file. {@code saveSeconds == null} marks the two
     *  no-data sentinels ({@link #ABSENT}, {@link #UNREADABLE}) — BOTH horizon-cached,
     *  so doubt never becomes per-ask IO. */
    private record HeaderSnapshot(long mtimeMillis, boolean mtimeSettled, int[] saveSeconds) {}

    private static final HeaderSnapshot ABSENT = new HeaderSnapshot(Long.MIN_VALUE, true, null);
    private static final HeaderSnapshot UNREADABLE = new HeaderSnapshot(Long.MIN_VALUE, true, null);

    static final class RegionEntry {
        final AtomicLong liveSaveMarkSeconds = new AtomicLong();
        volatile HeaderSnapshot header;          // null = never examined / stripped
        volatile long statDeadlineNanos;         // consulted only while header != null
        volatile long maxHeaderSecond;           // monotonic; the latch's disk-visible bound
    }

    private final Function<String, Path> regionDirResolver;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, RegionEntry>> byDimension =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> unresolvableWarned = new ConcurrentHashMap<>();
    // FIFO of entries holding a retained header array; strip-and-relearn beyond the cap.
    // Mutated ONLY under the owning entry's monitor (setHeader) or in enforceCap, which
    // takes one victim monitor at a time with no other monitor held.
    private final ConcurrentLinkedQueue<RegionEntry> retainedHeaders = new ConcurrentLinkedQueue<>();
    private final AtomicInteger retainedHeaderCount = new AtomicInteger();

    public RegionStampTable(Function<String, Path> regionDirResolver) {
        this(regionDirResolver, MAX_HEADER_SNAPSHOTS);
    }

    /** Test seam: a small snapshot cap so the strip-and-relearn machinery is pinnable
     *  without thousands of region files. Production always uses the delegating ctor. */
    RegionStampTable(Function<String, Path> regionDirResolver, int maxHeaderSnapshots) {
        this.regionDirResolver = regionDirResolver;
        this.maxHeaderSnapshots = maxHeaderSnapshots;
    }

    private final int maxHeaderSnapshots;

    /**
     * The P1 header rung's question: the second at/before which this chunk's on-disk
     * content last changed, per the region header, or {@link #UNKNOWN} when no honest
     * claim exists, or {@link #NEVER_CLEAN} when the position can never be validated
     * (absent chunk, degenerate second, or the region is LATCHED behind a marked
     * change no examined header reflects yet). A caller may answer {@code up_to_date}
     * only when the returned stamp PLUS ITS SERVE-LATENCY MARGIN is strictly below the
     * client's stamp — acquisition stamps are issued at read completion, so a stamp can
     * postdate a save whose bytes the read missed by up to the read duration. May
     * perform one stat and one 8 KiB read; reader-pool threads only.
     */
    public long chunkStampSecondsOrUnknown(String dimension, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        var entry = entryFor(dimension, PositionUtil.packRegionOf(packed));
        HeaderSnapshot h = refreshedHeader(dimension, entry, cx >> 5, cz >> 5);
        enforceHeaderCap();
        if (h == null || h == UNREADABLE) return UNKNOWN;
        if (h == ABSENT) {
            // Region file missing: the chunk is not on disk — the real read's
            // authoritative-miss ladder (generation) owns this, never a freshness claim.
            return UNKNOWN;
        }
        // The mark latch (class javadoc): a marked change not yet observed in any
        // examined header voids every per-chunk claim for the region. Read the mark
        // AFTER the refresh so a just-raised maxHeaderSecond is seen.
        if (entry.liveSaveMarkSeconds.get() > entry.maxHeaderSecond) {
            return NEVER_CLEAN;
        }
        // The REGION HEADER's index layout — z-major, the store sweep's exact formula.
        // Deliberately NOT PositionUtil.tileSlotOf, which is x-major (the tscache tile
        // layout): transposing here reads the wrong chunk's stamp.
        int headerSecond = h.saveSeconds()[(cx & 31) + ((cz & 31) << 5)];
        return headerSecond == NEVER_SECOND ? NEVER_CLEAN : headerSecond;
    }

    /**
     * Record that content in this chunk's region changed no earlier than
     * {@code epochSeconds} (a hash-confirmed save on Fabric, a dirty-marking Bukkit
     * event on Paper — both strictly no later than the change reaching the region
     * file). Monotonic max; any thread. This arms the latch that blocks every claim
     * for the region until an examined header proves the write landed.
     */
    public void bumpLiveSaveMark(String dimension, int cx, int cz, long epochSeconds) {
        var entry = entryFor(dimension, PositionUtil.packRegionOf(PositionUtil.packPosition(cx, cz)));
        entry.liveSaveMarkSeconds.accumulateAndGet(epochSeconds, Math::max);
    }

    private RegionEntry entryFor(String dimension, long regionKey) {
        // Permanent per-region records (~64 B) — bounded by regions ever asked/marked,
        // never swept (the SNAPSHOT arrays are the capped part).
        return this.byDimension
                .computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionKey, k -> new RegionEntry());
    }

    /** The entry's examined state, statted/re-read when the horizon lapsed. Null only
     *  before the first examination completes. */
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
                // Cached like any other doubt: one resolver probe per horizon.
                entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
                setHeader(entry, UNREADABLE, h);
                return UNREADABLE;
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
            if (h != null && h.saveSeconds() != null && h.mtimeSettled() && h.mtimeMillis() == mtime) {
                // Unchanged since the last examined read: keep the memo, push the horizon.
                entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
                return h;
            }
            int[] seconds = readNormalizedHeader(mca);
            entry.statDeadlineNanos = now + STAT_HORIZON_NANOS;
            if (seconds == null) {
                // Unreadable header: no honest claim; retry after the horizon.
                setHeader(entry, UNREADABLE, h);
                return UNREADABLE;
            }
            long maxSecond = 0;
            for (int s : seconds) {
                if (s != NEVER_SECOND && s > maxSecond) maxSecond = s;
            }
            if (maxSecond > entry.maxHeaderSecond) entry.maxHeaderSecond = maxSecond;
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

    /** Swap the entry's header, maintaining the retained-array queue/count for THIS
     *  entry only. Always called under the entry's monitor; never touches another
     *  entry, so no lock-order question arises. Cap enforcement runs separately in
     *  {@link #enforceHeaderCap} (outside all monitors). */
    private void setHeader(RegionEntry entry, HeaderSnapshot fresh, HeaderSnapshot old) {
        entry.header = fresh;
        boolean hadArray = old != null && old.saveSeconds() != null;
        boolean hasArray = fresh != null && fresh.saveSeconds() != null;
        if (hasArray && !hadArray) {
            this.retainedHeaders.add(entry);
            this.retainedHeaderCount.incrementAndGet();
        } else if (hadArray && !hasArray) {
            // Decrement only on a successful remove: a concurrent enforceHeaderCap may
            // have polled this entry already (and decremented) — a blind second
            // decrement would loosen the cap permanently.
            if (this.retainedHeaders.remove(entry)) {
                this.retainedHeaderCount.decrementAndGet();
            }
        }
    }

    /**
     * Strip-and-relearn beyond the snapshot cap, OUTSIDE every refresh monitor: each
     * victim is stripped under ITS OWN monitor with no other monitor held (no
     * lock-order inversion — the P1 review's frozen-memo race is closed: a victim
     * mid-refresh either commits before we take its monitor, and we strip its fresh
     * array with an EXPIRED deadline so the next ask relearns, or commits after, and
     * its setHeader sees the null we wrote and re-tracks its array honestly).
     */
    private void enforceHeaderCap() {
        while (this.retainedHeaderCount.get() > this.maxHeaderSnapshots) {
            var victim = this.retainedHeaders.poll();
            if (victim == null) return; // count transiently ahead of the queue — settle later
            this.retainedHeaderCount.decrementAndGet();
            synchronized (victim) {
                var h = victim.header;
                if (h != null && h.saveSeconds() != null) {
                    // Dropping the header alone would let the next stat see an
                    // unchanged mtime and trust a memo that no longer exists — the
                    // whole snapshot goes, and the deadline is EXPIRED (now, never a
                    // MIN_VALUE sentinel: nanoTime deltas overflow against it) so the
                    // next ask re-reads (one 8 KiB relearn on demand).
                    victim.header = null;
                    victim.statDeadlineNanos = System.nanoTime();
                }
                // header already null/sentinel: its setHeader path did the removal
                // accounting or will re-add on its next commit — nothing to strip.
            }
        }
    }

    /**
     * The store sweep's header shape ({@code SqliteLodStore.readHeaderTimestamps}),
     * normalized for freshness claims: location 0 (chunk absent from a present region)
     * and degenerate seconds (non-positive, or implausibly far in the future — a u32
     * read as a negative int, tool-damaged headers) become {@link #NEVER_SECOND}, so
     * the compare can only ever fail toward serving. Null = unreadable.
     */
    private static int[] readNormalizedHeader(Path mca) {
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
            int[] stamps = new int[1024];
            for (int i = 0; i < 1024; i++) {
                int loc = buf.getInt(i * 4);
                if (loc == 0) {
                    stamps[i] = NEVER_SECOND;
                    continue;
                }
                long sec = buf.getInt(4096 + i * 4) & 0xFFFF_FFFFL;
                stamps[i] = (sec <= 0 || sec > ceiling) ? NEVER_SECOND : (int) sec;
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
