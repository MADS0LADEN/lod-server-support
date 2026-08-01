package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Protocol-19 codec-byte wire semantics for {@link VoxelColumnS2CPayload}: the v19
 * layout (source THEN codec THEN section array), codec-1 round-trips, the read-time
 * raw-size charge rule (plan §0.5 — {@code max(shipped, clamp(declared, 0, MAX))}),
 * and unknown-codec preservation (rejection happens at the drain, never here).
 */
class CompressedColumnWireTest {

    private static StoreCodec codec;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable on this platform");
    }

    private static byte[] encode(VoxelColumnS2CPayload p) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VoxelColumnS2CPayload.CODEC.encode(buf, p);
            var out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static VoxelColumnS2CPayload decode(byte[] wire) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        try {
            return VoxelColumnS2CPayload.CODEC.decode(buf);
        } finally {
            buf.release();
        }
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
    void codec1LayoutMatchesExplicitReference() {
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
        var p = new VoxelColumnS2CPayload(7, -8, Level.OVERWORLD, 1234L,
                LSSConstants.COLUMN_SOURCE_STORE, LSSConstants.COLUMN_CODEC_ZSTD,
                frame, raw.length);
        assertArrayEquals(expected, encode(p));
    }

    @Test
    void codec1RoundTripKeepsFrameAndHonestDeclaredCharge() {
        byte[] raw = new byte[8192];
        raw[100] = 3;
        byte[] frame = codec.compress(raw);
        var decoded = decode(encode(new VoxelColumnS2CPayload(1, 2, Level.OVERWORLD, 9L,
                LSSConstants.COLUMN_SOURCE_DISK, LSSConstants.COLUMN_CODEC_ZSTD,
                frame, raw.length)));
        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, decoded.codec());
        assertArrayEquals(frame, decoded.shippedSections(), "the frame crosses untouched");
        assertEquals(raw.length, decoded.rawSize(),
                "an honest frame's declared content size is the charge");
        assertEquals(raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                decoded.estimatedBytes(), "estimatedBytes stays RAW-denominated");
        assertEquals(frame.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                decoded.wireEstimatedBytes());
    }

    @Test
    void garbageCodec1BytesChargeShippedLength() {
        // Not a zstd frame at all: declaredContentSize is non-positive, and the charge
        // rule floors at the shipped length — never negative, never zero for resident
        // bytes (review A2: a lying header must not under-charge the retention gauge).
        byte[] junk = {1, 2, 3, 4, 5, 6, 7, 8};
        var decoded = decode(encode(new VoxelColumnS2CPayload(0, 0, Level.OVERWORLD, 1L,
                (byte) 0, LSSConstants.COLUMN_CODEC_ZSTD, junk, junk.length)));
        assertEquals(junk.length, decoded.rawSize());
    }

    @Test
    void tinyDeclaredChargeFlooredAtShippedLength() {
        // A real frame whose content (10 B) is smaller than its own shipped size: the
        // max(shipped, ...) floor keeps the charge at resident bytes.
        byte[] raw = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        byte[] frame = codec.compress(raw);
        assumeTrue(frame.length > raw.length, "tiny content frames carry header overhead");
        var decoded = decode(encode(new VoxelColumnS2CPayload(0, 0, Level.OVERWORLD, 1L,
                (byte) 0, LSSConstants.COLUMN_CODEC_ZSTD, frame, raw.length)));
        assertEquals(frame.length, decoded.rawSize(),
                "charge = max(shipped, declared) — resident bytes always covered");
    }

    @Test
    void overCapDeclaredChargeClampsAtMaxSectionsSize() {
        byte[] raw = new byte[LSSConstants.MAX_SECTIONS_SIZE + 8];
        byte[] frame = codec.compress(raw);
        var decoded = decode(encode(new VoxelColumnS2CPayload(0, 0, Level.OVERWORLD, 1L,
                (byte) 0, LSSConstants.COLUMN_CODEC_ZSTD, frame, raw.length)));
        assertEquals(LSSConstants.MAX_SECTIONS_SIZE, decoded.rawSize(),
                "a hostile huge declaration distorts the gauge by at most MAX per column");
    }

    @Test
    void unknownCodecValuePreservedAndChargesShipped() {
        // Alignment is safe for ANY codec value (the array is length-prefixed); rejection
        // is the DRAIN's job (ingest-failure), so the read must preserve the byte and
        // charge conservatively.
        byte[] body = {0};
        var decoded = decode(encode(new VoxelColumnS2CPayload(0, 0, Level.OVERWORLD, 1L,
                (byte) 0, (byte) 9, body, body.length)));
        assertEquals((byte) 9, decoded.codec());
        assertEquals(body.length, decoded.rawSize());
    }

    @Test
    void declaredContentSizeMatchesOnOwnFramesAndFailsOnGarbage() {
        byte[] raw = new byte[2048];
        assertEquals(raw.length, codec.declaredContentSize(codec.compress(raw)),
                "single-shot frames always declare their content size (Phase 0: 0 violations)");
        assertTrue(codec.declaredContentSize(new byte[]{1, 2, 3}) <= 0);
    }
}
