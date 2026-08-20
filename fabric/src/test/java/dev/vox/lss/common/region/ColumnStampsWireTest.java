package dev.vox.lss.common.region;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.wire.WireBytes;
import dev.vox.lss.common.wire.WireFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stamped-up_to_date wire codec's pins (stamped-up-to-date-plan.md §3/§9.6) — the
 * FarPlayerWire/RegionSummaryWire hostile-decode bar, plus the SEMANTIC stamp bounds
 * that are this codec's own: the client-side ratchet is monotonic and revocation only
 * touches summary provenance, so a single hostile huge second would seal a position
 * against offline edits permanently. Frames violating any bound drop WHOLE.
 */
class ColumnStampsWireTest {

    private static final long NOW = 1_750_000_000L;
    private static final String DIM = "minecraft:overworld";

    private static byte[] frame(long... seconds) {
        long[] positions = new long[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            positions[i] = PositionUtil.packPosition(i * 3 - 7, -i);
        }
        return ColumnStampsWire.encode(DIM, positions, seconds, seconds.length);
    }

    @Test
    void roundTripsPositionsAndSeconds() {
        long[] positions = {PositionUtil.packPosition(3, -4),
                PositionUtil.packPosition(-2_000_000, 2_000_000),
                PositionUtil.packPosition(0, 0)};
        long[] seconds = {NOW, NOW - 3600, NOW + 5};
        var decoded = ColumnStampsWire.decode(
                ColumnStampsWire.encode(DIM, positions, seconds, 3), NOW + 10);
        assertEquals(DIM, decoded.dimension());
        assertArrayEquals(positions, decoded.packedPositions());
        assertArrayEquals(seconds, decoded.stampSeconds());
    }

    @Test
    void unknownVersionDrops() {
        byte[] f = frame(NOW);
        f[0] = 9;
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(f, NOW + 10));
    }

    @Test
    void truncatedFrameDropsBeforeAllocation() {
        byte[] f = frame(NOW, NOW + 1, NOW + 2);
        byte[] cut = java.util.Arrays.copyOf(f, f.length - 12);
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(cut, NOW + 10));
    }

    @Test
    void trailingBytesDrop() {
        byte[] f = frame(NOW);
        byte[] padded = java.util.Arrays.copyOf(f, f.length + 3);
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(padded, NOW + 10));
    }

    @Test
    void hostileCountsDrop() {
        // count = 0 and count > MAX both reject; a huge declared count on a short
        // frame rejects at the remaining-bytes floor before sizing anything.
        var w = new WireBytes.Writer(32);
        w.writeByte(ColumnStampsWire.VERSION);
        w.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w, NOW);
        w.writeVarInt(0);
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w.toByteArray(), NOW + 10));

        var w2 = new WireBytes.Writer(32);
        w2.writeByte(ColumnStampsWire.VERSION);
        w2.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w2, NOW);
        w2.writeVarInt(ColumnStampsWire.MAX_STAMP_ENTRIES + 1);
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w2.toByteArray(), NOW + 10));
    }

    @Test
    void oversizedDimensionDrops() {
        String longDim = "lss:" + "d".repeat(300);
        long[] pos = {PositionUtil.packPosition(1, 1)};
        long[] sec = {NOW};
        byte[] f = ColumnStampsWire.encode(longDim, pos, sec, 1);
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(f, NOW + 10));
    }

    @Test
    void futureSecondBeyondSkewDropsWhole() {
        // The permanent-seal shape (§9.6): one hostile huge second must reject the
        // WHOLE frame — partial application of a corrupt frame is worse than losing
        // it (loss is the designed-tolerant case).
        byte[] f = frame(NOW, NOW + ColumnStampsWire.FUTURE_SKEW_ALLOWANCE_SECONDS + 100);
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(f, NOW));
        // At the boundary it passes.
        var ok = ColumnStampsWire.decode(
                frame(NOW + ColumnStampsWire.FUTURE_SKEW_ALLOWANCE_SECONDS), NOW);
        assertEquals(1, ok.stampSeconds().length);
    }

    @Test
    void nonPositiveSecondsDropAtDecodeAndRefuseAtEncode() {
        // Decode side: craft a frame whose delta walks the second to 0.
        var w = new WireBytes.Writer(64);
        w.writeByte(ColumnStampsWire.VERSION);
        w.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w, NOW);
        w.writeVarInt(1);
        w.writeLong(PositionUtil.packPosition(1, 1));
        RegionSummaryWire.writeZigVarLong(w, -NOW); // second = 0
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w.toByteArray(), NOW + 10));
        // Encode side: a producer bug fails loudly at build, not at the client.
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.encode(
                DIM, new long[]{1L}, new long[]{0L}, 1));
    }
}
