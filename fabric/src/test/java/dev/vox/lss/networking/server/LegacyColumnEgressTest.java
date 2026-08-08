package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.common.wire.WireFormatException;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The C2 legacy egress body translation ({@link RequestProcessingService#translateColumnToNative}
 * — XVER plan §4.2/§9): with v20 as the canonical internal form, every legacy dialect's
 * column body must translate back to EXACTLY the native bytes the v19 era shipped. The
 * headline is the corpus-driven translation chain: for every committed fixture pair,
 * {@code translate(v20-corpus golden)} must BYTE-EQUAL the frozen {@code nbt-corpus}
 * native golden — the §9 bijection on one registry, driven by the same fixtures the
 * emit direction is pinned against (so the two suites cannot drift apart silently).
 * Payload-level cases pin the codec handling (raw in place; zstd decompress → translate
 * → RECOMPRESS), the re-derived rawSize, header preservation, splice composition
 * (v18/v16 are "v19 minus header bytes" — the splices must ride the TRANSLATED body),
 * and the two loud failure shapes the sender's warn-drop contains.
 */
class LegacyColumnEgressTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegistryAccess REGISTRY_ACCESS;
    private static ToIntFunction<String> BLOCK_IDS;
    private static ToIntFunction<String> BIOME_IDS;
    private static int BIOME_ID_COUNT;

    @BeforeAll
    static void setup() {
        REGISTRY_ACCESS = CorpusRegistryAccess.build();
        var blockInverse = IdentityTables.blockIdsByIdentity();
        BLOCK_IDS = identity -> blockInverse.getOrDefault(identity, -1);
        BIOME_IDS = NbtSectionSerializer.biomeIdLookup(REGISTRY_ACCESS);
        BIOME_ID_COUNT = NbtSectionSerializer.biomeIdCount(REGISTRY_ACCESS);
    }

    // ---- fixtures ----

    /** Locate a corpus dir in this module regardless of the test JVM's working directory
     *  (the {@code NbtSectionSerializerTest.goldenPath} walk, parameterized by dir). */
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
        throw new IllegalStateException("cannot locate the fabric module source tree from "
                + Path.of("").toAbsolutePath());
    }

    private static byte[] readCorpus(String dirName, String name) {
        try {
            return Files.readAllBytes(corpusDir(dirName).resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> corpusNames() throws IOException {
        try (Stream<Path> files = Files.list(corpusDir("nbt-corpus"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".bin")).sorted().toList();
        }
    }

    private static byte[] translateBody(byte[] v20Body) {
        return dev.vox.lss.common.wire.V20ToNativeTranslator.translate(v20Body,
                BLOCK_IDS, BIOME_IDS, Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT);
    }

    // ---- the §9 translation chain over the committed corpus ----

    /** The one deliberate byte divergence in the chain: this fixture's native palette
     *  carries DUPLICATE entries, which the dictionary de-duplicates on the way to v20
     *  (identity-keyed) — so the way back emits the translator's collapsed (minimal)
     *  palette, byte-smaller but content-identical. Native decoders tolerate either
     *  shape; {@link #duplicatePaletteFixtureCollapsesToContentIdenticalBytes} pins
     *  the content equality this exclusion leans on. */
    private static final String COLLAPSED_FIXTURE = "duplicate-air.bin";

    @Test
    void everyCorpusGoldenTranslatesBackToItsExactNativeBytes() throws IOException {
        var names = corpusNames();
        assertTrue(names.size() >= 14, "premise: the committed corpus has at least 14 fixtures, found " + names);
        assertTrue(names.contains(COLLAPSED_FIXTURE),
                "premise: the collapse carve-out below still names a committed fixture");
        for (String name : names) {
            if (name.equals(COLLAPSED_FIXTURE)) continue;
            byte[] nativeGolden = readCorpus("nbt-corpus", name);
            byte[] v20Golden = readCorpus("v20-corpus", name);
            assertArrayEquals(nativeGolden, translateBody(v20Golden),
                    "egress translation must reproduce the frozen native bytes for " + name);
        }
    }

    @Test
    void duplicatePaletteFixtureCollapsesToContentIdenticalBytes() {
        byte[] nativeGolden = readCorpus("nbt-corpus", COLLAPSED_FIXTURE);
        byte[] translated = translateBody(readCorpus("v20-corpus", COLLAPSED_FIXTURE));
        assertFalse(java.util.Arrays.equals(nativeGolden, translated),
                "premise: the collapse actually diverges — if this starts passing "
                        + "byte-equal, fold the fixture back into the chain test");

        var expected = dev.vox.lss.common.wire.WireSectionCursor.parse(
                nativeGolden, dev.vox.lss.common.wire.WireSectionCursor.Layout.NATIVE);
        var actual = dev.vox.lss.common.wire.WireSectionCursor.parse(
                translated, dev.vox.lss.common.wire.WireSectionCursor.Layout.NATIVE);
        assertEquals(expected.sections().size(), actual.sections().size());
        for (int i = 0; i < expected.sections().size(); i++) {
            var e = expected.sections().get(i);
            var a = actual.sections().get(i);
            assertEquals(e.sectionY(), a.sectionY());
            assertEquals(e.nonEmptyBlockCount(), a.nonEmptyBlockCount());
            assertEquals(e.fluidCount(), a.fluidCount());
            assertArrayEquals(resolvedValues(e.blocks(), 4096), resolvedValues(a.blocks(), 4096),
                    "per-entry block ids must survive the palette collapse (section " + i + ")");
            assertArrayEquals(resolvedValues(e.biomes(), 64), resolvedValues(a.biomes(), 64),
                    "per-entry biome ids must survive the palette collapse (section " + i + ")");
            assertArrayEquals(e.blockLight(), a.blockLight());
            assertArrayEquals(e.skyLight(), a.skyLight());
        }
    }

    /** Per-entry resolved ids of a native container (single-value / indexed / DIRECT). */
    private static int[] resolvedValues(dev.vox.lss.common.wire.WireSectionCursor.WireContainer c,
                                        int entries) {
        if (c.bits() == 0) {
            int[] out = new int[entries];
            java.util.Arrays.fill(out, c.palette()[0]);
            return out;
        }
        int[] values = dev.vox.lss.common.wire.WireSectionCursor.unpack(c.data(), c.bits(), entries);
        if (c.palette() == null) {
            return values; // DIRECT: the packed values ARE the ids
        }
        int[] out = new int[entries];
        for (int i = 0; i < entries; i++) {
            out[i] = c.palette()[values[i]];
        }
        return out;
    }

    // ---- payload-level codec handling ----

    private static VoxelColumnS2CPayload rawPayload(byte[] v20Body) {
        return new VoxelColumnS2CPayload(7, -3, Level.OVERWORLD, 1234567L,
                LSSConstants.COLUMN_SOURCE_DISK, LSSConstants.COLUMN_CODEC_RAW,
                v20Body, v20Body.length);
    }

    @Test
    void rawPayloadTranslatesInPlaceAndRederivesRawSize() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-section.bin");

        var out = RequestProcessingService.translateColumnToNative(rawPayload(v20),
                BLOCK_IDS, BIOME_IDS, Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, null);

        assertArrayEquals(nativeGolden, out.shippedSections());
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, out.codec());
        assertEquals(nativeGolden.length, out.rawSize(),
                "rawSize must be re-derived from the TRANSLATED body — the legacy client's "
                        + "charge rule reads the bytes it receives");
        assertEquals(7, out.chunkX());
        assertEquals(-3, out.chunkZ());
        assertEquals(Level.OVERWORLD, out.dimension());
        assertEquals(1234567L, out.columnTimestamp());
        assertEquals(LSSConstants.COLUMN_SOURCE_DISK, out.source());
    }

    @Test
    void zstdPayloadDecompressesTranslatesAndRecompresses() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd, "the zstd natives ship on the test classpath (the store suite "
                + "requires them) — a null here is an environment regression, not a skip");
        byte[] v20 = readCorpus("v20-corpus", "multi-palette.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-palette.bin");
        var in = new VoxelColumnS2CPayload(2, 5, Level.END, 42L,
                LSSConstants.COLUMN_SOURCE_STORE, LSSConstants.COLUMN_CODEC_ZSTD,
                zstd.compress(v20), v20.length);

        var out = RequestProcessingService.translateColumnToNative(in,
                BLOCK_IDS, BIOME_IDS, Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, zstd);

        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, out.codec(),
                "a v19 session keeps its compression capability — the codec must survive");
        assertEquals(nativeGolden.length, out.rawSize());
        assertArrayEquals(nativeGolden, zstd.decompress(out.shippedSections(), out.rawSize()),
                "the recompressed frame must decompress to the exact native bytes");
        assertFalse(java.util.Arrays.equals(out.shippedSections(), in.shippedSections()),
                "premise: the shipped frame actually changed (recompressed native, not v20)");
    }

    // ---- splice composition: v18/v16 are "the translated v19 minus header bytes" ----

    private static byte[] encodePayload(VoxelColumnS2CPayload payload) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VoxelColumnS2CPayload.CODEC.encode(buf, payload);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    @Test
    void v18SpliceComposesOnTheTranslatedBody() {
        byte[] v20 = readCorpus("v20-corpus", "waterlogged.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "waterlogged.bin");
        var translated = RequestProcessingService.translateColumnToNative(rawPayload(v20),
                BLOCK_IDS, BIOME_IDS, Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, null);

        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodePayload(translated.asV18())));
        try {
            assertEquals(7, buf.readInt());
            assertEquals(-3, buf.readInt());
            assertEquals(Level.OVERWORLD.identifier().toString(),
                    buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertEquals(1234567L, buf.readLong());
            assertEquals(LSSConstants.COLUMN_SOURCE_DISK, buf.readByte(),
                    "v18 keeps the source byte");
            assertArrayEquals(nativeGolden, buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE),
                    "the v18 frame's section array must be the TRANSLATED native body");
            assertEquals(0, buf.readableBytes(), "no codec byte anywhere in a v18 frame");
        } finally {
            buf.release();
        }
    }

    @Test
    void v16SpliceComposesOnTheTranslatedBody() {
        byte[] v20 = readCorpus("v20-corpus", "negative-y.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "negative-y.bin");
        var translated = RequestProcessingService.translateColumnToNative(rawPayload(v20),
                BLOCK_IDS, BIOME_IDS, Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, null);

        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodePayload(translated.asV16())));
        try {
            assertEquals(7, buf.readInt());
            assertEquals(-3, buf.readInt());
            assertEquals(Level.OVERWORLD.identifier().toString(),
                    buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertEquals(1234567L, buf.readLong());
            assertArrayEquals(nativeGolden, buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE),
                    "the v16 frame drops source AND codec and carries the translated body");
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    // ---- the loud failure shapes (contained by the sender's warn-drop) ----

    @Test
    void unresolvableIdentityThrowsTheTranslatorsPinnedFailure() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        assertThrows(WireFormatException.class,
                () -> RequestProcessingService.translateColumnToNative(rawPayload(v20),
                        identity -> -1, BIOME_IDS,
                        Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, null),
                "an identity missing from the server's own registry is a table bug and "
                        + "must fail loudly, never serve wrong blocks");
    }

    @Test
    void zstdPayloadWithNoCodecAvailableThrows() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        var framed = new VoxelColumnS2CPayload(0, 0, Level.OVERWORLD, 1L,
                LSSConstants.COLUMN_SOURCE_DISK, LSSConstants.COLUMN_CODEC_ZSTD, v20, v20.length);
        assertThrows(IllegalStateException.class,
                () -> RequestProcessingService.translateColumnToNative(framed,
                        BLOCK_IDS, BIOME_IDS,
                        Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, null));
    }
}
