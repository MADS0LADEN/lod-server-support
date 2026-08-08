package dev.vox.lss.common.store;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The C4 lazy schema 3→4 migration (XVER §5): the from-state matrix (exact v0.9.x
 * state upgrades IN PLACE and keeps every row; dev-era metas and foreign fingerprints
 * drop-and-rebuild — incl. the structural {@code store_layout} guard for the
 * C1..C3-era {@code 4∧20}-without-{@code wirefmt} shape that would otherwise latch
 * the store dead), pre-migration serving (19-rows validate under the LEGACY FNV hash
 * and surface {@code wirefmt=19}), and the background walk (translate + retag with
 * CRC hashes, resumable across restarts via meta — never the version keys — anomaly
 * rows deleted, DropAll resets, completion clears the bookkeeping and the status
 * token). The walk's translator here is a FAKE reversible function — the store treats
 * bodies as opaque; byte-fidelity of the REAL translator is the corpus chain in
 * {@code LegacyColumnEgressTest}, and the serve-rung translation is
 * {@code StoreFrameServingRungTest}'s C4 cases.
 */
class SqliteLodStoreMigrationTest {

    private static final String OW = "minecraft:overworld";
    private static final String FP = "fp-test";
    private static final int WIRE_20 = 20;

    /** The fake walk translator: prefix a marker byte (opaque to the store). */
    private static final UnaryOperator<byte[]> FAKE_TRANSLATOR = raw -> {
        byte[] out = new byte[raw.length + 1];
        out[0] = 0x77;
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    };

    @TempDir
    Path tmp;

    private Path storeDir() {
        return this.tmp.resolve("store");
    }

    private Path regionDir(String dim) {
        return this.tmp.resolve(dim.replace(':', '_')).resolve("region");
    }

    private SqliteLodStore.Environment env20() {
        return new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE_20,
                this::regionDir, d -> "", 0, Long.MAX_VALUE, FP);
    }

    // ---- the schema-3 (released v0.9.x) fixture, built by hand ----

    private record FixtureRow(long pos, byte[] raw, boolean corruptBlob) {}

    private void buildSchema3Store(String fingerprint, List<FixtureRow> rows) throws Exception {
        Files.createDirectories(storeDir());
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        StoreCodec codec = StoreCodec.zstdOrNull();
        assertNotNull(codec, "zstd natives required on the test classpath");
        long now = System.currentTimeMillis() / 1000L;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE dims (id INTEGER PRIMARY KEY, name TEXT UNIQUE,"
                    + " mask_fingerprint TEXT)");
            st.execute("CREATE TABLE regions (dim INTEGER, rpos INTEGER, seen_mtime INTEGER,"
                    + " PRIMARY KEY (dim, rpos)) WITHOUT ROWID");
            st.execute("CREATE TABLE backfill (dim TEXT, rx INTEGER, rz INTEGER, done INTEGER,"
                    + " PRIMARY KEY (dim, rx, rz)) WITHOUT ROWID");
            // The RELEASED v0.9.x lods shape: no wirefmt column, FNV hashes.
            st.execute("CREATE TABLE lods_1 (pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL,"
                    + " chash INTEGER NOT NULL, usize INTEGER NOT NULL,"
                    + " src_stamp INTEGER NOT NULL, fhash INTEGER NOT NULL,"
                    + " blob BLOB NOT NULL)");
            st.execute("CREATE INDEX lods_1_ts ON lods_1 (ts)");
            st.execute("INSERT INTO dims (id, name, mask_fingerprint) VALUES (1, '"
                    + OW + "', '')");
            for (var e : Map.of(
                    "schema_version", "3",
                    "wire_format_version", "19",
                    "mc_version", "26.2-test",
                    "codec", StoreCodec.NAME,
                    "registry_fingerprint", fingerprint).entrySet()) {
                st.execute("INSERT INTO meta (k, v) VALUES ('" + e.getKey() + "', '"
                        + e.getValue() + "')");
            }
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO lods_1 (pos, ts, chash, usize, src_stamp, fhash, blob)"
                             + " VALUES (?,?,?,?,?,?,?)")) {
            for (FixtureRow row : rows) {
                byte[] blob = row.corruptBlob()
                        ? new byte[] {1, 2, 3, 4} // not a zstd frame — the anomaly shape
                        : codec.compress(row.raw());
                ps.setLong(1, row.pos());
                ps.setLong(2, now);
                ps.setLong(3, LodStoreService.legacyContentHashFnv(row.raw()));
                ps.setInt(4, row.raw().length);
                ps.setLong(5, now);
                ps.setLong(6, LodStoreService.legacyContentHashFnv(blob));
                ps.setBytes(7, blob);
                ps.executeUpdate();
            }
        }
        // Region files + recorded seen_mtime so the startup sweep judges "unchanged"
        // and keeps every fixture row (a vanished region would drop them).
        writeRegionsFor(rows, now);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO regions (dim, rpos, seen_mtime) VALUES (1,?,?)")) {
            for (long rpos : rows.stream().map(r -> regionOf(r.pos())).distinct().toList()) {
                int rx = (int) (rpos >> 32);
                int rz = (int) rpos;
                long mtime = Files.getLastModifiedTime(
                        regionDir(OW).resolve("r." + rx + "." + rz + ".mca")).toMillis();
                ps.setLong(1, rpos);
                ps.setLong(2, mtime);
                ps.executeUpdate();
            }
        }
    }

    private static long regionOf(long packedPos) {
        int cx = PositionUtil.unpackX(packedPos);
        int cz = PositionUtil.unpackZ(packedPos);
        return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    }

    private void writeRegionsFor(List<FixtureRow> rows, long stampSec) throws Exception {
        Path dir = regionDir(OW);
        Files.createDirectories(dir);
        var byRegion = new java.util.HashMap<Long, ByteBuffer>();
        for (FixtureRow row : rows) {
            long rpos = regionOf(row.pos());
            ByteBuffer buf = byRegion.computeIfAbsent(rpos, k -> ByteBuffer.allocate(8192));
            int cx = PositionUtil.unpackX(row.pos()) & 31;
            int cz = PositionUtil.unpackZ(row.pos()) & 31;
            int idx = cx + cz * 32;
            buf.putInt(idx * 4, (2 << 8) | 1);
            buf.putInt(4096 + idx * 4, (int) stampSec);
        }
        for (var e : byRegion.entrySet()) {
            int rx = (int) (e.getKey() >> 32);
            int rz = (int) (long) e.getKey();
            Files.write(dir.resolve("r." + rx + "." + rz + ".mca"), e.getValue().array());
        }
    }

    private SqliteLodStore open() throws Exception {
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env20(),
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000), "startup sweep must complete");
        return store;
    }

    private static byte[] raw(int seed, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (seed + i);
        return b;
    }

    // ---- the from-state matrix ----

    @Test
    void exactFromStateUpgradesInPlaceAndServesEveryRowPreMigration() throws Exception {
        long p1 = PositionUtil.packPosition(1, 2);
        long p2 = PositionUtil.packPosition(3, 4);
        buildSchema3Store(FP, List.of(new FixtureRow(p1, raw(10, 900), false),
                new FixtureRow(p2, raw(40, 700), false)));
        var store = open();
        try {
            var hit = store.get(OW, p1);
            assertNotNull(hit, "the lazy upgrade must KEEP the v0.9.x rows — a drop here"
                    + " is the 4 GB store self-destructing");
            assertArrayEquals(raw(10, 900), hit.sectionBytes(),
                    "pre-migration serve: FNV-validated original native bytes");
            assertEquals(19, hit.wirefmt(), "the row surfaces its pre-migration format");
            assertTrue(store.migrationStatusToken().contains("migrating="),
                    "status must show the pending walk, got '"
                            + store.migrationStatusToken() + "'");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void foreignFingerprintSchema3DropsAndRebuilds() throws Exception {
        long p1 = PositionUtil.packPosition(1, 2);
        buildSchema3Store("other-registry", List.of(new FixtureRow(p1, raw(10, 900), false)));
        var store = open();
        try {
            assertNull(store.get(OW, p1),
                    "a fingerprint-drifted schema-3 store must drop (its ids are not"
                            + " decodable against this registry), never upgrade");
            assertEquals("", store.migrationStatusToken());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void devEraFourTwentyMetaWithoutWirefmtColumnDropsViaTheLayoutGuard() throws Exception {
        // The C1..C3-era shape (mega-plan structural guard): meta 4∧20 passes the old
        // equality compare while every wirefmt SELECT/INSERT would throw — without the
        // store_layout key this store opened "valid" and latched dead at
        // WRITE_FAILURE_LATCH, recoverable only by hand-deleting store.db.
        long p1 = PositionUtil.packPosition(1, 2);
        buildSchema3Store(FP, List.of(new FixtureRow(p1, raw(10, 900), false)));
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE meta SET v='4' WHERE k='schema_version'");
            st.execute("UPDATE meta SET v='20' WHERE k='wire_format_version'");
        }
        var store = open();
        try {
            assertNull(store.get(OW, p1),
                    "the store_layout structural guard must drop the no-wirefmt store");
        } finally {
            store.shutdown();
        }
    }

    // ---- the background walk ----

    private static void awaitWalkDone(SqliteLodStore store) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (store.migrationStatusToken().isEmpty()) return;
            Thread.sleep(50);
        }
        fail("walk did not complete; status=" + store.migrationStatusToken());
    }

    @Test
    void walkRetagsRowsWithCrcHashesAndResumesAcrossARestart() throws Exception {
        var rows = new java.util.ArrayList<FixtureRow>();
        for (int i = 0; i < 100; i++) {
            rows.add(new FixtureRow(PositionUtil.packPosition(i, i + 1), raw(i, 600 + i), false));
        }
        buildSchema3Store(FP, rows);

        // Boot 1: translator NOT wired — the walk must WAIT (serves still work), and
        // the pending state must survive the restart via meta, not the version keys.
        var store = open();
        try {
            Thread.sleep(600);
            assertTrue(store.migrationStatusToken().contains("/100"),
                    "unwired translator: the walk waits, pending survives");
            assertNotNull(store.get(OW, rows.get(0).pos()));
        } finally {
            store.shutdown();
        }

        // Boot 2: translator wired — the walk completes, rows re-serve TRANSLATED
        // under CRC validation with wirefmt=20, bookkeeping clears.
        store = open();
        try {
            store.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(store);
            var hit = store.get(OW, rows.get(7).pos());
            assertNotNull(hit);
            assertEquals(20, hit.wirefmt(), "migrated rows carry the v20 tag");
            assertArrayEquals(FAKE_TRANSLATOR.apply(raw(7, 607)), hit.sectionBytes(),
                    "the migrated row serves the TRANSLATED body under CRC validation");
        } finally {
            store.shutdown();
        }

        // Boot 3: nothing pending — no walk, no status token.
        store = open();
        try {
            assertEquals("", store.migrationStatusToken(),
                    "completed bookkeeping must not resurrect a walk");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void walkDeletesAnomalyRowsAndFinishes() throws Exception {
        long good = PositionUtil.packPosition(1, 2);
        long bad = PositionUtil.packPosition(3, 4);
        buildSchema3Store(FP, List.of(new FixtureRow(good, raw(10, 500), false),
                new FixtureRow(bad, raw(20, 500), true)));
        var store = open();
        try {
            store.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(store);
            assertNotNull(store.get(OW, good), "the good row migrates");
            assertNull(store.get(OW, bad),
                    "an unparseable row is DELETED (derived data), never retried forever");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void dropAllResetsTheWalkBookkeeping() throws Exception {
        var rows = new java.util.ArrayList<FixtureRow>();
        for (int i = 0; i < 50; i++) {
            rows.add(new FixtureRow(PositionUtil.packPosition(i, i + 1), raw(i, 600), false));
        }
        buildSchema3Store(FP, rows);
        var store = open();
        try {
            assertTrue(store.migrationStatusToken().contains("migrating="));
            store.requestDropAllRows();
            for (int i = 0; i < 400 && !store.migrationStatusToken().isEmpty(); i++) {
                Thread.sleep(25);
            }
            assertEquals("", store.migrationStatusToken(),
                    "DropAll drops the walk's subject rows — its bookkeeping resets with them");
        } finally {
            store.shutdown();
        }
    }
}
