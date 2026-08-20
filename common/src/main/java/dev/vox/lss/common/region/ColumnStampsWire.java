package dev.vox.lss.common.region;

import dev.vox.lss.common.wire.WireBytes;
import dev.vox.lss.common.wire.WireFormatException;

/**
 * The stamped-up_to_date wire codec (stamped-up-to-date-plan.md §3): one S2C frame
 * carrying (packedPosition, verificationSecond) pairs for columns the server just
 * answered {@code up_to_date} through a COMPARE-BACKED rung. The client ratchets its
 * cached acquisition stamps forward so the next region-summary frame validates the
 * columns instead of re-flagging them stale forever (the serve-then-save inversion).
 * Same family and discipline as {@link RegionSummaryWire}: raw byte[] bodies on both
 * platforms, version byte, explicit dimension echoed for the anti-stale binding,
 * hostile-decode caps before any allocation.
 *
 * <pre>
 * stamps := version:u8=1 dimension:utf baseSecond:varlong-zig
 *           count:varint(1..MAX_STAMP_ENTRIES)
 *           count x { packedPos:i64  delta:varlong-zig }   stamp(i) = base + delta(i)
 * </pre>
 *
 * <p>Semantic bounds at decode (plan §9.6 — the review's permanent-seal hostile shape):
 * the ratchet is monotonic and revocation only touches summary provenance, so a single
 * hostile huge second would seal a position against offline edits PERMANENTLY. Every
 * decoded stamp must land in {@code (0, localNowSecond + FUTURE_SKEW_ALLOWANCE]}
 * (mirroring {@code RegionStampTable}'s skew posture); a violating frame drops WHOLE
 * (one bad entry means the producer is broken or hostile — partial application of a
 * corrupt frame is worse than losing it, and loss is the designed-tolerant case).
 */
public final class ColumnStampsWire {

    public static final int VERSION = 1;
    /** Per-frame entry cap — the batch-response wire cap's sibling; the server SPLITS
     *  past it (plan §9.5 — never truncates: silent tail truncation would drop the
     *  bulk regime the feature targets). */
    public static final int MAX_STAMP_ENTRIES = 1024;
    /** Decode-side clock-skew allowance for the semantic stamp bound — the stamp
     *  table's {@code FUTURE_SKEW_ALLOWANCE_SECONDS} posture. */
    public static final long FUTURE_SKEW_ALLOWANCE_SECONDS = 3600;

    /** Decoded frame. Positions/seconds are index-paired; seconds are server-clock
     *  epoch seconds, each already bounds-checked at decode. */
    public record Stamps(String dimension, long[] packedPositions, long[] stampSeconds) {}

    private ColumnStampsWire() {}

    /** Encode one frame. Caller guarantees {@code 1 <= count <= MAX_STAMP_ENTRIES}
     *  and positive seconds (the producer stamps with its own clock); both are still
     *  re-checked here so a producer bug fails loudly at build, not at the client. */
    public static byte[] encode(String dimension, long[] packedPositions,
                                long[] stampSeconds, int count) {
        if (count < 1 || count > MAX_STAMP_ENTRIES) {
            throw new WireFormatException("stamps count " + count + " outside [1, "
                    + MAX_STAMP_ENTRIES + "]");
        }
        // Encode-side dimension guard (final panel — RegionSummaryWire's own rule:
        // "the decode cap alone would let a local bug emit a frame the twin then
        // rejects"): an over-cap/empty dimension would ship frames every client
        // silently discards WHOLE while the server counts them sent.
        if (dimension == null || dimension.isEmpty()
                || dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > RegionSummaryWire.MAX_DIMENSION_UTF_BYTES) {
            throw new WireFormatException("stamps dimension null/empty or over "
                    + RegionSummaryWire.MAX_DIMENSION_UTF_BYTES + " UTF bytes");
        }
        long base = stampSeconds[0];
        if (base <= 0) throw new WireFormatException("stamps base second " + base + " <= 0");
        // Producer-side upper bound (3-Opus fold): the client rejects out-of-bound
        // frames WHOLE, so one clock-damaged second would silently discard up to 1023
        // good entries at every client — fail loudly at the server instead.
        long producerBound = System.currentTimeMillis() / 1000L + FUTURE_SKEW_ALLOWANCE_SECONDS;
        var w = new WireBytes.Writer(16 + count * 10);
        w.writeByte(VERSION);
        w.writeUtf(dimension);
        RegionSummaryWire.writeZigVarLong(w, base);
        w.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            if (stampSeconds[i] <= 0 || stampSeconds[i] > producerBound) {
                throw new WireFormatException("stamps second " + stampSeconds[i]
                        + " outside (0, now+" + FUTURE_SKEW_ALLOWANCE_SECONDS + "]");
            }
            w.writeLong(packedPositions[i]);
            RegionSummaryWire.writeZigVarLong(w, stampSeconds[i] - base);
        }
        return w.toByteArray();
    }

    /** Decode + validate against the receiver's clock ({@code localNowSecond}). */
    public static Stamps decode(byte[] body, long localNowSecond) {
        var r = new WireBytes.Reader(body);
        int v = r.readUnsignedByte();
        if (v != VERSION) throw new WireFormatException("stamps version " + v + " != " + VERSION);
        String dimension = r.readUtf(RegionSummaryWire.MAX_DIMENSION_UTF_BYTES);
        if (dimension.isEmpty()) throw new WireFormatException("empty stamps dimension");
        long base = RegionSummaryWire.readZigVarLong(r);
        int count = r.readVarInt();
        if (count < 1 || count > MAX_STAMP_ENTRIES) {
            throw new WireFormatException("stamps count " + count + " outside [1, "
                    + MAX_STAMP_ENTRIES + "]");
        }
        // Every entry costs at least 9 bytes (i64 + one varlong byte): a frame shorter
        // than that is hostile/truncated — reject BEFORE sizing the arrays.
        if (r.remaining() < 9L * count) {
            throw new WireFormatException("stamps frame has " + r.remaining()
                    + " bytes remaining for " + count + " entries");
        }
        long bound = localNowSecond + FUTURE_SKEW_ALLOWANCE_SECONDS;
        long[] positions = new long[count];
        long[] seconds = new long[count];
        for (int i = 0; i < count; i++) {
            positions[i] = r.readLong();
            long s = base + RegionSummaryWire.readZigVarLong(r);
            if (s <= 0 || s > bound) {
                throw new WireFormatException("stamps second " + s
                        + " outside (0, now+" + FUTURE_SKEW_ALLOWANCE_SECONDS + "]");
            }
            seconds[i] = s;
        }
        if (r.remaining() != 0) {
            throw new WireFormatException("stamps frame has " + r.remaining() + " trailing bytes");
        }
        return new Stamps(dimension, positions, seconds);
    }
}
