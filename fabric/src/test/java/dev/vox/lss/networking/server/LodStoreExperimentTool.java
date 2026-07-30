package dev.vox.lss.networking.server;

import com.github.luben.zstd.Zstd;
import com.mojang.serialization.Lifecycle;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LOD-store Phase 0 decision tool (docs/planning/lod-store-implementation-plan.md §1/§2/§5):
 * runs the codec arms (uncompressed / deflate-1 / zstd-1 / LZ4) and the SQLite schema-shape
 * arms (rowid 8k vs 16k pages, WITHOUT ROWID confirmation, mmap A/B) against the REAL
 * benchmark-world corpus, serialized through the production NBT->wire path.
 *
 * <p>NOT a CI test: it skips itself unless {@code -Dlss.store.experiment.regionDir} points
 * at a region directory (invoke via
 * {@code ./gradlew :fabric:test --tests "*LodStoreExperimentTool*"
 * -Plss.store.experiment.regionDir=$PWD/benchmark-worlds/base/world/dimensions/minecraft/overworld/region
 * -Plss.store.experiment.out=$PWD/profile-results/store-experiment}).
 * Results land as JSON + a printed summary; the Phase 0 gate records the codec and
 * page-size decisions from them.
 *
 * <p>Also verifies, incidentally but deliberately, that the region header timestamp table
 * is populated on this world (the §1 freshness design leans on it — a world whose writer
 * left the table zeroed must degrade to startup-sweep-only).
 */
class LodStoreExperimentTool {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    /** Columns processed before timings are recorded (JIT + page-cache warm). */
    private static final int WARMUP_COLUMNS = 3000;
    private static final int POINT_READS = 3000;

    private static RegistryAccess buildFullBiomeRegistry() {
        var provider = VanillaRegistries.createLookup();
        var src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    // ---- timing accumulators ----

    private static final class Series {
        private long[] v = new long[4096];
        private int n;
        void add(long x) {
            if (this.n == this.v.length) this.v = Arrays.copyOf(this.v, this.n * 2);
            this.v[this.n++] = x;
        }
        long total() {
            long s = 0;
            for (int i = 0; i < this.n; i++) s += this.v[i];
            return s;
        }
        double mean() { return this.n == 0 ? 0 : (double) total() / this.n; }
        long pct(double p) {
            if (this.n == 0) return 0;
            long[] c = Arrays.copyOf(this.v, this.n);
            Arrays.sort(c);
            return c[Math.min(this.n - 1, (int) Math.floor(p * this.n))];
        }
        int count() { return this.n; }
    }

    private static final class CodecArm {
        final String name;
        final Series compressNs = new Series();
        final Series decompressNs = new Series();
        long compressedBytes;
        long uncompressedBytes;
        CodecArm(String name) { this.name = name; }
    }

    @Test
    void runExperiment() throws Exception {
        String regionDirProp = System.getProperty("lss.store.experiment.regionDir");
        assumeTrue(regionDirProp != null && !regionDirProp.isBlank(),
                "store experiment not requested (-Dlss.store.experiment.regionDir absent)");
        Path regionDir = Path.of(regionDirProp);
        Path outDir = Path.of(System.getProperty("lss.store.experiment.out",
                "profile-results/store-experiment"));
        Files.createDirectories(outDir);
        int maxColumns = Integer.getInteger("lss.store.experiment.maxColumns", Integer.MAX_VALUE);

        RegistryAccess registryAccess = buildFullBiomeRegistry();

        // Codec arms. deflate-6 rides along as a ratio reference (not a candidate — the
        // plan's candidates are uncompressed / deflate-1 / zstd-1 / LZ4).
        CodecArm deflate1 = new CodecArm("deflate1");
        CodecArm deflate6 = new CodecArm("deflate6");
        CodecArm zstd1 = new CodecArm("zstd1");
        CodecArm lz4 = new CodecArm("lz4-fast");
        List<CodecArm> arms = List.of(deflate1, deflate6, zstd1, lz4);

        LZ4Compressor lz4c = LZ4Factory.fastestInstance().fastCompressor();
        LZ4FastDecompressor lz4d = LZ4Factory.fastestInstance().fastDecompressor();

        // SQLite arms: schema/page-size/codec shape (blob content = deflate-1 unless named).
        Map<String, SqliteArm> dbArms = new LinkedHashMap<>();
        dbArms.put("rowid-8k-deflate1", new SqliteArm(outDir.resolve("rowid-8k-deflate1.db"), 8192, false));
        dbArms.put("rowid-16k-deflate1", new SqliteArm(outDir.resolve("rowid-16k-deflate1.db"), 16384, false));
        dbArms.put("rowid-8k-uncompressed", new SqliteArm(outDir.resolve("rowid-8k-uncompressed.db"), 8192, false));
        dbArms.put("worowid-8k-deflate1", new SqliteArm(outDir.resolve("worowid-8k-deflate1.db"), 8192, true));
        for (SqliteArm arm : dbArms.values()) arm.open();

        Series wireSizes = new Series();
        Series serializeNs = new Series();
        List<Long> keys = new ArrayList<>();
        int columns = 0, allAir = 0, notFull = 0, unparseable = 0, external = 0;
        int zeroTimestampChunks = 0, nonzeroTimestampChunks = 0;
        long regionBytesTotal = 0;

        List<Path> regionFiles = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            stream.filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted().forEach(regionFiles::add);
        }
        assumeTrue(!regionFiles.isEmpty(), "no region files in " + regionDir);

        long startMs = System.currentTimeMillis();
        outer:
        for (Path mca : regionFiles) {
            var m = REGION_NAME.matcher(mca.getFileName().toString());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            regionBytesTotal += Files.size(mca);
            try (RandomAccessFile raf = new RandomAccessFile(mca.toFile(), "r")) {
                if (raf.length() < 8192) continue;
                byte[] header = new byte[8192];
                raf.readFully(header);
                for (int idx = 0; idx < 1024; idx++) {
                    int loc = readBE(header, idx * 4);
                    if (loc == 0) continue;
                    int ts = readBE(header, 4096 + idx * 4);
                    if (ts == 0) zeroTimestampChunks++; else nonzeroTimestampChunks++;
                    int sectorOff = loc >>> 8;
                    byte[] nbtBytes = readChunkPayload(raf, sectorOff);
                    if (nbtBytes == EXTERNAL_SENTINEL) { external++; continue; }
                    if (nbtBytes == null) continue;
                    CompoundTag tag;
                    try (var in = new DataInputStream(new ByteArrayInputStream(nbtBytes))) {
                        tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                    } catch (Exception e) {
                        unparseable++;
                        continue;
                    }
                    int cx = rx * 32 + (idx & 31);
                    int cz = rz * 32 + (idx >>> 5);

                    long t0 = System.nanoTime();
                    byte[] wire = NbtSectionSerializer.serializeChunkNbt(tag, registryAccess);
                    long serNs = System.nanoTime() - t0;
                    if (wire == null) { notFull++; continue; }

                    boolean warm = columns >= WARMUP_COLUMNS;
                    columns++;
                    long packed = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                    keys.add(packed);
                    if (warm) {
                        wireSizes.add(wire.length);
                        serializeNs.add(serNs);
                    }
                    if (wire.length == 0) {
                        allAir++;
                        for (SqliteArm arm : dbArms.values()) arm.insert(packed, ts, wire);
                        if (columns >= maxColumns) break outer;
                        continue;
                    }

                    byte[] d1 = runDeflate(deflate1, wire, 1, warm);
                    runDeflate(deflate6, wire, 6, warm);
                    runZstd(zstd1, wire, warm);
                    runLz4(lz4, lz4c, lz4d, wire, warm);

                    dbArms.get("rowid-8k-deflate1").insert(packed, ts, d1);
                    dbArms.get("rowid-16k-deflate1").insert(packed, ts, d1);
                    dbArms.get("worowid-8k-deflate1").insert(packed, ts, d1);
                    dbArms.get("rowid-8k-uncompressed").insert(packed, ts, wire);
                    if (columns >= maxColumns) break outer;
                }
            }
        }
        for (SqliteArm arm : dbArms.values()) arm.finishInserts();

        // Point-read latency per DB arm (warm page cache; the cold-cache story is the
        // benchmark-level variant, not this tool). Same shuffled key sequence per arm.
        List<Long> sample = new ArrayList<>(keys);
        Collections.shuffle(sample, new Random(42));
        sample = sample.subList(0, Math.min(POINT_READS, sample.size()));
        Map<String, Series> readLatency = new LinkedHashMap<>();
        for (var e : dbArms.entrySet()) {
            readLatency.put(e.getKey(), e.getValue().pointReads(sample));
        }
        // mmap A/B on the 8k deflate arm.
        Series mmapReads = dbArms.get("rowid-8k-deflate1").pointReadsMmap(sample, 1L << 30);
        Map<String, Object> dbStats = new LinkedHashMap<>();
        for (var e : dbArms.entrySet()) {
            dbStats.put(e.getKey(), e.getValue().stats());
            e.getValue().close();
        }

        // ---- report ----
        var json = new LinkedHashMap<String, Object>();
        json.put("generated", java.time.Instant.now().toString());
        json.put("regionDir", regionDir.toString());
        json.put("regionFiles", regionFiles.size());
        json.put("regionBytesTotal", regionBytesTotal);
        json.put("columns", columns);
        json.put("allAir", allAir);
        json.put("notFullOrUnservable", notFull);
        json.put("unparseable", unparseable);
        json.put("externalMcc", external);
        json.put("headerTimestamps", Map.of(
                "nonzero", nonzeroTimestampChunks, "zero", zeroTimestampChunks));
        json.put("wireBytesPerColumn", seriesStats(wireSizes, 1.0, "bytes"));
        json.put("serializeUsPerColumn", seriesStats(serializeNs, 1e-3, "us"));
        var codecJson = new LinkedHashMap<String, Object>();
        for (CodecArm arm : arms) {
            var a = new LinkedHashMap<String, Object>();
            a.put("ratio", arm.uncompressedBytes == 0 ? 0
                    : round2((double) arm.uncompressedBytes / arm.compressedBytes));
            a.put("bytesPerCol", arm.decompressNs.count() == 0 ? 0
                    : arm.compressedBytes / Math.max(1, arm.compressNs.count()));
            a.put("compressUs", seriesStats(arm.compressNs, 1e-3, "us"));
            a.put("decompressUs", seriesStats(arm.decompressNs, 1e-3, "us"));
            a.put("decompressMBps", arm.decompressNs.total() == 0 ? 0
                    : round2(arm.uncompressedBytes / (arm.decompressNs.total() / 1e9) / 1e6));
            codecJson.put(arm.name, a);
        }
        json.put("codecs", codecJson);
        var readJson = new LinkedHashMap<String, Object>();
        for (var e : readLatency.entrySet()) {
            readJson.put(e.getKey(), seriesStats(e.getValue(), 1e-3, "us"));
        }
        readJson.put("rowid-8k-deflate1+mmap1g", seriesStats(mmapReads, 1e-3, "us"));
        json.put("sqlitePointReadUs", readJson);
        json.put("sqliteArms", dbStats);

        String jsonText = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json);
        Path outFile = outDir.resolve("store-experiment.json");
        Files.writeString(outFile, jsonText);
        System.out.println("=== LOD store Phase 0 experiment (" +
                (System.currentTimeMillis() - startMs) / 1000 + "s) ===");
        System.out.println(jsonText);
        System.out.println("=== written to " + outFile + " ===");
    }

    private static double round2(double d) { return Math.round(d * 100.0) / 100.0; }

    private static Map<String, Object> seriesStats(Series s, double scale, String unit) {
        var m = new LinkedHashMap<String, Object>();
        m.put("n", s.count());
        m.put("mean_" + unit, round2(s.mean() * scale));
        m.put("p50_" + unit, round2(s.pct(0.50) * scale));
        m.put("p95_" + unit, round2(s.pct(0.95) * scale));
        m.put("p99_" + unit, round2(s.pct(0.99) * scale));
        return m;
    }

    // ---- codec arms ----

    private static byte[] runDeflate(CodecArm arm, byte[] wire, int level, boolean warm) {
        long t0 = System.nanoTime();
        Deflater d = new Deflater(level);
        d.setInput(wire);
        d.finish();
        byte[] buf = new byte[wire.length + 64];
        int total = 0;
        while (!d.finished()) {
            if (total == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            total += d.deflate(buf, total, buf.length - total);
        }
        d.end();
        byte[] compressed = Arrays.copyOf(buf, total);
        long t1 = System.nanoTime();
        byte[] out = new byte[wire.length];
        try {
            Inflater inf = new Inflater();
            inf.setInput(compressed);
            int n = 0;
            while (n < out.length && !inf.finished()) {
                n += inf.inflate(out, n, out.length - n);
            }
            inf.end();
            if (n != wire.length) throw new IllegalStateException("inflate size mismatch");
        } catch (java.util.zip.DataFormatException e) {
            throw new RuntimeException(e);
        }
        long t2 = System.nanoTime();
        if (warm) {
            arm.compressNs.add(t1 - t0);
            arm.decompressNs.add(t2 - t1);
            arm.compressedBytes += compressed.length;
            arm.uncompressedBytes += wire.length;
        }
        return compressed;
    }

    private static void runZstd(CodecArm arm, byte[] wire, boolean warm) {
        long t0 = System.nanoTime();
        byte[] compressed = Zstd.compress(wire, 1);
        long t1 = System.nanoTime();
        byte[] out = Zstd.decompress(compressed, wire.length);
        long t2 = System.nanoTime();
        if (out.length != wire.length) throw new IllegalStateException("zstd size mismatch");
        if (warm) {
            arm.compressNs.add(t1 - t0);
            arm.decompressNs.add(t2 - t1);
            arm.compressedBytes += compressed.length;
            arm.uncompressedBytes += wire.length;
        }
    }

    private static void runLz4(CodecArm arm, LZ4Compressor c, LZ4FastDecompressor d,
                               byte[] wire, boolean warm) {
        long t0 = System.nanoTime();
        byte[] compressed = c.compress(wire);
        long t1 = System.nanoTime();
        byte[] out = d.decompress(compressed, wire.length);
        long t2 = System.nanoTime();
        if (out.length != wire.length) throw new IllegalStateException("lz4 size mismatch");
        if (warm) {
            arm.compressNs.add(t1 - t0);
            arm.decompressNs.add(t2 - t1);
            arm.compressedBytes += compressed.length;
            arm.uncompressedBytes += wire.length;
        }
    }

    // ---- region payload read ----

    private static final byte[] EXTERNAL_SENTINEL = new byte[0];

    private static byte[] readChunkPayload(RandomAccessFile raf, int sectorOff) throws IOException {
        long at = (long) sectorOff * 4096;
        if (at + 5 > raf.length()) return null;
        raf.seek(at);
        int length = raf.readInt();
        if (length <= 0 || at + 4 + length > raf.length()) return null;
        int compression = raf.readByte() & 0xFF;
        if ((compression & 0x80) != 0) return EXTERNAL_SENTINEL; // .mcc external chunk
        byte[] data = new byte[length - 1];
        raf.readFully(data);
        try {
            return switch (compression) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(data)).readAllBytes();
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(data)).readAllBytes();
                case 3 -> data;
                default -> null; // 4 = LZ4 (unused by vanilla writers we target), 127 = custom
            };
        } catch (IOException e) {
            return null;
        }
    }

    private static int readBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    // ---- SQLite arm ----

    private static final class SqliteArm {
        final Path file;
        final int pageSize;
        final boolean withoutRowid;
        Connection conn;
        PreparedStatement insert;
        int batch;

        SqliteArm(Path file, int pageSize, boolean withoutRowid) {
            this.file = file;
            this.pageSize = pageSize;
            this.withoutRowid = withoutRowid;
        }

        void open() throws SQLException, IOException {
            Files.deleteIfExists(this.file);
            Files.deleteIfExists(this.file.resolveSibling(this.file.getFileName() + "-wal"));
            Files.deleteIfExists(this.file.resolveSibling(this.file.getFileName() + "-shm"));
            // NOT DriverManager: under Fabric's Knot classloader the ServiceLoader-registered
            // driver is invisible to java.sql.DriverManager ("No suitable driver") — the
            // DataSource route is classloader-clean. The production store must do the same.
            var ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + this.file);
            this.conn = ds.getConnection();
            try (Statement st = this.conn.createStatement()) {
                st.execute("PRAGMA page_size=" + this.pageSize);
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE lods (pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL,"
                        + " chash INTEGER NOT NULL, usize INTEGER NOT NULL,"
                        + " src_stamp INTEGER NOT NULL, blob BLOB NOT NULL)"
                        + (this.withoutRowid ? " WITHOUT ROWID" : ""));
            }
            this.conn.setAutoCommit(false);
            this.insert = this.conn.prepareStatement(
                    "INSERT OR REPLACE INTO lods (pos, ts, chash, usize, src_stamp, blob)"
                            + " VALUES (?,?,?,?,?,?)");
        }

        void insert(long pos, int srcStamp, byte[] blob) throws SQLException {
            this.insert.setLong(1, pos);
            this.insert.setLong(2, srcStamp);          // ts: header stamp stands in
            this.insert.setLong(3, fnv1a(blob));
            this.insert.setLong(4, blob.length);
            this.insert.setLong(5, srcStamp);
            this.insert.setBytes(6, blob);
            this.insert.executeUpdate();
            if (++this.batch >= 64) {
                this.conn.commit();
                this.batch = 0;
            }
        }

        void finishInserts() throws SQLException {
            this.conn.commit();
            try (Statement st = this.conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            this.conn.commit();
        }

        Series pointReads(List<Long> sample) throws SQLException {
            Series s = new Series();
            try (PreparedStatement ps = this.conn.prepareStatement(
                    "SELECT ts, chash, usize, src_stamp, blob FROM lods WHERE pos=?")) {
                // warmup pass
                for (int i = 0; i < Math.min(500, sample.size()); i++) {
                    readOne(ps, sample.get(i));
                }
                for (long pos : sample) {
                    long t0 = System.nanoTime();
                    readOne(ps, pos);
                    s.add(System.nanoTime() - t0);
                }
            }
            return s;
        }

        Series pointReadsMmap(List<Long> sample, long mmapBytes) throws SQLException {
            try (Statement st = this.conn.createStatement()) {
                st.execute("PRAGMA mmap_size=" + mmapBytes);
            }
            Series s = pointReads(sample);
            try (Statement st = this.conn.createStatement()) {
                st.execute("PRAGMA mmap_size=0");
            }
            return s;
        }

        private void readOne(PreparedStatement ps, long pos) throws SQLException {
            ps.setLong(1, pos);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    rs.getLong(1);
                    byte[] b = rs.getBytes(5);
                    if (b == null) throw new IllegalStateException("null blob");
                }
            }
        }

        Map<String, Object> stats() throws SQLException, IOException {
            var m = new LinkedHashMap<String, Object>();
            m.put("fileBytes", Files.size(this.file));
            try (Statement st = this.conn.createStatement()) {
                try (var rs = st.executeQuery("PRAGMA page_count")) {
                    rs.next();
                    m.put("pageCount", rs.getLong(1));
                }
                // dbstat needs SQLITE_ENABLE_DBSTAT_VTAB in the bundled build — detect.
                try (var rs = st.executeQuery(
                        "SELECT sum(pageno IS NOT NULL), sum(ncell), sum(payload),"
                                + " sum(unused), max(mx_payload)"
                                + " FROM dbstat WHERE name='lods'")) {
                    if (rs.next()) {
                        m.put("dbstat", Map.of(
                                "pages", rs.getLong(1),
                                "cells", rs.getLong(2),
                                "payload", rs.getLong(3),
                                "unused", rs.getLong(4),
                                "maxPayload", rs.getLong(5)));
                    }
                } catch (SQLException e) {
                    m.put("dbstat", "unavailable: " + e.getMessage());
                }
                try (var rs = st.executeQuery(
                        "SELECT count(*) FROM dbstat WHERE name='lods' AND pagetype='overflow'")) {
                    if (rs.next()) m.put("overflowPages", rs.getLong(1));
                } catch (SQLException e) {
                    // dbstat unavailable — already recorded above
                }
            }
            return m;
        }

        void close() throws SQLException {
            this.insert.close();
            this.conn.close();
        }

        private static long fnv1a(byte[] data) {
            long hash = 0xcbf29ce484222325L;
            for (byte b : data) {
                hash ^= (b & 0xFF);
                hash *= 0x100000001b3L;
            }
            return hash;
        }
    }
}
