package dev.vox.lss.networking.server;

import com.github.luben.zstd.Zstd;
import com.mojang.serialization.Lifecycle;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compressed-columns Phase 0 premise tool
 * (docs/planning/compressed-columns-implementation-plan.md §1): measures, on the REAL
 * benchmark-world corpus through the production NBT->wire path, what protocol-19 zstd
 * column shipping costs and saves versus today's raw-plus-netty-deflate wire:
 *
 * <ul>
 *   <li>OFF arm (today): deflate-6 over raw wire bytes — netty's cost server-side,
 *       inflate client-side, and the actual bytes shipped;</li>
 *   <li>ON arm (live/disk/gen serve): zstd-1 compress + deflate-6 over the FRAME
 *       (netty still wraps codec-1 payloads) — and the wrapped size, which per the
 *       store codec table is EXPECTED to exceed the OFF wire (~+5-12%, review B1);</li>
 *   <li>ON arm (store hit): the zstd compress is sunk — only the frame deflate remains;</li>
 *   <li>client ON: inflate(frame wrap) + zstd decompress;</li>
 *   <li>per-raw-size-bucket frame-vs-raw behaviour — the COLUMN_COMPRESS_MIN_BYTES pick;</li>
 *   <li>{@code Zstd.getFrameContentSize} == raw length on EVERY frame our single-shot
 *       {@code Zstd.compress(raw, 1)} emits — the bomb-guard/gauge premise (violations
 *       must be zero);</li>
 *   <li>derived G1 margin inputs and the G3 wire ceiling for compress_gate_check.py.</li>
 * </ul>
 *
 * <p>NOT a CI test: skips itself unless {@code -Dlss.store.experiment.regionDir} points at
 * a region directory (the same pass-through the store Phase 0 tool uses — zero gradle
 * changes). Invoke via
 * {@code ./gradlew :fabric:test --tests "*CompressedColumnsExperimentTool*"
 * -Plss.store.experiment.regionDir=$PWD/benchmark-worlds/base/world/dimensions/minecraft/overworld/region
 * -Plss.store.experiment.out=$PWD/profile-results/compressed-columns-experiment}.
 */
class CompressedColumnsExperimentTool {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    /** Columns processed before timings are recorded (JIT + page-cache warm). */
    private static final int WARMUP_COLUMNS = Integer.getInteger("lss.store.experiment.warmup", 3000);

    private static RegistryAccess buildFullBiomeRegistry() {
        var provider = VanillaRegistries.createLookup();
        var src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    // ---- timing accumulators (LodStoreExperimentTool's Series, verbatim) ----

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

    /** One (compress-cost, decompress-cost, sizes) accumulator. */
    private static final class Arm {
        final String name;
        final Series compressNs = new Series();
        final Series decompressNs = new Series();
        long inBytes;
        long outBytes;
        Arm(String name) { this.name = name; }
        Map<String, Object> report() {
            var a = new LinkedHashMap<String, Object>();
            a.put("n", this.compressNs.count());
            a.put("inBytesPerCol", this.compressNs.count() == 0 ? 0
                    : this.inBytes / this.compressNs.count());
            a.put("outBytesPerCol", this.compressNs.count() == 0 ? 0
                    : this.outBytes / this.compressNs.count());
            a.put("ratio", this.outBytes == 0 ? 0 : round2((double) this.inBytes / this.outBytes));
            a.put("compressUs", seriesStats(this.compressNs, 1e-3, "us"));
            a.put("decompressUs", seriesStats(this.decompressNs, 1e-3, "us"));
            return a;
        }
    }

    /** Raw-size bucket for the threshold pick: how do small columns behave? */
    private static final class Bucket {
        final int lo, hi; // raw size range [lo, hi)
        int n;
        int frameNotSmaller;   // zstd frame >= raw (the codec-0 fallback candidates)
        long rawBytes, frameBytes;
        Bucket(int lo, int hi) { this.lo = lo; this.hi = hi; }
        Map<String, Object> report() {
            var m = new LinkedHashMap<String, Object>();
            m.put("range", this.lo + ".." + (this.hi == Integer.MAX_VALUE ? "inf" : this.hi));
            m.put("n", this.n);
            m.put("frameNotSmaller", this.frameNotSmaller);
            m.put("meanRaw", this.n == 0 ? 0 : this.rawBytes / this.n);
            m.put("meanFrame", this.n == 0 ? 0 : this.frameBytes / this.n);
            m.put("frameOverRaw", this.rawBytes == 0 ? 0
                    : round2((double) this.frameBytes / this.rawBytes));
            return m;
        }
    }

    @Test
    void runExperiment() throws Exception {
        String regionDirProp = System.getProperty("lss.store.experiment.regionDir");
        assumeTrue(regionDirProp != null && !regionDirProp.isBlank(),
                "compressed-columns experiment not requested (-Dlss.store.experiment.regionDir absent)");
        Path regionDir = Path.of(regionDirProp);
        Path outDir = Path.of(System.getProperty("lss.store.experiment.out",
                "profile-results/compressed-columns-experiment"));
        Files.createDirectories(outDir);
        int maxColumns = Integer.getInteger("lss.store.experiment.maxColumns", Integer.MAX_VALUE);

        RegistryAccess registryAccess = buildFullBiomeRegistry();

        // OFF arm: netty deflate-6 over raw (vanilla CompressionEncoder is `new Deflater()`
        // = level 6 zlib). ON arm live: zstd-1 then deflate-6 over the frame. ON arm store
        // hit: frame deflate only (the zstd cost is sunk at deposit).
        Arm offDeflateRaw = new Arm("deflate6-raw");        // today's netty pass
        Arm onZstd = new Arm("zstd1-raw");                  // ON: the new compress
        Arm onDeflateFrame = new Arm("deflate6-frame");     // ON: netty's pass over the frame

        Bucket[] buckets = {
                new Bucket(1, 256), new Bucket(256, 512), new Bucket(512, 1024),
                new Bucket(1024, 2048), new Bucket(2048, 4096),
                new Bucket(4096, Integer.MAX_VALUE),
        };

        Series wireSizes = new Series();
        int columns = 0, allAir = 0, notFull = 0, unparseable = 0, external = 0;
        int frameSizeViolations = 0;

        List<Path> regionFiles = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            stream.filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted().forEach(regionFiles::add);
        }
        assumeTrue(!regionFiles.isEmpty(), "no region files in " + regionDir);

        long startMs = System.currentTimeMillis();
        outer:
        for (Path mca : regionFiles) {
            try (RandomAccessFile raf = new RandomAccessFile(mca.toFile(), "r")) {
                if (raf.length() < 8192) continue;
                byte[] header = new byte[8192];
                raf.readFully(header);
                for (int idx = 0; idx < 1024; idx++) {
                    int loc = readBE(header, idx * 4);
                    if (loc == 0) continue;
                    byte[] nbtBytes = readChunkPayload(raf, loc >>> 8);
                    if (nbtBytes == EXTERNAL_SENTINEL) { external++; continue; }
                    if (nbtBytes == null) continue;
                    CompoundTag tag;
                    try (var in = new DataInputStream(new ByteArrayInputStream(nbtBytes))) {
                        tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                    } catch (Exception e) {
                        unparseable++;
                        continue;
                    }

                    byte[] wire = NbtSectionSerializer.serializeChunkNbt(tag, registryAccess);
                    if (wire == null) { notFull++; continue; }
                    boolean warm = columns >= WARMUP_COLUMNS;
                    columns++;
                    if (wire.length == 0) {
                        allAir++;
                        if (columns >= maxColumns) break outer;
                        continue;
                    }
                    if (warm) wireSizes.add(wire.length);

                    // OFF arm: deflate-6(raw) + inflate — cost and shipped size.
                    runDeflateInflate(offDeflateRaw, wire, warm);

                    // ON arm: zstd-1(raw) [+ decompress] then deflate-6(frame) [+ inflate].
                    byte[] frame = runZstd(onZstd, wire, warm);
                    runDeflateInflate(onDeflateFrame, frame, warm);

                    // Bomb-guard premise: single-shot frames always declare their content size.
                    if (Zstd.getFrameContentSize(frame) != wire.length) frameSizeViolations++;

                    // Threshold buckets (every column, warm or not — size stats have no JIT).
                    for (Bucket b : buckets) {
                        if (wire.length >= b.lo && wire.length < b.hi) {
                            b.n++;
                            b.rawBytes += wire.length;
                            b.frameBytes += frame.length;
                            if (frame.length >= wire.length) b.frameNotSmaller++;
                            break;
                        }
                    }
                    if (columns >= maxColumns) break outer;
                }
            }
        }

        // ---- derived plan inputs (§5.2 G1 margin / G3 ceiling) ----
        // Per-column server-side CPU, warm store-hit serving:
        //   OFF: zstd decompress (store get) + deflate6(raw)          [today]
        //   ON : deflate6(frame)                                      [frame verbatim]
        // Live/disk/gen serving:
        //   OFF: deflate6(raw)
        //   ON : zstd1 compress + deflate6(frame)
        // Client side:
        //   OFF: inflate(deflate6(raw))
        //   ON : inflate(deflate6(frame)) + zstd decompress
        var derived = new LinkedHashMap<String, Object>();
        double offStoreHitUs = us(onZstd.decompressNs) + us(offDeflateRaw.compressNs);
        double onStoreHitUs = us(onDeflateFrame.compressNs);
        double offLiveUs = us(offDeflateRaw.compressNs);
        double onLiveUs = us(onZstd.compressNs) + us(onDeflateFrame.compressNs);
        double offClientUs = us(offDeflateRaw.decompressNs);
        double onClientUs = us(onDeflateFrame.decompressNs) + us(onZstd.decompressNs);
        derived.put("serverStoreHitUs", Map.of("off", round2(offStoreHitUs), "on", round2(onStoreHitUs),
                "savedUs", round2(offStoreHitUs - onStoreHitUs)));
        derived.put("serverLiveServeUs", Map.of("off", round2(offLiveUs), "on", round2(onLiveUs),
                "savedUs", round2(offLiveUs - onLiveUs)));
        derived.put("clientUs", Map.of("off", round2(offClientUs), "on", round2(onClientUs),
                "savedUs", round2(offClientUs - onClientUs)));
        derived.put("wireBytesPerCol", Map.of(
                "off", offDeflateRaw.compressNs.count() == 0 ? 0
                        : offDeflateRaw.outBytes / offDeflateRaw.compressNs.count(),
                "on", onDeflateFrame.compressNs.count() == 0 ? 0
                        : onDeflateFrame.outBytes / onDeflateFrame.compressNs.count(),
                "onOverOff_G3", offDeflateRaw.outBytes == 0 ? 0
                        : round2((double) onDeflateFrame.outBytes / offDeflateRaw.outBytes)));

        var json = new LinkedHashMap<String, Object>();
        json.put("generated", java.time.Instant.now().toString());
        json.put("regionDir", regionDir.toString());
        json.put("regionFiles", regionFiles.size());
        json.put("columns", columns);
        json.put("allAir", allAir);
        json.put("notFullOrUnservable", notFull);
        json.put("unparseable", unparseable);
        json.put("externalMcc", external);
        json.put("frameContentSizeViolations", frameSizeViolations);
        json.put("rawWireBytesPerColumn", seriesStats(wireSizes, 1.0, "bytes"));
        json.put("arms", Map.of(
                offDeflateRaw.name, offDeflateRaw.report(),
                onZstd.name, onZstd.report(),
                onDeflateFrame.name, onDeflateFrame.report()));
        var bucketJson = new ArrayList<Map<String, Object>>();
        for (Bucket b : buckets) bucketJson.add(b.report());
        json.put("thresholdBuckets", bucketJson);
        json.put("derived", derived);

        String jsonText = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json);
        Path outFile = outDir.resolve("compressed-columns-experiment.json");
        Files.writeString(outFile, jsonText);
        System.out.println("=== compressed-columns Phase 0 experiment ("
                + (System.currentTimeMillis() - startMs) / 1000 + "s) ===");
        System.out.println(jsonText);
        System.out.println("=== written to " + outFile + " ===");
    }

    private static double us(Series s) { return s.mean() * 1e-3; }

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

    /** Deflate-6 (zlib, vanilla netty's `new Deflater()`) + matching inflate. */
    private static byte[] runDeflateInflate(Arm arm, byte[] input, boolean warm) {
        long t0 = System.nanoTime();
        Deflater d = new Deflater(6);
        d.setInput(input);
        d.finish();
        byte[] buf = new byte[input.length + 64];
        int total = 0;
        while (!d.finished()) {
            if (total == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            total += d.deflate(buf, total, buf.length - total);
        }
        d.end();
        byte[] compressed = Arrays.copyOf(buf, total);
        long t1 = System.nanoTime();
        byte[] out = new byte[input.length];
        try {
            Inflater inf = new Inflater();
            inf.setInput(compressed);
            int n = 0;
            while (n < out.length && !inf.finished()) {
                n += inf.inflate(out, n, out.length - n);
            }
            inf.end();
            if (n != input.length) throw new IllegalStateException("inflate size mismatch");
        } catch (java.util.zip.DataFormatException e) {
            throw new RuntimeException(e);
        }
        long t2 = System.nanoTime();
        if (warm) {
            arm.compressNs.add(t1 - t0);
            arm.decompressNs.add(t2 - t1);
            arm.inBytes += input.length;
            arm.outBytes += compressed.length;
        }
        return compressed;
    }

    private static byte[] runZstd(Arm arm, byte[] wire, boolean warm) {
        long t0 = System.nanoTime();
        byte[] compressed = Zstd.compress(wire, 1);
        long t1 = System.nanoTime();
        byte[] out = Zstd.decompress(compressed, wire.length);
        long t2 = System.nanoTime();
        if (out.length != wire.length) throw new IllegalStateException("zstd size mismatch");
        if (warm) {
            arm.compressNs.add(t1 - t0);
            arm.decompressNs.add(t2 - t1);
            arm.inBytes += wire.length;
            arm.outBytes += compressed.length;
        }
        return compressed;
    }

    // ---- region payload read (LodStoreExperimentTool, verbatim) ----

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
                default -> null;
            };
        } catch (IOException e) {
            return null;
        }
    }

    private static int readBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
