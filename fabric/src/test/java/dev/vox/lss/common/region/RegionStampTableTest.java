package dev.vox.lss.common.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the region stamp table's freshness semantics (region-summary-sync-plan.md P1):
 * per-chunk header save seconds answer honestly, and EVERY doubt path — missing file,
 * unreadable header, absent chunk, degenerate seconds, unresolvable dimension — fails
 * toward STALE (UNKNOWN/NEVER_CLEAN, which no client stamp can beat). The liveSaveMark
 * raises the effective stamp instantly (the save-submitted-but-write-pending window),
 * and an mtime change forces a header re-read once the stat horizon lapses.
 */
class RegionStampTableTest {

    private static final String DIM = "minecraft:overworld";
    private static final long NOW = System.currentTimeMillis() / 1000L;

    @TempDir
    Path dir;

    private RegionStampTable table;

    private RegionStampTable table() {
        if (this.table == null) {
            this.table = new RegionStampTable(d -> DIM.equals(d) ? this.dir : null);
        }
        return this.table;
    }

    /** Write a region file whose header holds ONE present chunk at (cx, cz) with the
     *  given save second; every other slot is absent (location 0). */
    private Path writeRegion(int cx, int cz, long saveSecond) throws Exception {
        Path mca = this.dir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        var buf = ByteBuffer.allocate(8192);
        int idx = (cx & 31) + ((cz & 31) << 5);
        buf.putInt(idx * 4, 0x0000_0201); // any nonzero location = present
        buf.putInt(4096 + idx * 4, (int) saveSecond);
        Files.write(mca, buf.array());
        return mca;
    }

    @Test
    void headerSecondAnswersForPresentChunk() throws Exception {
        writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
    }

    @Test
    void absentChunkInPresentRegionIsNeverClean() throws Exception {
        writeRegion(3, 4, NOW - 100);
        // (5, 4) shares the region file but has location 0 — deleted or never saved.
        // NEVER_CLEAN: the real read's authoritative-miss ladder owns absent chunks.
        assertEquals(RegionStampTable.NEVER_CLEAN, table().chunkStampSecondsOrUnknown(DIM, 5, 4));
    }

    @Test
    void missingRegionFileIsUnknown() {
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 100, 100));
    }

    @Test
    void unresolvableDimensionIsUnknown() throws Exception {
        writeRegion(0, 0, NOW - 100);
        assertEquals(RegionStampTable.UNKNOWN,
                table().chunkStampSecondsOrUnknown("minecraft:the_end", 0, 0));
    }

    @Test
    void throwingResolverIsUnknown() {
        var t = new RegionStampTable(d -> { throw new IllegalStateException("boom"); });
        assertEquals(RegionStampTable.UNKNOWN, t.chunkStampSecondsOrUnknown(DIM, 0, 0));
    }

    @Test
    void truncatedHeaderIsUnknown() throws Exception {
        Files.write(this.dir.resolve("r.0.0.mca"), new byte[100]); // < 8 KiB header
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 1, 1));
    }

    @Test
    void degenerateHeaderSecondsAreNeverClean() throws Exception {
        // Zero, negative-as-u32 (a u32 > 2^31 must not compare "ancient"), and
        // far-future seconds are damage, not saves (adversarial A10).
        writeRegion(0, 0, 0);
        assertEquals(RegionStampTable.NEVER_CLEAN, table().chunkStampSecondsOrUnknown(DIM, 0, 0));

        var t2 = new RegionStampTable(d -> this.dir);
        writeRegion(32, 0, 0xFFFF_FFF0L); // reads as a huge u32, far beyond now + skew
        assertEquals(RegionStampTable.NEVER_CLEAN, t2.chunkStampSecondsOrUnknown(DIM, 32, 0));

        var t3 = new RegionStampTable(d -> this.dir);
        writeRegion(64, 0, NOW + 86_400); // a day in the future: beyond the skew allowance
        assertEquals(RegionStampTable.NEVER_CLEAN, t3.chunkStampSecondsOrUnknown(DIM, 64, 0));
    }

    @Test
    void markAboveTheObservedHeaderLatchesTheWholeRegion() throws Exception {
        writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // A mark NEWER than any examined header second means a change is in flight to
        // disk: the whole region must answer NEVER_CLEAN — comparing client stamps
        // against the mark TIME is unsound (a read racing the pending write hands out
        // stamps newer than the mark while carrying pre-change bytes).
        table().bumpLiveSaveMark(DIM, 3, 4, NOW - 10);
        assertEquals(RegionStampTable.NEVER_CLEAN, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        assertEquals(NOW - 10, table().liveSaveMarkForTest(DIM, 3, 4));
        // The latch self-clears when the write LANDS: a re-read observing a header
        // second at/above the mark proves the change is disk-visible, and per-chunk
        // seconds answer alone again (the mark never degrades the region permanently).
        Path mca = writeRegion(3, 4, NOW - 5);
        Files.setLastModifiedTime(mca, FileTime.fromMillis(System.currentTimeMillis() + 4000));
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 5, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
    }

    @Test
    void markAtOrBelowTheObservedHeaderDoesNotLatch() throws Exception {
        writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // A mark the examined header already covers (mark <= maxHeaderSecond) proves
        // nothing is pending — per-chunk seconds keep answering.
        table().bumpLiveSaveMark(DIM, 3, 4, NOW - 150);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        table().bumpLiveSaveMark(DIM, 3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
    }

    @Test
    void mtimeIsTheChangeDetectorOnceSettled() throws Exception {
        // A SETTLED first read (mtime in the past second, stable across the re-stat)
        // arms the == shortcut; this pins the detector in BOTH directions (review
        // minor: the original shape read in the write's own second, never settled,
        // and would have passed with the mtime compare inverted or deleted).
        Path mca = writeRegion(3, 4, NOW - 100);
        Files.setLastModifiedTime(mca, FileTime.fromMillis(System.currentTimeMillis() - 5000));
        FileTime settledMtime = Files.getLastModifiedTime(mca);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // Rewrite the CONTENT but restore the EXACT same mtime: the == shortcut must
        // keep the memo (no re-read) — that is the stat-as-detector contract.
        var buf = ByteBuffer.allocate(8192);
        int idx = (3 & 31) + ((4 & 31) << 5);
        buf.putInt(idx * 4, 0x0000_0201);
        buf.putInt(4096 + idx * 4, (int) (NOW - 5));
        Files.write(mca, buf.array());
        Files.setLastModifiedTime(mca, settledMtime);
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4),
                "unchanged mtime must keep the memo — the detector is the mtime, not the horizon");
        // Now move the mtime: the != compare must force the re-read.
        Files.setLastModifiedTime(mca, FileTime.fromMillis(System.currentTimeMillis() + 4000));
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 5, table().chunkStampSecondsOrUnknown(DIM, 3, 4),
                "a changed mtime must re-read the header");
    }

    @Test
    void unreadableHeaderIsCachedForAHorizonThenRetried() throws Exception {
        Path mca = this.dir.resolve("r.0.0.mca");
        Files.write(mca, new byte[100]); // truncated: unreadable
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 1, 1));
        // Repaired within the horizon: the UNREADABLE sentinel must still answer (one
        // probe per horizon — doubt never becomes per-ask IO).
        writeRegion(1, 1, NOW - 100);
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 1, 1));
        table().expireStatHorizonForTest(DIM, 1, 1);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 1, 1));
    }

    @Test
    void snapshotCapStripsAndRelearns() throws Exception {
        // A 2-snapshot cap over 4 regions: the FIFO strips, the count honors the cap,
        // and a stripped region answers CORRECTLY on re-demand (strip-and-relearn).
        var t = new RegionStampTable(d -> this.dir, 2);
        for (int r = 0; r < 4; r++) {
            writeRegion(r * 32, 0, NOW - 100 - r);
        }
        for (int r = 0; r < 4; r++) {
            assertEquals(NOW - 100 - r, t.chunkStampSecondsOrUnknown(DIM, r * 32, 0));
        }
        assertTrue(t.retainedHeaderCountForTest() <= 2,
                "cap must hold: " + t.retainedHeaderCountForTest());
        // Region 0 was stripped; re-demand relearns the same honest answer.
        assertEquals(NOW - 100, t.chunkStampSecondsOrUnknown(DIM, 0, 0));
        assertTrue(t.retainedHeaderCountForTest() <= 2);
    }

    @Test
    void fileAppearingAfterAbsenceIsPickedUpAfterHorizon() throws Exception {
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        writeRegion(3, 4, NOW - 100);
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
    }

    // ---- tile stamps (the P2 summary sweeper's question) ----

    @Test
    void tileStampReportsTheRegionMaxHeaderSecond() throws Exception {
        // Two present chunks; the tile stamp is the max save second.
        Path mca = this.dir.resolve("r.0.0.mca");
        var buf = ByteBuffer.allocate(8192);
        int idxA = (3 & 31) + ((4 & 31) << 5);
        int idxB = (10 & 31) + ((20 & 31) << 5);
        buf.putInt(idxA * 4, 0x0000_0201);
        buf.putInt(4096 + idxA * 4, (int) (NOW - 500));
        buf.putInt(idxB * 4, 0x0000_0401);
        buf.putInt(4096 + idxB * 4, (int) (NOW - 100));
        Files.write(mca, buf.array());
        assertEquals(NOW - 100, table().tileStampSeconds(DIM, 0, 0));
    }

    @Test
    void tileStampNoRegionIsZeroAndMarkedNoRegionIsNeverClean() {
        assertEquals(0, table().tileStampSeconds(DIM, 5, 5),
                "no region file = nothing on disk to validate against");
        // A mark aimed at a region that does not exist yet = a change in flight.
        table().bumpLiveSaveMark(DIM, 6 << 5, 6 << 5, NOW - 1);
        assertEquals(RegionStampTable.NEVER_CLEAN, table().tileStampSeconds(DIM, 6, 6));
    }

    @Test
    void tileStampUnresolvableDimensionIsNeverClean() {
        assertEquals(RegionStampTable.NEVER_CLEAN,
                table().tileStampSeconds("minecraft:the_end", 0, 0));
    }

    @Test
    void tileStampLatchesBehindAPendingMark() throws Exception {
        writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().tileStampSeconds(DIM, 0, 0));
        table().bumpLiveSaveMark(DIM, 3, 4, NOW - 10);
        assertEquals(RegionStampTable.NEVER_CLEAN, table().tileStampSeconds(DIM, 0, 0));
        // The write lands: header re-read at/above the mark clears the latch.
        Path mca = writeRegion(3, 4, NOW - 5);
        Files.setLastModifiedTime(mca, FileTime.fromMillis(System.currentTimeMillis() + 4000));
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 5, table().tileStampSeconds(DIM, 0, 0));
    }

    @Test
    void tileStampDegenerateSecondPoisonsTheWholeRegion() throws Exception {
        // One good chunk + one EXISTING chunk with a garbage save second: the garbage
        // chunk can change without moving maxHeaderSecond, so the tile must go NEVER
        // (per-chunk absence via location 0 does NOT poison — pinned by the no-region
        // and max tests above).
        Path mca = this.dir.resolve("r.0.0.mca");
        var buf = ByteBuffer.allocate(8192);
        int idxA = (3 & 31) + ((4 & 31) << 5);
        int idxB = (10 & 31) + ((20 & 31) << 5);
        buf.putInt(idxA * 4, 0x0000_0201);
        buf.putInt(4096 + idxA * 4, (int) (NOW - 500));
        buf.putInt(idxB * 4, 0x0000_0401);
        buf.putInt(4096 + idxB * 4, 0); // present chunk, zero second — damage
        Files.write(mca, buf.array());
        assertEquals(RegionStampTable.NEVER_CLEAN, table().tileStampSeconds(DIM, 0, 0));
    }

    @Test
    void newRegionAppearsInTheListingAfterAHorizon() throws Exception {
        assertEquals(0, table().tileStampSeconds(DIM, 0, 0));
        writeRegion(3, 4, NOW - 100);
        // Within the listing horizon the absence is still cached...
        assertEquals(0, table().tileStampSeconds(DIM, 0, 0));
        // ...and after it, the new file is seen (the readdir is the detector).
        table().expireListingHorizonForTest(DIM);
        assertEquals(NOW - 100, table().tileStampSeconds(DIM, 0, 0));
    }

    @Test
    void hostileRegionFileNamesAreSkipped() throws Exception {
        Files.write(this.dir.resolve("r.99999999999.0.mca"), new byte[]{1}); // int overflow
        Files.write(this.dir.resolve("r.x.0.mca"), new byte[]{1});           // non-numeric
        Files.write(this.dir.resolve("r.1.2.3.mca"), new byte[]{1});         // extra segment
        writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().tileStampSeconds(DIM, 0, 0),
                "malformed names must be skipped, never crash the listing");
        assertEquals(0, table().tileStampSeconds(DIM, 99, 99));
    }

    @Test
    void memoServesWithoutRereadWithinHorizon() throws Exception {
        Path mca = writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // Deleting the file behind the memo: within the horizon the memoized answer
        // stands (bounded staleness by design — the horizon is the contract).
        Files.delete(mca);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // Past the horizon the absence is observed and the claim retracts to UNKNOWN.
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
    }
}
