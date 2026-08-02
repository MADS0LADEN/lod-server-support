package dev.vox.lss.common.store;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreService.StoreHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The SQLite LOD store tier (plan §2, Phase 2). Pins the parts a live server cannot
 * cheaply exercise: cross-restart round-trip, meta drop-and-rebuild (derived data is
 * never migrated), the tombstone protocol closing the async-delete window, latest-wins
 * by stored ts, and the whole freshness-sweep decision table — stale header drop,
 * unchanged-mtime skip, vanished region, absent chunk (loc==0), unresolvable region
 * dir, mask-fingerprint drift, the serving-gate before sweep completion, and the
 * periodic re-sweep (Paper's stale bound).
 *
 * <p>Region files are synthesized as bare 8 KiB headers — the sweep reads only the
 * location + timestamp tables, never chunk payloads.
 */
class SqliteLodStoreTest {

    private static final String OW = "minecraft:overworld";
    private static final String END = "minecraft:the_end";
    private static final int WIRE = 18;

    @TempDir
    Path tmp;

    // ---- environment / region-file plumbing ----

    private Path storeDir() {
        return this.tmp.resolve("store");
    }

    private Path regionDir(String dim) {
        return this.tmp.resolve(dim.replace(':', '_')).resolve("region");
    }

    private SqliteLodStore.Environment env(int wireVersion, List<String> dims,
                                           Map<String, String> fps,
                                           Function<String, Path> regionResolver,
                                           int resweepSeconds) {
        return new SqliteLodStore.Environment(storeDir(), "26.2-test", wireVersion,
                regionResolver, d -> fps.getOrDefault(d, ""), resweepSeconds);
    }

    private SqliteLodStore.Environment defaultEnv() {
        return env(WIRE, List.of(OW, END), Map.of(), this::regionDir, 0);
    }

    private SqliteLodStore open(SqliteLodStore.Environment env) throws Exception {
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store, "SQLite engine must be available on the test JVM");
        assertTrue(store.awaitSweep(10_000), "startup sweep must complete");
        return store;
    }

    private static int headerIndex(int cx, int cz) {
        return (cx & 31) + ((cz & 31) << 5);
    }

    /** Writes a bare region header: the given chunks present at the given epoch-second
     *  stamps, every other location entry zero (chunk absent). */
    private void writeRegion(String dim, int rx, int rz, Map<Long, Integer> stampsByPos)
            throws Exception {
        Path dir = regionDir(dim);
        Files.createDirectories(dir);
        ByteBuffer buf = ByteBuffer.allocate(8192);
        for (var e : stampsByPos.entrySet()) {
            int cx = PositionUtil.unpackX(e.getKey());
            int cz = PositionUtil.unpackZ(e.getKey());
            int idx = headerIndex(cx, cz);
            buf.putInt(idx * 4, (2 << 8) | 1); // any nonzero location = chunk present
            buf.putInt(4096 + idx * 4, e.getValue());
        }
        Files.write(dir.resolve("r." + rx + "." + rz + ".mca"), buf.array());
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    /** Polls until the deposited row is visible through get() (batcher apply + commit). */
    private static StoreHit awaitHit(SqliteLodStore store, String dim, long pos)
            throws Exception {
        for (int i = 0; i < 400; i++) {
            StoreHit hit = store.get(dim, pos);
            if (hit != null) return hit;
            Thread.sleep(25);
        }
        return null;
    }

    private static void awaitGone(SqliteLodStore store, String dim, long pos)
            throws Exception {
        for (int i = 0; i < 400; i++) {
            if (store.get(dim, pos) == null && store.diagnostics().getQueueDepth() == 0) return;
            Thread.sleep(25);
        }
    }

    private static byte[] bytes(int seed, int len) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    private long sqlRowCount(String dim) throws Exception {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=3000");
            int dimId;
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM dims WHERE name='" + dim + "'")) {
                if (!rs.next()) return 0;
                dimId = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM lods_" + dimId)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ---- round-trip / ordering / tombstones ----

    @Test
    void depositRoundTripsAcrossReopenIncludingAllAir() throws Exception {
        long p0 = PositionUtil.packPosition(0, 0);
        long p1 = PositionUtil.packPosition(1, 0);
        long pAir = PositionUtil.packPosition(2, 0);
        // Chunks present on disk with stamps well BEFORE the deposits, so the reopen
        // sweep keeps the rows.
        writeRegion(OW, 0, 0, Map.of(p0, (int) (nowSec() - 1000),
                p1, (int) (nowSec() - 1000), pAir, (int) (nowSec() - 1000)));
        byte[] big = bytes(1, 40_000);
        byte[] small = bytes(2, 300);

        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p0, big, 111);
        store.deposit(OW, p1, small, 222);
        store.deposit(OW, pAir, new byte[0], 333);
        StoreHit h = awaitHit(store, OW, pAir);
        assertNotNull(h, "all-air deposit must round-trip");
        assertEquals(0, h.sectionBytes().length, "all-air is byte[0], never null");
        assertEquals(333, h.columnTimestamp());
        store.shutdown();

        SqliteLodStore reopened = open(defaultEnv());
        StoreHit rh0 = reopened.get(OW, p0);
        StoreHit rh1 = reopened.get(OW, p1);
        StoreHit rhAir = reopened.get(OW, pAir);
        assertNotNull(rh0, "row must survive a restart");
        assertArrayEquals(big, rh0.sectionBytes(), "bytes must survive compress+restart verbatim");
        assertEquals(111, rh0.columnTimestamp(), "the STORED ts is what a hit serves");
        assertNotNull(rh1);
        assertArrayEquals(small, rh1.sectionBytes());
        assertNotNull(rhAir);
        assertEquals(0, rhAir.sectionBytes().length);
        assertEquals(0, reopened.diagnostics().getErrors(), "clean reopen must not count errors");
        reopened.shutdown();
    }

    @Test
    void latestWinsByStoredTsAndSkipCounted() throws Exception {
        long p = PositionUtil.packPosition(3, 0);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        byte[] newer = bytes(3, 500);
        byte[] older = bytes(4, 500);
        store.deposit(OW, p, newer, 200);
        store.deposit(OW, p, older, 100); // must NOT overwrite the ts=200 row
        StoreHit h = awaitHit(store, OW, p);
        assertNotNull(h);
        assertEquals(200, h.columnTimestamp(), "older deposit must not overwrite newer row");
        assertArrayEquals(newer, h.sectionBytes());
        assertTrue(store.diagnostics().getDepositSkips() >= 1,
                "the losing deposit is counted store.deposit_skips");

        byte[] newest = bytes(5, 500);
        store.deposit(OW, p, newest, 300);
        for (int i = 0; i < 400; i++) {
            StoreHit cur = store.get(OW, p);
            if (cur != null && cur.columnTimestamp() == 300) break;
            Thread.sleep(25);
        }
        assertEquals(300, store.get(OW, p).columnTimestamp(), "newer deposit must overwrite");
        store.shutdown();
    }

    @Test
    void invalidateIsEffectiveBeforeTheAsyncRowDelete() throws Exception {
        long p = PositionUtil.packPosition(4, 0);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p, bytes(6, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        store.invalidate(OW, new long[]{p});
        // IMMEDIATELY after invalidate returns — the batcher has likely not applied the
        // row delete yet — the tombstone must already read as a miss.
        assertNull(store.get(OW, p), "invalidate must be effective before any subsequent get()");
        awaitGone(store, OW, p);
        assertNull(store.get(OW, p), "and stays a miss after the batcher applies the delete");
        store.shutdown();
        // DB-level: the ROW must be gone, not just tombstone-masked — a tombstone
        // expires after 10 s and a surviving row would resurrect (review finding: the
        // old assertions could pass on the tombstone alone).
        assertEquals(0, sqlRowCount(OW), "the row itself must be deleted, not just masked");
    }

    /** The deposit-racing-invalidate window: whatever the interleaving with the batcher,
     *  a deposit followed by an invalidate must never leave a servable row. */
    @Test
    void depositImmediatelyInvalidatedNeverResurrects() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        for (int i = 0; i < 50; i++) {
            long p = PositionUtil.packPosition(i, 1);
            store.deposit(OW, p, bytes(i, 200), 100 + i);
            store.invalidate(OW, new long[]{p});
        }
        for (int i = 0; i < 50; i++) {
            long p = PositionUtil.packPosition(i, 1);
            awaitGone(store, OW, p);
            assertNull(store.get(OW, p), "poisoned row resurrected at i=" + i);
        }
        store.shutdown();
        // DB-level: every racing deposit's row must be physically gone (tombstones
        // expire; only the delete keeps them dead).
        assertEquals(0, sqlRowCount(OW), "racing deposits left rows that would resurrect");
    }

    // ---- derived-data lifecycle ----

    @Test
    void metaDriftDropsAndRebuildsTheStore() throws Exception {
        long p = PositionUtil.packPosition(0, 2);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p, bytes(7, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        store.shutdown();

        // Wire-format bump: derived data is dropped and rebuilt, never migrated.
        SqliteLodStore bumped = open(env(WIRE + 1, List.of(OW, END), Map.of(),
                this::regionDir, 0));
        assertNull(bumped.get(OW, p), "old-wire rows must not survive a wire bump");
        assertEquals(0, bumped.diagnostics().getErrors(), "drop-and-rebuild is not an error");
        // ...and the rebuilt store is fully functional.
        bumped.deposit(OW, p, bytes(8, 400), 200);
        assertNotNull(awaitHit(bumped, OW, p), "rebuilt store must accept deposits");
        bumped.shutdown();
    }

    @Test
    void registryFingerprintDriftDropsAndRebuildsTheStore() throws Exception {
        long p = PositionUtil.packPosition(0, 2);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(new SqliteLodStore.Environment(storeDir(), "26.2-test",
                WIRE, this::regionDir, d -> "", 0, Long.MAX_VALUE, "bs1000-bioAAAA"));
        store.deposit(OW, p, bytes(7, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        store.shutdown();

        // A mod/datapack change shifts the GLOBAL block-state/biome ids the stored wire
        // bytes embed, while region files stay untouched — no freshness rule can fire,
        // so the registry fingerprint is the ONLY guard (4-agent round R2-M3).
        SqliteLodStore shifted = open(new SqliteLodStore.Environment(storeDir(), "26.2-test",
                WIRE, this::regionDir, d -> "", 0, Long.MAX_VALUE, "bs1002-bioAAAA"));
        assertNull(shifted.get(OW, p),
                "rows encoded under the old registry must not survive a registry change");
        assertEquals(0, shifted.diagnostics().getErrors(), "drop-and-rebuild is not an error");
        shifted.shutdown();
    }

    @Test
    void corruptDbFileIsDroppedAndRecreated() throws Exception {
        Files.createDirectories(storeDir());
        Files.write(storeDir().resolve("store.db"), bytes(9, 4096)); // garbage, not SQLite
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        long p = PositionUtil.packPosition(1, 2);
        store.deposit(OW, p, bytes(10, 400), 100);
        assertNotNull(awaitHit(store, OW, p), "store must recover from a corrupt DB file");
        store.shutdown();
    }

    @Test
    void rowIntegrityFailureCountsErrorAndPurgesTheRow() throws Exception {
        long p = PositionUtil.packPosition(2, 2);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p, bytes(11, 4000), 100);
        assertNotNull(awaitHit(store, OW, p));
        // Corrupt the row's chash out-of-band (a bit-rot / partial-write stand-in).
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=3000");
            st.execute("UPDATE lods_1 SET chash = chash + 1 WHERE pos=" + p);
        }
        assertNull(store.get(OW, p), "an integrity failure must read as a miss");
        assertTrue(store.diagnostics().getErrors() >= 1, "…and count store.errors");
        // Poll the DB truth, not the queue gauge: store.queue is drain-side (a queued
        // purge is invisible until the batcher stamps the next pass).
        long rows = -1;
        for (int i = 0; i < 400; i++) {
            rows = sqlRowCount(OW);
            if (rows == 0) break;
            Thread.sleep(25);
        }
        assertEquals(0, rows, "the poisoned row must be purged so it cannot re-fail");
        store.shutdown();
    }

    // ---- freshness sweep decision table ----

    @Test
    void sweepDropsRowsWhoseHeaderStampAdvancedAndKeepsUntouchedOnes() throws Exception {
        long edited = PositionUtil.packPosition(0, 3);
        long untouched = PositionUtil.packPosition(1, 3);
        writeRegion(OW, 0, 0, Map.of(edited, (int) (nowSec() - 1000),
                untouched, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, edited, bytes(12, 400), 100);
        store.deposit(OW, untouched, bytes(13, 400), 100);
        assertNotNull(awaitHit(store, OW, untouched));
        store.shutdown();

        // Offline edit: the chunk re-saved AFTER the deposit — header stamp advances
        // past src_stamp; the untouched chunk keeps its old stamp. Same region file, so
        // this also pins per-COLUMN granularity (one edit must not drop the region).
        writeRegion(OW, 0, 0, Map.of(edited, (int) (nowSec() + 100),
                untouched, (int) (nowSec() - 1000)));
        SqliteLodStore reopened = open(defaultEnv());
        assertNull(reopened.get(OW, edited), "offline-edited column must be dropped");
        assertNotNull(reopened.get(OW, untouched),
                "untouched column in the same region must survive");
        reopened.shutdown();
    }

    /** The review-MAJOR regression pin: shutdown must NOT record mtimes for regions
     *  whose headers were never examined. An in-session region change that the (never-
     *  scheduled) sweep did not see must be caught by the NEXT boot's sweep — the old
     *  shutdown-time bulk mtime snapshot marked it "seen" and the stale row served
     *  forever. */
    @Test
    void shutdownMustNotMarkUnexaminedRegionsSeen() throws Exception {
        long p = PositionUtil.packPosition(3, 3);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p, bytes(31, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        // The region changes IN-SESSION with no resweep configured (the Paper
        // unfired-event shape with the sweep off): only the next boot can catch it.
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() + 100)));
        store.shutdown();

        SqliteLodStore reopened = open(defaultEnv());
        assertNull(reopened.get(OW, p),
                "a region changed after its last examination must be re-checked at boot"
                        + " — shutdown must never stamp unexamined regions as seen");
        reopened.shutdown();
    }

    /** The dims-TABLE iteration pin: a dimension deposited at runtime (created world)
     *  that the platform resolver cannot place must be fail-safe swept at reopen —
     *  never served unswept (the old knownDimensions-frozen loop exempted it from
     *  every freshness rule). */
    @Test
    void runtimeDimensionUnknownToTheResolverIsDroppedAtReopen() throws Exception {
        String runtimeDim = "multiverse:custom_world";
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        long p = PositionUtil.packPosition(0, 10);
        store.deposit(runtimeDim, p, bytes(32, 300), 100);
        assertNotNull(awaitHit(store, runtimeDim, p));
        store.shutdown();

        // defaultEnv's resolver maps ANY name to a path, but runtimeDim's dir does not
        // exist -> unresolvable -> the dims-table sweep must drop its rows.
        SqliteLodStore reopened = open(defaultEnv());
        assertNull(reopened.get(runtimeDim, p),
                "a stored dimension the resolver cannot place must fail-safe drop");
        reopened.shutdown();
    }

    @Test
    void sweepSkipsRegionsWhoseMtimeIsUnchangedSinceItsLastExamination() throws Exception {
        long p = PositionUtil.packPosition(2, 3);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        // Age the file: seen_mtime is deliberately NOT recorded for a file whose mtime
        // is in the CURRENT second (the 1 s-granularity race guard, R1) — a real boot
        // never sweeps regions written this instant, so settle the stamp explicitly.
        Files.setLastModifiedTime(regionDir(OW).resolve("r.0.0.mca"),
                FileTime.fromMillis(System.currentTimeMillis() - 5000));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p, bytes(14, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        store.shutdown();

        // Session 2's startup sweep EXAMINES the region (no seen entry yet), keeps the
        // fresh row, and records the pre-read mtime — seen_mtime only ever exists next
        // to an actual examination (the review-MAJOR semantics).
        SqliteLodStore examined = open(defaultEnv());
        assertNotNull(examined.get(OW, p), "fresh row must survive its first examination");
        examined.shutdown();

        // Rewrite the header with a FUTURE stamp but restore the examined mtime: the
        // != mtime gate must short-circuit the header read entirely (this is the
        // optimization that makes the sweep one stat per unchanged region).
        Path mca = regionDir(OW).resolve("r.0.0.mca");
        FileTime examinedMtime = Files.getLastModifiedTime(mca);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() + 100)));
        Files.setLastModifiedTime(mca, examinedMtime);
        SqliteLodStore reopened = open(defaultEnv());
        assertNotNull(reopened.get(OW, p),
                "unchanged mtime since the last EXAMINATION must skip the header check");
        reopened.shutdown();
    }

    @Test
    void sweepDropsRowsOfVanishedRegionsAndAbsentChunks() throws Exception {
        long vanished = PositionUtil.packPosition(40, 0);  // region r.1.0
        long absent = PositionUtil.packPosition(0, 4);     // region r.0.0, loc zeroed later
        long kept = PositionUtil.packPosition(1, 4);
        writeRegion(OW, 1, 0, Map.of(vanished, (int) (nowSec() - 1000)));
        writeRegion(OW, 0, 0, Map.of(absent, (int) (nowSec() - 1000),
                kept, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, vanished, bytes(15, 400), 100);
        store.deposit(OW, absent, bytes(16, 400), 100);
        store.deposit(OW, kept, bytes(17, 400), 100);
        assertNotNull(awaitHit(store, OW, kept));
        store.shutdown();

        Files.delete(regionDir(OW).resolve("r.1.0.mca"));
        // Rewrite r.0.0 WITHOUT the absent chunk (loc==0) — a region-tool chunk delete.
        writeRegion(OW, 0, 0, Map.of(kept, (int) (nowSec() - 1000)));
        SqliteLodStore reopened = open(defaultEnv());
        assertNull(reopened.get(OW, vanished),
                "vanished region must drop its rows (deleted chunks must regenerate)");
        assertNull(reopened.get(OW, absent),
                "chunk absent from a present region (loc==0) must drop its row");
        assertNotNull(reopened.get(OW, kept));
        reopened.shutdown();
    }

    @Test
    void unresolvableRegionDirDropsOnlyThatDimension() throws Exception {
        long owPos = PositionUtil.packPosition(0, 5);
        long endPos = PositionUtil.packPosition(1, 5);
        writeRegion(OW, 0, 0, Map.of(owPos, (int) (nowSec() - 1000)));
        writeRegion(END, 0, 0, Map.of(endPos, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, owPos, bytes(18, 400), 100);
        store.deposit(END, endPos, bytes(19, 400), 100);
        assertNotNull(awaitHit(store, OW, owPos));
        assertNotNull(awaitHit(store, END, endPos));
        store.shutdown();

        Function<String, Path> resolver = d -> END.equals(d) ? null : regionDir(d);
        SqliteLodStore reopened = open(env(WIRE, List.of(OW, END), Map.of(), resolver, 0));
        assertNull(reopened.get(END, endPos),
                "unresolvable region dir must drop the dimension's rows (fail-safe)");
        assertNotNull(reopened.get(OW, owPos), "the other dimension must be untouched");
        reopened.shutdown();
    }

    @Test
    void maskFingerprintDriftDropsTheDimensionsRows() throws Exception {
        long p = PositionUtil.packPosition(0, 6);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(env(WIRE, List.of(OW, END),
                Map.of(OW, "antixray:aaaa"), this::regionDir, 0));
        store.deposit(OW, p, bytes(20, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        store.shutdown();

        // The admin changes the hidden-block list: stored bytes were masked under the
        // OLD policy and must not serve under the new one (mask fingerprint is
        // LOAD-BEARING — OBS-6).
        SqliteLodStore reopened = open(env(WIRE, List.of(OW, END),
                Map.of(OW, "antixray:bbbb"), this::regionDir, 0));
        assertNull(reopened.get(OW, p), "mask drift must drop the dimension's rows");
        // The new fingerprint is now the stored one: a third open with the SAME policy
        // keeps freshly deposited rows.
        reopened.deposit(OW, p, bytes(21, 400), 200);
        assertNotNull(awaitHit(reopened, OW, p));
        reopened.shutdown();
        SqliteLodStore third = open(env(WIRE, List.of(OW, END),
                Map.of(OW, "antixray:bbbb"), this::regionDir, 0));
        assertNotNull(third.get(OW, p), "stable fingerprint must keep rows");
        third.shutdown();
    }

    // ---- serving gate + periodic re-sweep ----

    @Test
    void storeServesNothingUntilTheStartupSweepCompletes() throws Exception {
        long p = PositionUtil.packPosition(0, 7);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(defaultEnv());
        store.deposit(OW, p, bytes(22, 400), 100);
        assertNotNull(awaitHit(store, OW, p));
        store.shutdown();

        CountDownLatch gate = new CountDownLatch(1);
        Function<String, Path> blocking = d -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return regionDir(d);
        };
        SqliteLodStore reopened = SqliteLodStore.createOrNull(LodStoreMode.FULL,
                env(WIRE, List.of(OW, END), Map.of(), blocking, 0), new LodStoreDiagnostics());
        assertNotNull(reopened);
        try {
            assertNull(reopened.get(OW, p),
                    "a not-yet-swept row must never serve (boot misses fall to the NBT ladder)");
        } finally {
            gate.countDown();
        }
        assertTrue(reopened.awaitSweep(10_000));
        assertNotNull(reopened.get(OW, p), "…and serves normally once the sweep completes");
        reopened.shutdown();
    }

    @Test
    void periodicResweepDropsRowsStaleFromUnfiredEvents() throws Exception {
        long p = PositionUtil.packPosition(0, 8);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(env(WIRE, List.of(OW, END), Map.of(),
                this::regionDir, 1)); // 1 s cadence — Paper's stale bound
        store.deposit(OW, p, bytes(23, 400), 100);
        assertNotNull(awaitHit(store, OW, p));

        // The Paper unfired-event shape: the chunk is re-saved DURING the session with
        // no Bukkit event fired — only the region file changes.
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() + 100)));
        Files.setLastModifiedTime(regionDir(OW).resolve("r.0.0.mca"),
                FileTime.fromMillis(System.currentTimeMillis() + 5000));
        boolean dropped = false;
        for (int i = 0; i < 400 && !dropped; i++) {
            dropped = store.get(OW, p) == null;
            Thread.sleep(25);
        }
        assertTrue(dropped, "the periodic re-sweep must drop the offline-edited row in-session");
        assertTrue(store.diagnostics().getSweepDrops() >= 1,
                "sweep drops must be counted (store.sweep_drops — the resweep's only "
                        + "live observable)");
        store.shutdown();
    }

    /** The tiered-composition contract at this layer: a resweep drop must be REPORTED
     *  (the memory tier in front would otherwise keep serving the stale copy). */
    @Test
    void periodicResweepReportsDroppedPositionsToTheListener() throws Exception {
        long p = PositionUtil.packPosition(1, 8);
        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() - 1000)));
        SqliteLodStore store = open(env(WIRE, List.of(OW, END), Map.of(),
                this::regionDir, 1));
        var reported = new java.util.concurrent.ConcurrentHashMap<String, long[]>();
        store.setSweepDropListener(reported::put);
        store.deposit(OW, p, bytes(30, 400), 100);
        assertNotNull(awaitHit(store, OW, p));

        writeRegion(OW, 0, 0, Map.of(p, (int) (nowSec() + 100)));
        Files.setLastModifiedTime(regionDir(OW).resolve("r.0.0.mca"),
                FileTime.fromMillis(System.currentTimeMillis() + 5000));
        long[] positions = null;
        for (int i = 0; i < 400 && positions == null; i++) {
            positions = reported.get(OW);
            Thread.sleep(25);
        }
        assertNotNull(positions, "the resweep must report its drops to the listener");
        assertArrayEquals(new long[]{p}, positions);
        store.shutdown();
    }

    // ---- misc contract ----

    /** Phase 5 size cap: above maxDbBytes the batcher evicts oldest-ts rows and
     *  vacuums; newest rows survive, evicted ones read as misses (re-warm on serve).
     *
     *  <p>The cap here is ~2 MB rather than the ~200 KB it used to be: on 16 KB pages
     *  the schema's own tables and indices occupy a floor of roughly 150 KB, so a
     *  200 KB cap leaves no room for ANY row and evicting the store empty is the
     *  correct answer to it — which makes it useless as a survivor pin. Production
     *  cannot reach that regime anyway ({@code lodStoreMaxMB} clamps at 64 MB). */
    @Test
    void sizeCapEvictsOldestRowsAndKeepsNewest() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        var env = new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE,
                this::regionDir, d -> "", 0, 2_000_000L); // ~2 MB cap
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        // ~200 x 20 KB (incompressible) rows ≈ 4 MB >> the cap; ts ascending.
        for (int i = 0; i < 200; i++) {
            store.deposit(OW, PositionUtil.packPosition(i, 12), bytes(100 + i, 20_000), 1000 + i);
        }
        assertNotNull(awaitHit(store, OW, PositionUtil.packPosition(199, 12)));
        boolean evicted = false;
        for (int i = 0; i < 600 && !evicted; i++) { // gauge cadence is 5 s
            evicted = store.get(OW, PositionUtil.packPosition(0, 12)) == null;
            Thread.sleep(50);
        }
        assertTrue(evicted, "the oldest-ts row must be evicted under the size cap");
        // Assert the SURVIVOR only after the cap has converged. lastGaugeRefreshNanos
        // starts at 0, so the batcher's very first loop iteration runs a gauge refresh
        // with a single row committed — an assertion taken at the first eviction
        // observes that boot artifact rather than the cap's steady state. This pin
        // passed there while the store went on to evict itself to zero (v0.9.0
        // review); awaiting convergence is what makes the survivor claim real.
        awaitStableEvictions(store);
        assertNotNull(store.get(OW, PositionUtil.packPosition(199, 12)),
                "the newest row must survive eviction");
        assertEquals(0, store.diagnostics().getErrors());
        store.shutdown();
    }

    /** Blocks until the eviction counter holds steady across a full gauge interval,
     *  i.e. the cap has converged rather than being observed mid-round. */
    private static void awaitStableEvictions(SqliteLodStore store) throws Exception {
        long last = -1;
        for (int i = 0; i < 10; i++) { // ~60 s worst case; typically two samples
            Thread.sleep(6_000); // > the 5 s gauge cadence
            long now = store.diagnostics().getSqlEvictions();
            if (now > 0 && now == last) return;
            last = now;
        }
        fail("size-cap eviction never converged");
    }

    /** The size cap must converge on a working set, not treadmill.
     *
     *  <p>Before v0.9.0 the cap compared against {@code Files.size()} while
     *  {@code PRAGMA incremental_vacuum} reclaimed exactly ONE page per JDBC call, so
     *  the file could never fall back under the cap and the store evicted every
     *  deposit forever. Measured on the real engine: 448 rows / ~9 MB alive against a
     *  64 MB cap (14% utilisation), and a one-shot burst ended at ZERO rows with the
     *  file stuck permanently above its cap — dead for the life of that DB. This
     *  deposits well over the cap, lets eviction settle, and pins both halves the old
     *  test could not: a substantial working set survives, and an IDLE capped store
     *  stops evicting. */
    @Test
    void sizeCapConvergesToAWorkingSetInsteadOfEvictingEverything() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        // ~2 MB cap; 20 KB incompressible rows on 16 KB pages ≈ 60 rows of headroom.
        var env = new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE,
                this::regionDir, d -> "", 0, 2_000_000L);
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        int total = 300; // ~6 MB deposited against a ~2 MB cap
        for (int i = 0; i < total; i++) {
            store.deposit(OW, PositionUtil.packPosition(i, 12), bytes(100 + i, 20_000), 1000 + i);
        }
        assertNotNull(awaitHit(store, OW, PositionUtil.packPosition(total - 1, 12)));
        awaitStableEvictions(store);

        int alive = countAlive(store, total);
        assertTrue(alive >= 25,
                "a capped store must retain a working set, not evict itself empty; alive=" + alive);
        // ...and must STAY retained with nothing further deposited. The treadmill's
        // signature is a row count that keeps falling while the store is idle.
        Thread.sleep(6_000);
        assertEquals(alive, countAlive(store, total),
                "an idle capped store must stop evicting once under its cap");
        assertEquals(0, store.diagnostics().getErrors());
        store.shutdown();
    }

    private static int countAlive(SqliteLodStore store, int total) {
        int alive = 0;
        for (int i = 0; i < total; i++) {
            if (store.get(OW, PositionUtil.packPosition(i, 12)) != null) alive++;
        }
        return alive;
    }

    /** Cap-behavior §2: the size-cap eviction INFO latches after its FIRST emission —
     *  a capped store evicts as steady state (every ~5 s gauge refresh) and the line
     *  was permanent spam on a treadmilling store. Two observed eviction rounds must
     *  produce exactly one log call (counted via the emission counter — LSSLogger has
     *  no injection seam); the running totals stay observable via the diagnostics
     *  counter behind the status line's evicted= token. */
    @Test
    void sizeCapEvictionLogsExactlyOncePerSession() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        var env = new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE,
                this::regionDir, d -> "", 0, 200_000L); // ~200 KB cap
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        for (int i = 0; i < 40; i++) {
            store.deposit(OW, PositionUtil.packPosition(i, 12), bytes(100 + i, 20_000), 1000 + i);
        }
        long firstRound = 0;
        for (int i = 0; i < 600 && firstRound == 0; i++) { // gauge cadence is 5 s
            firstRound = store.diagnostics().getSqlEvictions();
            Thread.sleep(50);
        }
        assertTrue(firstRound > 0, "first eviction round must fire under the cap");
        // Refill over the cap so a LATER gauge refresh must evict again — a second
        // entry into the exact branch that used to log every time.
        for (int i = 0; i < 40; i++) {
            store.deposit(OW, PositionUtil.packPosition(100 + i, 12), bytes(200 + i, 20_000), 2000 + i);
        }
        long total = firstRound;
        for (int i = 0; i < 600 && total <= firstRound; i++) {
            total = store.diagnostics().getSqlEvictions();
            Thread.sleep(50);
        }
        assertTrue(total > firstRound, "second eviction round must fire after the refill");
        assertEquals(1, store.capLogEmissionCount(),
                "two eviction rounds, ONE cap log line (latched per session)");
        store.shutdown();
    }

    /** C2 (review-fixes round): the sweep judges rows by their ACQUISITION stamp (the
     *  5-arg deposit), not deposit-call time — the R1-M2 property, previously unpinned
     *  (every sweep test used the 4-arg legacy shape = the pre-fix semantics). The
     *  B13 deposit-clears-seen_mtime rule is what makes each reopen re-judge. */
    @Test
    void sweepJudgesRowsByAcquisitionStampNotDepositTime() throws Exception {
        long p = PositionUtil.packPosition(3, 0);
        long headerStamp = nowSec() - 100;
        writeRegion(OW, 0, 0, Map.of(p, (int) headerStamp));
        SqliteLodStore store = open(defaultEnv());
        // Acquired BEFORE the save that stamped the header -> stale at the next sweep.
        store.deposit(OW, p, bytes(1, 64), 1000, headerStamp - 50);
        assertNotNull(awaitHit(store, OW, p));
        store.shutdown();

        SqliteLodStore second = open(defaultEnv()); // boot sweep judges the region
        assertNull(second.get(OW, p), "acq older than the header stamp must sweep");
        // Acquired AFTER the header stamp -> fresh, must survive the next sweep.
        second.deposit(OW, p, bytes(2, 64), 2000, headerStamp + 50);
        assertNotNull(awaitHit(second, OW, p));
        second.shutdown();

        SqliteLodStore third = open(defaultEnv());
        assertNotNull(third.get(OW, p), "acq newer than the header stamp must survive");
        third.shutdown();
    }

    /** C3a: an applied DeleteRows survives a LATER op's rollback — the R1-M1
     *  immediate-commit rule, asserted at raw SQL so the 10 s tombstone window cannot
     *  mask a resurrection. Folding deletes back into the shared txn reds here. */
    @Test
    void appliedDeleteSurvivesALaterOpsRollback() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        long p = PositionUtil.packPosition(5, 0);
        store.deposit(OW, p, bytes(1, 64), 1000);
        assertNotNull(awaitHit(store, OW, p));
        store.invalidate(OW, new long[]{p});
        for (int i = 0; i < 400 && sqlRowCount(OW) > 0; i++) Thread.sleep(25);
        assertEquals(0, sqlRowCount(OW), "the delete must apply");
        store.failNextOpsForTest(1);
        store.deposit(OW, PositionUtil.packPosition(6, 0), bytes(2, 64), 1001); // throws -> rollback
        for (int i = 0; i < 200 && store.diagnostics().getErrors() == 0; i++) Thread.sleep(25);
        assertTrue(store.diagnostics().getErrors() >= 1, "the injected failure must count");
        assertEquals(0, sqlRowCount(OW),
                "the committed delete must survive the later rollback (R1-M1)");
        store.shutdown();
    }

    /** A tombstone must outlive its OWN queued delete, not merely the deposit queue.
     *
     *  <p>The expiry floor used to consider only queued deposits, justified by
     *  "control ops never consult tombstones" — true, but the tombstone's other job is
     *  suppressing READERS until the delete applies. Across a batcher stall longer
     *  than the TTL (a large sweep, a whole-dimension drop, a WAL TRUNCATE, the vacuum
     *  drain) the first resumed iteration applied one delete and then expired every
     *  tombstone whose delete was still queued behind it, so {@code get()} served the
     *  PRE-EDIT row. Silent, and it does not self-heal — the client ingests it and the
     *  position leaves the want-set. (v0.9.0 review.)
     *
     *  <p>Note the fix cannot be an age floor: a tombstone is stamped just BEFORE its
     *  delete is enqueued, so any timestamp derived from the control queue still
     *  expires the tombstone it was meant to protect. It is an identity check. */
    @Test
    void tombstonesOutliveTheirOwnQueuedDeletes() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        int n = 60;
        long[] positions = new long[n];
        for (int i = 0; i < n; i++) {
            positions[i] = PositionUtil.packPosition(i, 5);
            store.deposit(OW, positions[i], bytes(i + 1, 512), 1000 + i);
        }
        assertNotNull(awaitHit(store, OW, positions[n - 1]));

        // Pause the batcher, then invalidate each position SEPARATELY so the control
        // queue holds n distinct DeleteRows: one applies per step, the rest wait.
        var permits = store.pauseBatcherForTest();
        for (long p : positions) store.invalidate(OW, new long[]{p});
        assertEquals(n, store.tombstoneCountForTest(OW), "every invalidate stamps a tombstone");
        Thread.sleep(11_000); // age them past the 10 s TTL while the deletes cannot drain

        // Exactly ONE step: applies delete #1, then runs the (now due) tombstone sweep
        // with n-1 deletes still queued. This is the moment the bug fired.
        permits.release();
        for (int i = 0; i < 400 && sqlRowCount(OW) > n - 1; i++) Thread.sleep(25);
        assertEquals(n - 1, sqlRowCount(OW), "exactly one delete applies per step");

        // The batcher is parked again, so this is a still frame, not a race: every
        // position whose delete is still queued must remain suppressed.
        for (int i = 1; i < n; i++) {
            assertNull(store.get(OW, positions[i]),
                    "a position whose delete is still queued must stay suppressed");
        }
        assertEquals(n - 1, store.tombstoneCountForTest(OW),
                "the sweep must not expire a tombstone whose own delete is still queued");

        permits.release(Integer.MAX_VALUE - 1);
        for (int i = 0; i < 400 && sqlRowCount(OW) > 0; i++) Thread.sleep(25);
        assertEquals(0, sqlRowCount(OW), "every delete must apply");
        assertEquals(0, store.diagnostics().getErrors());
        store.shutdown();
    }

    /** C3b: a DeleteRows whose apply THROWS is re-queued and applies on retry — a
     *  lost delete would resurrect the stale row when its tombstone expires. */
    @Test
    void failedDeleteRequeuesAndAppliesOnRetry() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        long p = PositionUtil.packPosition(7, 0);
        store.deposit(OW, p, bytes(1, 64), 1000);
        assertNotNull(awaitHit(store, OW, p));
        store.failNextOpsForTest(1);
        store.invalidate(OW, new long[]{p});
        for (int i = 0; i < 400 && sqlRowCount(OW) > 0; i++) Thread.sleep(25);
        assertEquals(0, sqlRowCount(OW), "the re-queued delete must apply on retry");
        store.shutdown();
    }

    /** C3c: WRITE_FAILURE_LATCH consecutive writer failures latch the store off —
     *  writes refused, reads miss, and the status surface renders "latched" (review
     *  B1: a dead store must LOOK dead), with isHealthy() false so the backfill's
     *  abort rungs fire. */
    @Test
    void writerFailureStreakLatchesTheStoreOff() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        long p = PositionUtil.packPosition(8, 0);
        store.deposit(OW, p, bytes(1, 64), 1000);
        assertNotNull(awaitHit(store, OW, p));
        assertEquals("ok", store.stateToken(), "a serving store renders ok");
        store.failNextOpsForTest(1000);
        for (int i = 0; i < 25; i++) { // > WRITE_FAILURE_LATCH (20) consecutive failures
            store.deposit(OW, PositionUtil.packPosition(100 + i, 0), bytes(i, 64), 2000 + i);
        }
        for (int i = 0; i < 400 && !"latched".equals(store.stateToken()); i++) Thread.sleep(25);
        assertEquals("latched", store.stateToken(), "the failure streak must latch");
        assertTrue(!store.isHealthy(), "the backfill's health probe must read dead");
        assertNull(store.get(OW, p), "a latched store must read as miss");
        assertTrue(!store.deposit(OW, PositionUtil.packPosition(200, 0), bytes(9, 64), 3000),
                "a latched store must refuse deposits");
        store.shutdown();
    }

    /** C5: cap eviction un-marks the evicted rows' backfill regions (R3-M1's eviction
     *  half, previously unpinned) — and post-B4 the un-mark is durable BEFORE the row
     *  deletes, so a crash between them cannot leave a done-marked hole. */
    @Test
    void sizeCapEvictionUnmarksTheAffectedBackfillRegions() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        var env = new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE,
                this::regionDir, d -> "", 0, 200_000L); // ~200 KB cap
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        for (int i = 0; i < 40; i++) {
            store.deposit(OW, PositionUtil.packPosition(i, 12), bytes(100 + i, 20_000), 1000 + i);
        }
        assertNotNull(awaitHit(store, OW, PositionUtil.packPosition(39, 12)));
        store.markBackfillRegionDone(OW, 0, 0);
        for (int i = 0; i < 400 && !store.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(store.isBackfillRegionDone(OW, 0, 0), "region must be done-marked first");
        for (int i = 0; i < 600 && store.diagnostics().getSqlEvictions() == 0; i++) Thread.sleep(50);
        assertTrue(store.diagnostics().getSqlEvictions() > 0, "eviction must fire under the cap");
        for (int i = 0; i < 400 && store.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(!store.isBackfillRegionDone(OW, 0, 0),
                "eviction must un-mark the affected region (R3-M1's second half)");
        store.shutdown();
    }

    /** Cap-behavior §1: an UNCAPPED store (maxDbBytes = Long.MAX_VALUE — what the
     *  0-config default wires) never enters the eviction arm: rows persist, the
     *  eviction counter stays 0, and the cap line never fires. */
    @Test
    void uncappedStoreNeverEvictsAndNeverLogsTheCapLine() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv()); // no-cap env shape = uncapped
        for (int i = 0; i < 20; i++) {
            store.deposit(OW, PositionUtil.packPosition(i, 12), bytes(100 + i, 20_000), 1000 + i);
        }
        assertNotNull(awaitHit(store, OW, PositionUtil.packPosition(19, 12)));
        // Prove the gauge arm actually RAN before asserting the negative — dbBytes is
        // written only by the gauge refresh.
        for (int i = 0; i < 400 && store.diagnostics().getDbBytes() == 0; i++) Thread.sleep(25);
        assertTrue(store.diagnostics().getDbBytes() > 0, "gauge refresh must have run");
        assertNotNull(store.get(OW, PositionUtil.packPosition(0, 12)),
                "no row may be evicted from an uncapped store");
        assertEquals(0, store.diagnostics().getSqlEvictions());
        assertEquals(0, store.capLogEmissionCount());
        store.shutdown();
    }

    @Test
    void unknownDimensionReadsAsMissWithoutError() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        assertNull(store.get("minecraft:nowhere", PositionUtil.packPosition(0, 0)));
        assertEquals(0, store.diagnostics().getErrors());
        store.shutdown();
    }

    @Test
    void shutdownIsIdempotentAndDepositAfterShutdownIsIgnored() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        SqliteLodStore store = open(defaultEnv());
        store.shutdown();
        store.shutdown();
        store.deposit(OW, PositionUtil.packPosition(0, 9), bytes(24, 100), 100);
        assertNull(store.get(OW, PositionUtil.packPosition(0, 9)));
    }
}
