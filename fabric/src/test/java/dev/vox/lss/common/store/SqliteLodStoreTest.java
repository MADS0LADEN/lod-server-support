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
        awaitGone(store, OW, p);
        assertEquals(0, sqlRowCount(OW), "the poisoned row must be purged so it cannot re-fail");
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

    /** Phase 5 size cap: above maxDbBytes the batcher evicts oldest-ts batches and
     *  vacuums; newest rows survive, evicted ones read as misses (re-warm on serve). */
    @Test
    void sizeCapEvictsOldestRowsAndKeepsNewest() throws Exception {
        writeRegion(OW, 0, 0, Map.of());
        var env = new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE,
                this::regionDir, d -> "", 0, 200_000L); // ~200 KB cap
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        // ~40 x 20 KB (incompressible) rows ≈ 800 KB >> the cap; ts ascending.
        for (int i = 0; i < 40; i++) {
            store.deposit(OW, PositionUtil.packPosition(i, 12), bytes(100 + i, 20_000), 1000 + i);
        }
        assertNotNull(awaitHit(store, OW, PositionUtil.packPosition(39, 12)));
        boolean evicted = false;
        for (int i = 0; i < 600 && !evicted; i++) { // gauge cadence is 5 s
            evicted = store.get(OW, PositionUtil.packPosition(0, 12)) == null;
            Thread.sleep(50);
        }
        assertTrue(evicted, "the oldest-ts row must be evicted under the size cap");
        assertNotNull(store.get(OW, PositionUtil.packPosition(39, 12)),
                "the newest row must survive eviction");
        assertEquals(0, store.diagnostics().getErrors());
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
