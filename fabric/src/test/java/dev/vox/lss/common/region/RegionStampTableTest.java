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
    void liveSaveMarkRaisesTheEffectiveStampInstantly() throws Exception {
        writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // A mark anywhere in the region raises every chunk's effective stamp — the
        // header has not changed on disk, but content is known to be moving.
        table().bumpLiveSaveMark(DIM, 3, 4, NOW - 10);
        assertEquals(NOW - 10, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // Monotonic max: an older mark never lowers it.
        table().bumpLiveSaveMark(DIM, 3, 4, NOW - 50);
        assertEquals(NOW - 10, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        assertEquals(NOW - 10, table().liveSaveMarkForTest(DIM, 3, 4));
    }

    @Test
    void mtimeChangeForcesHeaderRereadAfterHorizon() throws Exception {
        Path mca = writeRegion(3, 4, NOW - 100);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        // Rewrite with a newer save second and a distinct mtime; the memo answers the
        // OLD value until the horizon lapses (bounded staleness), then must re-read.
        var buf = ByteBuffer.allocate(8192);
        int idx = (3 & 31) + ((4 & 31) << 5);
        buf.putInt(idx * 4, 0x0000_0201);
        buf.putInt(4096 + idx * 4, (int) (NOW - 5));
        Files.write(mca, buf.array());
        Files.setLastModifiedTime(mca, FileTime.fromMillis(System.currentTimeMillis() + 4000));
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 5, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
    }

    @Test
    void fileAppearingAfterAbsenceIsPickedUpAfterHorizon() throws Exception {
        assertEquals(RegionStampTable.UNKNOWN, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
        writeRegion(3, 4, NOW - 100);
        table().expireStatHorizonForTest(DIM, 3, 4);
        assertEquals(NOW - 100, table().chunkStampSecondsOrUnknown(DIM, 3, 4));
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
