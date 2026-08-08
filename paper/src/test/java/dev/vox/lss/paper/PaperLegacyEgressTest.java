package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.common.wire.WireFormatException;
import dev.vox.lss.common.wire.WireSectionCursor;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
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
 * Paper twin of the Fabric {@code LegacyColumnEgressTest} at FRAME level
 * ({@link PaperPayloadHandler#translateColumnFrameToNative} — XVER plan §4.2/§9): the
 * corpus-driven translation chain (translate(v20 golden) must byte-equal the frozen
 * native golden, one deliberate palette-collapse carve-out), codec handling over whole
 * encoded frames (raw in place, zstd decompress → translate → recompress with the
 * frame's own declared content size), splice composition (the v18/v16 header rewrites
 * over a TRANSLATED frame must equal the rewrites over a natively-built frame), and
 * the loud failure shapes the sender's warn-drop contains.
 */
class PaperLegacyEgressTest {

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
        var blockInverse = PaperIdentityTables.blockIdsByIdentity();
        BLOCK_IDS = identity -> blockInverse.getOrDefault(identity, -1);
        BIOME_IDS = PaperNbtSectionSerializer.biomeIdLookup(REGISTRY_ACCESS);
        BIOME_ID_COUNT = PaperNbtSectionSerializer.biomeIdCount(REGISTRY_ACCESS);
    }

    // ---- fixtures ----

    private static Path corpusDir(String dirName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources").resolve(dirName);
            }
            Path nested = dir.resolve("paper");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources").resolve(dirName);
            }
        }
        throw new IllegalStateException("cannot locate the paper module source tree from "
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

    private static byte[] frameFor(byte codec, byte[] body) {
        return PaperPayloadHandler.encodeVoxelColumnPreEncoded(7, -3, "minecraft:overworld",
                1234567L, LSSConstants.COLUMN_SOURCE_DISK, codec, body);
    }

    private static byte[] translateFrame(byte[] frame, StoreCodec zstd) {
        return PaperPayloadHandler.translateColumnFrameToNative(frame,
                BLOCK_IDS, BIOME_IDS, Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, zstd);
    }

    // ---- the §9 translation chain over the committed corpus, frame-level ----

    /** Same deliberate divergence as the Fabric twin: the fixture's duplicate palette
     *  entries collapse through the identity dictionary; content pinned below. */
    private static final String COLLAPSED_FIXTURE = "duplicate-air.bin";

    @Test
    void everyCorpusGoldenTranslatesBackToItsExactNativeFrame() throws IOException {
        var names = corpusNames();
        assertTrue(names.size() >= 14, "premise: the committed corpus has at least 14 fixtures, found " + names);
        assertTrue(names.contains(COLLAPSED_FIXTURE),
                "premise: the collapse carve-out below still names a committed fixture");
        for (String name : names) {
            if (name.equals(COLLAPSED_FIXTURE)) continue;
            byte[] nativeGolden = readCorpus("nbt-corpus", name);
            byte[] v20Golden = readCorpus("v20-corpus", name);
            assertArrayEquals(frameFor(LSSConstants.COLUMN_CODEC_RAW, nativeGolden),
                    translateFrame(frameFor(LSSConstants.COLUMN_CODEC_RAW, v20Golden), null),
                    "the translated frame must byte-equal a natively-built frame (header "
                            + "preserved, body the frozen native bytes) for " + name);
        }
    }

    @Test
    void duplicatePaletteFixtureCollapsesToContentIdenticalBytes() {
        byte[] nativeGolden = readCorpus("nbt-corpus", COLLAPSED_FIXTURE);
        byte[] translatedFrame = translateFrame(
                frameFor(LSSConstants.COLUMN_CODEC_RAW, readCorpus("v20-corpus", COLLAPSED_FIXTURE)), null);
        byte[] translatedBody = readFrameBody(translatedFrame);
        assertFalse(java.util.Arrays.equals(nativeGolden, translatedBody),
                "premise: the collapse actually diverges — if this starts passing "
                        + "byte-equal, fold the fixture back into the chain test");

        var expected = WireSectionCursor.parse(nativeGolden, WireSectionCursor.Layout.NATIVE);
        var actual = WireSectionCursor.parse(translatedBody, WireSectionCursor.Layout.NATIVE);
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

    private static int[] resolvedValues(WireSectionCursor.WireContainer c, int entries) {
        if (c.bits() == 0) {
            int[] out = new int[entries];
            java.util.Arrays.fill(out, c.palette()[0]);
            return out;
        }
        int[] values = WireSectionCursor.unpack(c.data(), c.bits(), entries);
        if (c.palette() == null) {
            return values;
        }
        int[] out = new int[entries];
        for (int i = 0; i < entries; i++) {
            out[i] = c.palette()[values[i]];
        }
        return out;
    }

    /** The length-prefixed section byte array at the tail of an encoded column frame. */
    private static byte[] readFrameBody(byte[] frame) {
        var decoded = new byte[][] { null };
        // Reuse the packed-pos reader's buffer discipline via the public decode surface:
        // skip the fixed header exactly as the splices do.
        var buf = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.wrappedBuffer(frame));
        try {
            buf.readInt();
            buf.readInt();
            buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH);
            buf.readLong();
            buf.readByte(); // source
            buf.readByte(); // codec
            decoded[0] = buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE);
        } finally {
            buf.release();
        }
        return decoded[0];
    }

    // ---- codec handling ----

    @Test
    void zstdFrameDecompressesTranslatesAndRecompresses() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd, "the zstd natives ship on the test classpath (the store suite "
                + "requires them) — a null here is an environment regression, not a skip");
        byte[] v20 = readCorpus("v20-corpus", "multi-palette.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-palette.bin");

        byte[] out = translateFrame(frameFor(LSSConstants.COLUMN_CODEC_ZSTD, zstd.compress(v20)), zstd);

        byte[] outBody = readFrameBody(out);
        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, frameCodec(out),
                "a v19 session keeps its compression capability — the codec must survive");
        assertArrayEquals(nativeGolden, zstd.decompress(outBody, nativeGolden.length),
                "the recompressed frame must decompress to the exact native bytes");
    }

    private static byte frameCodec(byte[] frame) {
        var buf = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.wrappedBuffer(frame));
        try {
            buf.readInt();
            buf.readInt();
            buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH);
            buf.readLong();
            buf.readByte(); // source
            return buf.readByte();
        } finally {
            buf.release();
        }
    }

    // ---- splice composition ----

    @Test
    void v18AndV16SplicesOverTheTranslatedFrameEqualTheNativeBuiltRewrites() {
        byte[] v20 = readCorpus("v20-corpus", "waterlogged.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "waterlogged.bin");
        byte[] translated = translateFrame(frameFor(LSSConstants.COLUMN_CODEC_RAW, v20), null);
        byte[] nativeFrame = frameFor(LSSConstants.COLUMN_CODEC_RAW, nativeGolden);

        assertArrayEquals(PaperPayloadHandler.rewriteColumnToV18(nativeFrame),
                PaperPayloadHandler.rewriteColumnToV18(translated),
                "the v18 splice must compose on the translated frame");
        assertArrayEquals(PaperPayloadHandler.rewriteColumnToV16(nativeFrame),
                PaperPayloadHandler.rewriteColumnToV16(translated),
                "the v16 splice must compose on the translated frame");
    }

    // ---- the loud failure shapes ----

    @Test
    void unresolvableIdentityThrowsTheTranslatorsPinnedFailure() {
        byte[] frame = frameFor(LSSConstants.COLUMN_CODEC_RAW, readCorpus("v20-corpus", "multi-section.bin"));
        assertThrows(WireFormatException.class,
                () -> PaperPayloadHandler.translateColumnFrameToNative(frame,
                        identity -> -1, BIOME_IDS,
                        Block.BLOCK_STATE_REGISTRY.size(), BIOME_ID_COUNT, null),
                "an identity missing from the server's own registry is a table bug and "
                        + "must fail loudly, never serve wrong blocks");
    }

    @Test
    void zstdFrameWithNoCodecAvailableThrows() {
        byte[] frame = frameFor(LSSConstants.COLUMN_CODEC_ZSTD, readCorpus("v20-corpus", "multi-section.bin"));
        assertThrows(IllegalStateException.class,
                () -> translateFrame(frame, null));
    }

    @Test
    void zstdFrameWithAnInvalidDeclaredContentSizeThrows() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd);
        // Garbage bytes are not a zstd frame: declaredContentSize is negative/invalid and
        // the guard must refuse BEFORE any allocation-sized-by-wire-content happens.
        byte[] frame = frameFor(LSSConstants.COLUMN_CODEC_ZSTD, new byte[] {1, 2, 3, 4, 5});
        assertThrows(IllegalStateException.class, () -> translateFrame(frame, zstd));
    }
}
