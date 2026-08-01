package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Paper twin of the protocol-19 codec-byte wire pins: the codec-1 encode matches the
 * explicit v19 layout (byte-identical to Fabric's payload encode — the wire-parity
 * contract), and the v16 SPLICE both removes exactly the two tag bytes from a RAW frame
 * and THROWS on a codec-1 frame (review A6 — a spliced zstd body would hard-kick the
 * legacy client; the egress guard converts the throw into a warn-drop).
 */
class CompressedColumnPaperWireTest {

    private static StoreCodec codec;

    @BeforeAll
    static void probe() {
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable on this platform");
    }

    private static byte[] ref(Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buf);
            var out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    @Test
    void codec1EncodeMatchesExplicitV19Layout() {
        byte[] raw = new byte[4096];
        byte[] frame = codec.compress(raw);
        byte[] expected = ref(b -> {
            b.writeInt(7);
            b.writeInt(-8);
            b.writeUtf("minecraft:overworld", LSSConstants.MAX_DIMENSION_STRING_LENGTH);
            b.writeLong(1234L);
            b.writeByte(LSSConstants.COLUMN_SOURCE_STORE);
            b.writeByte(LSSConstants.COLUMN_CODEC_ZSTD);
            b.writeByteArray(frame);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                7, -8, "minecraft:overworld", 1234L,
                LSSConstants.COLUMN_SOURCE_STORE, LSSConstants.COLUMN_CODEC_ZSTD, frame));
    }

    @Test
    void rawOverloadEncodesCodec0() {
        byte[] sections = {1, 2, 3};
        byte[] viaOverload = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                1, 2, "minecraft:overworld", 5L, LSSConstants.COLUMN_SOURCE_DISK, sections);
        byte[] explicit = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                1, 2, "minecraft:overworld", 5L, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_CODEC_RAW, sections);
        assertArrayEquals(explicit, viaOverload,
                "the pre-19 overload must emit a valid CURRENT frame (codec byte is never optional)");
    }

    @Test
    void spliceRemovesExactlyTwoBytesFromARawFrame() {
        byte[] sections = {9, 8, 7};
        byte[] current = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                3, -4, "minecraft:overworld", 42L, LSSConstants.COLUMN_SOURCE_DISK, sections);
        byte[] legacy = ref(b -> {
            b.writeInt(3);
            b.writeInt(-4);
            b.writeUtf("minecraft:overworld", LSSConstants.MAX_DIMENSION_STRING_LENGTH);
            b.writeLong(42L);
            b.writeByteArray(sections);
        });
        assertArrayEquals(legacy, PaperPayloadHandler.rewriteColumnToV16(current));
        assertEquals(current.length - 2, legacy.length);
    }

    @Test
    void spliceThrowsOnACodec1Frame() {
        byte[] frame = codec.compress(new byte[4096]);
        byte[] current = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, "minecraft:overworld", 1L,
                LSSConstants.COLUMN_SOURCE_STORE, LSSConstants.COLUMN_CODEC_ZSTD, frame);
        assertThrows(IllegalStateException.class,
                () -> PaperPayloadHandler.rewriteColumnToV16(current),
                "a framed column must never splice into the legacy layout");
    }
}
