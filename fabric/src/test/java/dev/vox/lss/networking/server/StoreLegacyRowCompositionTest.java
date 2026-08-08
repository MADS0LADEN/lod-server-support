package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.ChunkReadResult;
import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import dev.vox.lss.common.store.SqliteLodStore;
import dev.vox.lss.common.store.StoreCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The C4 review's #9: ONE composition test where a REAL native corpus body flows
 * through the REAL stack — a genuine SQLite 19-row (FNV-validated, forged the way the
 * lazy upgrade leaves it) → {@code getFrame} → the serve rung's decompress →
 * {@code NbtSectionSerializer.toV20} against the production registry tables → the
 * delivered result BYTE-EQUALS the committed v20 corpus golden, and {@code fromV20}
 * round-trips it back to the frozen native golden. The unit suites cover each seam
 * with fakes ({@code SqliteLodStoreMigrationTest} treats bodies as opaque,
 * {@code StoreFrameServingRungTest} uses a marker translator); no soak can produce a
 * live 19-row, so this is the one place the real pieces meet.
 */
class StoreLegacyRowCompositionTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String OW = "minecraft:overworld";
    private static final UUID PLAYER = UUID.randomUUID();
    private static RegistryAccess REGISTRY_ACCESS;
    private static StoreCodec codec;

    @BeforeAll
    static void setup() {
        REGISTRY_ACCESS = CorpusRegistryAccess.build();
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd natives required");
    }

    @TempDir
    Path tmp;

    /** {@code LegacyColumnEgressTest.corpusDir}, replicated (private there). */
    private static Path corpusDir(String dirName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources").resolve(dirName);
            }
            Path nested = dir.resolve("fabric");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources").resolve(dirName);
            }
        }
        throw new IllegalStateException("cannot locate the fabric module source tree");
    }

    @Test
    void aRealNativeStoreRowServesTheV20CorpusGoldenThroughTheRealRung() throws Exception {
        byte[] nativeGolden = Files.readAllBytes(corpusDir("nbt-corpus").resolve("multi-palette.bin"));
        byte[] v20Golden = Files.readAllBytes(corpusDir("v20-corpus").resolve("multi-palette.bin"));
        long pos = PositionUtil.packPosition(1, 2);
        Path storeDir = this.tmp.resolve("store");
        Path regionDir = this.tmp.resolve("region");

        // A real store, then forge the deposited row into the exact shape the lazy
        // 3→4 upgrade leaves behind: wirefmt=19, FNV hashes, native-layout body.
        var env = new SqliteLodStore.Environment(storeDir, "26.2-test", 20,
                d -> regionDir, d -> "", 0, Long.MAX_VALUE, "fp-test");
        long stamp = System.currentTimeMillis() / 1000L;
        var store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        assertTrue(store.deposit(OW, pos, nativeGolden, stamp, stamp));
        LodStoreService.StoreHit deposited = null;
        for (int i = 0; i < 400 && deposited == null; i++) {
            deposited = store.get(OW, pos);
            if (deposited == null) Thread.sleep(25);
        }
        assertNotNull(deposited, "deposit must drain");
        store.shutdown();

        forgeRowToLegacy(storeDir, nativeGolden);
        writeRegionAndSeenMtime(storeDir, regionDir, pos, stamp);

        // Reopen: the forged 19-row must survive the sweep and serve through the REAL
        // rung + REAL translator as the byte-exact v20 golden.
        store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        var reader = new AbstractChunkDiskReader(1) {
            void submit(UUID u, int x, int z, String dim, long order,
                        AbstractChunkDiskReader.ReadOperation op) {
                submitRead(u, x, z, dim, order, op);
            }
        };
        try {
            var hit = store.getFrame(OW, pos);
            assertNotNull(hit, "the forged 19-row must FNV-validate and hit");
            assertEquals(19, hit.wirefmt());

            reader.registerPlayer(PLAYER);
            reader.setServeStoreFrames(true);
            reader.attachStore(store);
            reader.setStoreLegacyTranslator(
                    raw -> NbtSectionSerializer.toV20(raw, REGISTRY_ACCESS));
            reader.submit(PLAYER, 1, 2, OW, 1L, () -> {
                throw new AssertionError("the region ladder must not run on a store hit");
            });
            ChunkReadResult result = null;
            long deadline = System.currentTimeMillis() + 5000;
            var q = reader.getPlayerQueue(PLAYER);
            while (result == null && System.currentTimeMillis() < deadline) {
                result = q.poll();
                if (result == null) Thread.onSpinWait();
            }
            assertNotNull(result, "no result within 5s");
            assertTrue(result.fromStore());
            assertNull(result.frameBytes(), "a 19-row delivers RAW, never the stored frame");
            assertArrayEquals(v20Golden, result.sectionBytes(),
                    "store row → rung → REAL toV20 must produce the committed v20 golden");
            assertArrayEquals(nativeGolden,
                    NbtSectionSerializer.fromV20(result.sectionBytes(), REGISTRY_ACCESS),
                    "and the C2 egress direction round-trips it back to the native golden");
        } finally {
            reader.shutdown();
            store.shutdown();
        }
    }

    @Test
    void theMigrationWalkPersistsTheFreshV20EncodeByteExactly() throws Exception {
        // §9's migration golden (C6 — the gap map's item 7): the WALK, driven by the
        // REAL translator over a REAL native body, must persist a row byte-equal to
        // the committed v20 corpus golden with CRC hashes recomputed over exactly
        // those bytes. SqliteLodStoreMigrationTest pins the walk's mechanics against
        // a marker translator (bodies opaque); this is the one place walk + real
        // translator + real bytes meet.
        byte[] nativeGolden = Files.readAllBytes(corpusDir("nbt-corpus").resolve("multi-section.bin"));
        byte[] v20Golden = Files.readAllBytes(corpusDir("v20-corpus").resolve("multi-section.bin"));
        long pos = PositionUtil.packPosition(3, 4);
        Path storeDir = this.tmp.resolve("mstore");
        Path regionDir = this.tmp.resolve("mregion");
        var env = new SqliteLodStore.Environment(storeDir, "26.2-test", 20,
                d -> regionDir, d -> "", 0, Long.MAX_VALUE, "fp-test");
        long stamp = System.currentTimeMillis() / 1000L;
        var store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        assertTrue(store.deposit(OW, pos, nativeGolden, stamp, stamp));
        for (int i = 0; i < 400 && store.get(OW, pos) == null; i++) Thread.sleep(25);
        assertNotNull(store.get(OW, pos), "deposit must drain");
        store.shutdown();

        forgeRowToLegacy(storeDir, nativeGolden);
        armMigrationBookkeeping(storeDir);
        writeRegionAndSeenMtime(storeDir, regionDir, pos, stamp);

        store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        try {
            store.setLegacyMigrationTranslator(
                    raw -> NbtSectionSerializer.toV20(raw, REGISTRY_ACCESS));
            long deadline = System.currentTimeMillis() + 30_000;
            while (!store.migrationStatusToken().isEmpty()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(store.migrationStatusToken().isEmpty(),
                    "walk did not complete: " + store.migrationStatusToken());
        } finally {
            store.shutdown();
        }

        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir.resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT wirefmt, usize, chash, fhash, blob FROM lods_1 WHERE pos=" + pos)) {
            assertTrue(rs.next(), "the migrated row must exist");
            assertEquals(20, rs.getInt(1), "retagged v20");
            assertEquals(v20Golden.length, rs.getInt(2), "usize recomputed for the v20 body");
            byte[] blob = rs.getBytes(5);
            assertArrayEquals(v20Golden, codec.decompress(blob, v20Golden.length),
                    "the persisted body must byte-equal the committed v20 corpus golden");
            assertEquals(LodStoreService.contentHash(v20Golden), rs.getLong(3),
                    "chash recomputed as CRC over exactly the golden bytes");
            assertEquals(LodStoreService.contentHash(blob), rs.getLong(4),
                    "fhash recomputed as CRC over exactly the stored frame");
        }
    }

    /** Arm the walk bookkeeping the lazy upgrade would have written — the forged row
     *  lives in a schema-4 store, so the meta keys must be planted by hand. */
    private void armMigrationBookkeeping(Path storeDir) throws Exception {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir.resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT OR REPLACE INTO meta (k, v) VALUES"
                    + " ('migrate_pending', '1'), ('migrate_total', '1'), ('migrate_done', '0')");
        }
    }

    /** Retag the (single) deposited row to the lazy-upgrade legacy shape: wirefmt 19 +
     *  FNV hashes over the SAME body/frame bytes the deposit wrote. */
    private void forgeRowToLegacy(Path storeDir, byte[] nativeBody) throws Exception {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir.resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            byte[] blob;
            try (ResultSet rs = st.executeQuery("SELECT blob FROM lods_1")) {
                assertTrue(rs.next(), "the deposited row must exist");
                blob = rs.getBytes(1);
            }
            st.executeUpdate("UPDATE lods_1 SET wirefmt=19, chash="
                    + LodStoreService.legacyContentHashFnv(nativeBody) + ", fhash="
                    + LodStoreService.legacyContentHashFnv(blob));
        }
    }

    /** Region file + matching {@code seen_mtime} so the reopen sweep judges the region
     *  unchanged and keeps the forged row (the deposit cleared its seen_mtime). */
    private void writeRegionAndSeenMtime(Path storeDir, Path regionDir, long pos,
                                         long stampSec) throws Exception {
        Files.createDirectories(regionDir);
        int cx = PositionUtil.unpackX(pos);
        int cz = PositionUtil.unpackZ(pos);
        ByteBuffer buf = ByteBuffer.allocate(8192);
        int idx = (cx & 31) + (cz & 31) * 32;
        buf.putInt(idx * 4, (2 << 8) | 1);
        buf.putInt(4096 + idx * 4, (int) stampSec);
        Path mca = regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        Files.write(mca, buf.array());
        long mtime = Files.getLastModifiedTime(mca).toMillis();
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir.resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT OR REPLACE INTO regions (dim, rpos, seen_mtime)"
                    + " VALUES (1, " + regionOf(pos) + ", " + mtime + ")");
        }
    }

    private static long regionOf(long packedPos) {
        int cx = PositionUtil.unpackX(packedPos);
        int cz = PositionUtil.unpackZ(packedPos);
        return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    }
}
