package dev.vox.lss.common.wire;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code common/} has no Minecraft dependency, so {@link WireBytes} hand-rolls the
 * VarInt / big-endian / UTF shapes — this suite pins BIT-IDENTITY against the real
 * {@code FriendlyByteBuf}, which is the whole premise of the shared cursor. A drift
 * here would mean the cursor parses a different wire than the game emits.
 */
class WireBytesParityTest {

    private static final int[] EDGE_VALUES = {
            0, 1, 2, 126, 127, 128, 129, 255, 256, 16383, 16384, 2097151, 2097152,
            268435455, 268435456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE,
    };

    @Test
    void varIntEncodingMatchesFriendlyByteBufOnEdgesAndFuzz() {
        var rng = new Random(1);
        for (int round = 0; round < 2000 + EDGE_VALUES.length; round++) {
            int value = round < EDGE_VALUES.length ? EDGE_VALUES[round] : rng.nextInt();
            var mc = new FriendlyByteBuf(Unpooled.buffer());
            mc.writeVarInt(value);
            byte[] expected = new byte[mc.readableBytes()];
            mc.readBytes(expected);

            var ours = new WireBytes.Writer(8);
            ours.writeVarInt(value);
            assertArrayEquals(expected, ours.toByteArray(), "encode of " + value);
            assertEquals(expected.length, WireBytes.varIntSize(value), "size of " + value);
            assertEquals(value, new WireBytes.Reader(expected).readVarInt(), "decode of " + value);
        }
    }

    @Test
    void scalarAndUtfShapesMatchFriendlyByteBuf() {
        var mc = new FriendlyByteBuf(Unpooled.buffer());
        mc.writeByte(-4);
        mc.writeShort(4096);
        mc.writeShort(-1);
        mc.writeLong(0x0123456789ABCDEFL);
        mc.writeUtf("minecraft:oak_stairs[facing=north]");
        byte[] expected = new byte[mc.readableBytes()];
        mc.readBytes(expected);

        var ours = new WireBytes.Writer(64);
        ours.writeByte(-4).writeShort(4096).writeShort(-1)
                .writeLong(0x0123456789ABCDEFL).writeUtf("minecraft:oak_stairs[facing=north]");
        assertArrayEquals(expected, ours.toByteArray());

        var in = new WireBytes.Reader(expected);
        assertEquals(-4, in.readByte());
        assertEquals(4096, in.readShort());
        assertEquals(-1, in.readShort());
        assertEquals(0x0123456789ABCDEFL, in.readLong());
        assertEquals("minecraft:oak_stairs[facing=north]", in.readUtf(256));
        assertEquals(0, in.remaining());
    }

    @Test
    void readerContainmentIsWireFormatExceptionNotArrayErrors() {
        assertThrows(WireFormatException.class, () -> new WireBytes.Reader(new byte[0]).readByte());
        assertThrows(WireFormatException.class, () -> new WireBytes.Reader(new byte[1]).readShort());
        assertThrows(WireFormatException.class, () -> new WireBytes.Reader(new byte[7]).readLong());
        assertThrows(WireFormatException.class,
                () -> new WireBytes.Reader(new byte[] { (byte) 0x80 }).readVarInt());
        assertThrows(WireFormatException.class,
                () -> new WireBytes.Reader(new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, 1 }).readVarInt());
        var negativeLen = new WireBytes.Writer(8).writeVarInt(-5).toByteArray();
        assertThrows(WireFormatException.class,
                () -> new WireBytes.Reader(negativeLen).readUtf(256));
        assertThrows(WireFormatException.class, () -> new WireBytes.Reader(new byte[4], 3, 1));
    }
}
