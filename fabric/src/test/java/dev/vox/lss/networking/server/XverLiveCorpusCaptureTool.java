package dev.vox.lss.networking.server;

import com.mojang.serialization.Lifecycle;
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
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * §9's cross-line fixture-corpus CAPTURE (C6, review MAJOR — "closes the only-manual
 * gap"): walks a REAL 26.2 world's region files through the production serialize path
 * (which emits the canonical v20 body since C1's translate-at-producer) and checks the
 * selected columns in under {@code src/test/resources/xver-live-corpus/}. The 26.2-side
 * {@link XverLiveCorpusDecodeTest} pins that every fixture decodes strictly against
 * THIS line's registries; the 26.1 / 1.21.11 backport suites (D3 phase 7) decode the
 * SAME fixtures against THEIR registries — identity-level content, exact fallback
 * counts — which is the only automated coverage the actual issue-#85 scenario has.
 *
 * <p>NOT a CI test: skips unless {@code -Dlss.xver.captureRegionDir} is set. Run:
 * <pre>
 * ./gradlew :fabric:test --tests XverLiveCorpusCaptureTool --rerun \
 *   -Plss.xver.captureRegionDir=$PWD/test-server/paper/world/dimensions/minecraft/overworld/region
 * </pre>
 * Selection is DETERMINISTIC (sorted region files, first qualifying columns, at most
 * {@code PER_REGION} per region for spread) so a re-run over the same world reproduces
 * the same fixtures; re-capturing over a different world replaces them wholesale (they
 * are goldens thereafter — treat a diff as a deliberate re-baseline).
 */
class XverLiveCorpusCaptureTool {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int TARGET_COLUMNS = 12;
    private static final int PER_REGION = 2;
    /** Only meaty terrain qualifies: a rich dictionary is what exercises the backport
     *  lines' resolvers (an all-stone column proves almost nothing). */
    private static final int MIN_DICT_ENTRIES = 40;

    @Test
    void captureLiveCorpus() throws Exception {
        String regionDirProp = System.getProperty("lss.xver.captureRegionDir");
        assumeTrue(regionDirProp != null && !regionDirProp.isBlank(),
                "corpus capture not requested (-Dlss.xver.captureRegionDir absent)");
        Path regionDir = Path.of(regionDirProp);
        Path outDir = Path.of("src/test/resources/xver-live-corpus");
        Files.createDirectories(outDir);

        RegistryAccess registryAccess = buildFullBiomeRegistry();
        List<Path> regionFiles = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            stream.filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted().forEach(regionFiles::add);
        }
        assumeTrue(!regionFiles.isEmpty(), "no region files in " + regionDir);

        int captured = 0;
        var manifest = new StringBuilder("# xver-live-corpus manifest — captured from "
                + regionDir + "\n# column | bytes | dictionary entries | sections\n");
        outer:
        for (Path mca : regionFiles) {
            int fromThisRegion = 0;
            try (RandomAccessFile raf = new RandomAccessFile(mca.toFile(), "r")) {
                if (raf.length() < 8192) continue;
                byte[] header = new byte[8192];
                raf.readFully(header);
                for (int idx = 0; idx < 1024 && fromThisRegion < PER_REGION; idx++) {
                    int loc = readBE(header, idx * 4);
                    if (loc == 0) continue;
                    byte[] nbtBytes = readChunkPayload(raf, loc >>> 8);
                    if (nbtBytes == null || nbtBytes.length == 0) continue;
                    CompoundTag tag;
                    try (var in = new DataInputStream(new ByteArrayInputStream(nbtBytes))) {
                        tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                    } catch (Exception e) {
                        continue;
                    }
                    byte[] v20 = NbtSectionSerializer.serializeChunkNbt(tag, registryAccess);
                    if (v20 == null || v20.length == 0) continue;
                    WireSectionCursor.WireColumn column;
                    try {
                        column = WireSectionCursor.parse(v20, WireSectionCursor.Layout.V20);
                    } catch (RuntimeException e) {
                        throw new AssertionError("production serialize emitted an unparseable"
                                + " v20 body — capture aborted (" + mca + " idx " + idx + ")", e);
                    }
                    if (column.dictionary().size() < MIN_DICT_ENTRIES) continue;
                    String name = String.format("col-%02d.bin", captured);
                    Files.write(outDir.resolve(name), v20);
                    manifest.append(name).append(" | ").append(v20.length).append(" | ")
                            .append(column.dictionary().size()).append(" | ")
                            .append(column.sections().size()).append('\n');
                    captured++;
                    fromThisRegion++;
                    if (captured >= TARGET_COLUMNS) break outer;
                }
            }
        }
        Files.writeString(outDir.resolve("MANIFEST.txt"), manifest.toString());
        System.out.println("[xver-corpus] captured " + captured + " columns into " + outDir);
        assumeTrue(captured == TARGET_COLUMNS,
                "capture found only " + captured + "/" + TARGET_COLUMNS
                        + " qualifying columns — pick a richer world");
    }

    private static RegistryAccess buildFullBiomeRegistry() {
        var provider = VanillaRegistries.createLookup();
        var src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    private static int readBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** Region chunk payload, zlib branch only (external/.mcc and other codecs skipped —
     *  a capture tool may be choosy; the production reader's full ladder is pinned
     *  elsewhere). Null = skip this column. */
    private static byte[] readChunkPayload(RandomAccessFile raf, int sectorOffset) throws IOException {
        long pos = (long) sectorOffset * 4096;
        if (pos + 5 > raf.length()) return null;
        raf.seek(pos);
        int length = raf.readInt();
        if (length <= 0 || pos + 4 + length > raf.length()) return null;
        int compressionType = raf.readByte() & 0xFF;
        if (compressionType != 2) return null; // zlib only
        byte[] compressed = new byte[length - 1];
        raf.readFully(compressed);
        try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
