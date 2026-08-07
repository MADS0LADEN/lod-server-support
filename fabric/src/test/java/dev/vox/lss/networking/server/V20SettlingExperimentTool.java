package dev.vox.lss.networking.server;

import com.github.luben.zstd.Zstd;
import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.wire.NativeToV20Translator;
import dev.vox.lss.common.wire.WireSectionCursor;
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
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * XVER Phase 0 SETTLING MEASUREMENT (§8): encode a real-terrain corpus through BOTH
 * real encoders — the production native path ({@code serializeChunkNbt}) and the v20
 * identity-dictionary translation ({@code NativeToV20Translator} over the real
 * registry tables) — and diff ACTUAL zstd-1 frames. §8's +1,174 B/col raw estimate
 * mixed corpora and used a zlib proxy; these are the numbers the release notes and
 * store-growth statements must cite instead (the plan gates any published size claim
 * on this run).
 *
 * <p>NOT a CI test: skips itself unless {@code -Dlss.store.experiment.regionDir} is
 * set (the store/compressed tools' pass-through — zero gradle changes). §8's corpus
 * is the played test-server world:
 * {@code ./gradlew :fabric:test --tests "*V20SettlingExperimentTool*"
 * -Plss.store.experiment.regionDir=$PWD/test-server/paper/world/dimensions/minecraft/overworld/region
 * -Plss.store.experiment.out=$PWD/profile-results/v20-settling}.
 */
class V20SettlingExperimentTool {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static RegistryAccess buildFullBiomeRegistry() {
        var provider = VanillaRegistries.createLookup();
        var src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

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
    }

    @Test
    void runSettlingMeasurement() throws Exception {
        String regionDirProp = System.getProperty("lss.store.experiment.regionDir");
        assumeTrue(regionDirProp != null && !regionDirProp.isBlank(),
                "settling measurement not requested (-Dlss.store.experiment.regionDir absent)");
        Path regionDir = Path.of(regionDirProp);
        Path outDir = Path.of(System.getProperty("lss.store.experiment.out",
                "profile-results/v20-settling"));
        Files.createDirectories(outDir);
        int maxColumns = Integer.getInteger("lss.store.experiment.maxColumns", Integer.MAX_VALUE);

        RegistryAccess registryAccess = buildFullBiomeRegistry();
        String[] blockIdentities = IdentityTables.blockIdentities();
        var biomeTable = IdentityTables.biomeTable(
                registryAccess.lookupOrThrow(Registries.BIOME));

        Series nativeRaw = new Series(), v20Raw = new Series(), deltaRaw = new Series();
        Series nativeFrame = new Series(), v20Frame = new Series(), deltaFrame = new Series();
        Series dictEntries = new Series(), sectionsPerCol = new Series();
        int columns = 0, allAir = 0, notFull = 0, unparseable = 0, external = 0;
        int translateFailures = 0, reparseFailures = 0;

        List<Path> regionFiles = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            stream.filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted().forEach(regionFiles::add);
        }
        assumeTrue(!regionFiles.isEmpty(), "no region files in " + regionDir);

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

                    byte[] nativeWire = NbtSectionSerializer.serializeChunkNbt(tag, registryAccess);
                    if (nativeWire == null) { notFull++; continue; }
                    columns++;
                    if (nativeWire.length == 0) {
                        allAir++;
                        if (columns >= maxColumns) break outer;
                        continue;
                    }

                    byte[] v20Wire;
                    try {
                        v20Wire = NativeToV20Translator.translate(nativeWire,
                                id -> blockIdentities[id],
                                id -> biomeTable.identities()[id]);
                    } catch (RuntimeException e) {
                        translateFailures++;
                        continue;
                    }
                    try {
                        var column = WireSectionCursor.parse(v20Wire, WireSectionCursor.Layout.V20);
                        dictEntries.add(column.dictionary().size());
                        sectionsPerCol.add(column.sections().size());
                    } catch (RuntimeException e) {
                        reparseFailures++;
                    }

                    byte[] nativeZstd = Zstd.compress(nativeWire, 1);
                    byte[] v20Zstd = Zstd.compress(v20Wire, 1);
                    nativeRaw.add(nativeWire.length);
                    v20Raw.add(v20Wire.length);
                    deltaRaw.add(v20Wire.length - nativeWire.length);
                    nativeFrame.add(nativeZstd.length);
                    v20Frame.add(v20Zstd.length);
                    deltaFrame.add(v20Zstd.length - nativeZstd.length);

                    if (columns >= maxColumns) break outer;
                }
            }
        }

        assumeTrue(columns > 0, "corpus produced no columns");
        var report = new StringBuilder();
        report.append("{\n");
        report.append("  \"columns\": ").append(columns)
                .append(", \"allAir\": ").append(allAir)
                .append(", \"notFull\": ").append(notFull)
                .append(", \"unparseable\": ").append(unparseable)
                .append(", \"external\": ").append(external).append(",\n");
        report.append("  \"translateFailures\": ").append(translateFailures)
                .append(", \"reparseFailures\": ").append(reparseFailures).append(",\n");
        appendSeries(report, "nativeRawBytes", nativeRaw);
        appendSeries(report, "v20RawBytes", v20Raw);
        appendSeries(report, "deltaRawBytes", deltaRaw);
        appendSeries(report, "nativeZstd1Bytes", nativeFrame);
        appendSeries(report, "v20Zstd1Bytes", v20Frame);
        appendSeries(report, "deltaZstd1Bytes", deltaFrame);
        appendSeries(report, "dictEntries", dictEntries);
        appendSeries(report, "sectionsPerColumn", sectionsPerCol);
        report.append("  \"rawGrowthPct\": ")
                .append(pct(nativeRaw.total(), v20Raw.total())).append(",\n");
        report.append("  \"zstd1FrameGrowthPct\": ")
                .append(pct(nativeFrame.total(), v20Frame.total())).append("\n");
        report.append("}\n");
        Files.writeString(outDir.resolve("settling.json"), report);
        System.out.println("[v20-settling] " + columns + " columns ("
                + translateFailures + " translate failures, " + reparseFailures + " reparse failures)");
        System.out.println("[v20-settling] raw: " + Math.round(nativeRaw.mean()) + " -> "
                + Math.round(v20Raw.mean()) + " B/col (" + pct(nativeRaw.total(), v20Raw.total()) + "%)");
        System.out.println("[v20-settling] zstd-1: " + Math.round(nativeFrame.mean()) + " -> "
                + Math.round(v20Frame.mean()) + " B/col (" + pct(nativeFrame.total(), v20Frame.total()) + "%)");
        System.out.println("[v20-settling] report: " + outDir.resolve("settling.json"));
    }

    private static String pct(long base, long changed) {
        return base == 0 ? "0" : String.format("%.2f", 100.0 * (changed - base) / base);
    }

    private static void appendSeries(StringBuilder out, String name, Series s) {
        out.append("  \"").append(name).append("\": {\"mean\": ").append(Math.round(s.mean()))
                .append(", \"p50\": ").append(s.pct(0.50))
                .append(", \"p95\": ").append(s.pct(0.95))
                .append(", \"max\": ").append(s.pct(1.0)).append("},\n");
    }

    // ---- region reading (LodStoreExperimentTool's reader, verbatim) ----

    private static final byte[] EXTERNAL_SENTINEL = new byte[0];

    private static int readBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] readChunkPayload(RandomAccessFile raf, int sectorOffset) throws IOException {
        long pos = (long) sectorOffset * 4096;
        if (pos + 5 > raf.length()) return null;
        raf.seek(pos);
        int declared = raf.readInt();
        if (declared <= 0 || declared > 4 * 1024 * 1024) return null;
        int compressionType = raf.readByte() & 0xFF;
        if ((compressionType & 0x80) != 0) return EXTERNAL_SENTINEL;
        byte[] payload = new byte[declared - 1];
        raf.readFully(payload);
        try {
            return switch (compressionType) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(payload)).readAllBytes();
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(payload)).readAllBytes();
                case 3 -> payload;
                default -> null;
            };
        } catch (IOException e) {
            return null;
        }
    }
}
