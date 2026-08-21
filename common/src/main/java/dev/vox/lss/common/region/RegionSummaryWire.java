package dev.vox.lss.common.region;

import dev.vox.lss.common.wire.WireBytes;
import dev.vox.lss.common.wire.WireFormatException;

/**
 * The region-summary wire codec (region-summary-sync-plan.md §4). BOTH platforms
 * encode/decode through these byte[] bodies verbatim — the FarPlayerWire pattern (one
 * codec, two carriers), so wire parity holds by construction.
 *
 * <p>Layouts (version byte first; counts/radii VarInt; coordinates zigzag-VarInt;
 * strings VarInt-UTF):
 * <pre>
 * request := version:u8=1 dimension:utf centerTileX:zig centerTileZ:zig tileRadius:varint
 * summary := version:u8=1 dimension:utf centerTileX:zig centerTileZ:zig tileRadius:varint
 *            stamp[(2r+1)^2]:zigzag-delta-varlong   (row-major: x fastest within z rows)
 * </pre>
 * Stamps are server-clock epoch SECONDS with two reserved sentinels:
 * {@link #STAMP_NO_REGION} (0 — a region file that has NEVER been observed in this
 * server life: nothing was ever on disk to validate against, so the client validates
 * its stamped positions there; the table answers NEVER_CLEAN instead for every
 * doubt-shaped absence — deleted-after-observed, unlistable directory, empty header —
 * see {@code RegionStampTable.tileStampSeconds}) and {@link #STAMP_NEVER_CLEAN} (-1
 * pre-delta — unknown/unresolvable: the client validates NOTHING in the tile). The
 * delta chain runs over the raw values including sentinels (zigzag absorbs the sign).
 *
 * <p>Hostile-decode discipline (the FarPlayerWire bar): version mismatch, negative or
 * over-{@link #MAX_SUMMARY_TILE_RADIUS} radii, oversized dimension strings, trailing
 * bytes, and short frames all throw {@link WireFormatException}; the tile-count
 * arithmetic runs in long so a hostile radius cannot overflow int. Bounded allocation:
 * the radius cap bounds the stamp array at {@code (2*65+1)^2} longs (~137 KB) before
 * any allocation happens.
 */
public final class RegionSummaryWire {

    public static final int VERSION = 1;
    /** ceil(MAX_LOD_DISTANCE / 32) + 1 = ceil(2048/32) + 1 — one ring of margin over
     *  the largest configurable LOD disc, checked at DECODE on both sides. */
    public static final int MAX_SUMMARY_TILE_RADIUS = 65;
    /** Center-coordinate domain (P2 client review MAJOR-1): far beyond the world
     *  border's ~59k-tile reach, but small enough that {@code center ± radius} and the
     *  consumers' tile→leaf shifts can never overflow int — an unbounded hostile
     *  center made the client's window walk wrap (demonstrated AIOOBE) and alias
     *  {@code tileX << 2} onto REAL leaves. Bounding the DOMAIN kills the whole class
     *  on both sides instead of patching each walk. */
    public static final int MAX_SUMMARY_TILE_ABS = 2_000_000;
    public static final int MAX_DIMENSION_UTF_BYTES = 256;

    /** No region file: nothing on disk to validate against. */
    public static final long STAMP_NO_REGION = 0L;
    /** Unknown/unresolvable: the client must validate nothing in this tile. Wire form
     *  is -1 (pre-delta); the in-memory form is normalized to this constant. */
    public static final long STAMP_NEVER_CLEAN = Long.MAX_VALUE;
    private static final long WIRE_NEVER_CLEAN = -1L;

    /** C2S: "send me the stamp window for this dimension around this tile center".
     *  Compact-ctor validated: a Request that EXISTS is wire-legal. */
    public record Request(String dimension, int centerTileX, int centerTileZ, int tileRadius) {
        public Request {
            requireDimension(dimension);
            boundedCenter(centerTileX);
            boundedCenter(centerTileZ);
            boundedRadius(tileRadius);
        }
    }

    /** S2C: the stamp window. {@code stampSeconds} is row-major over the square
     *  {@code [centerX-r..centerX+r] x [centerZ-r..centerZ+r]}, x fastest within z
     *  rows; values are epoch seconds, {@link #STAMP_NO_REGION}, or
     *  {@link #STAMP_NEVER_CLEAN} (already normalized from the wire form).
     *  Compact-ctor validated: a Summary that EXISTS is wire-legal. */
    public record Summary(String dimension, int centerTileX, int centerTileZ,
                          int tileRadius, long[] stampSeconds) {
        public Summary {
            requireDimension(dimension);
            boundedCenter(centerTileX);
            boundedCenter(centerTileZ);
            long side = 2L * boundedRadius(tileRadius) + 1;
            if (stampSeconds == null || stampSeconds.length != side * side) {
                throw new WireFormatException("summary stamps length "
                        + (stampSeconds == null ? "null" : stampSeconds.length)
                        + " != (2r+1)^2 = " + (side * side));
            }
        }
    }

    // ---- request ----

    public static byte[] encodeRequest(Request req) {
        // Bounds guaranteed by the record's compact ctor.
        var w = new WireBytes.Writer(16 + req.dimension().length());
        w.writeByte(VERSION);
        w.writeUtf(req.dimension());
        writeZigVarLong(w, req.centerTileX());
        writeZigVarLong(w, req.centerTileZ());
        w.writeVarInt(req.tileRadius());
        return w.toByteArray();
    }

    public static Request decodeRequest(byte[] body) {
        var r = new WireBytes.Reader(body);
        requireVersion(r, "summary request");
        String dimension = r.readUtf(MAX_DIMENSION_UTF_BYTES);
        int cx = zigToInt(readZigVarLong(r), "centerTileX");
        int cz = zigToInt(readZigVarLong(r), "centerTileZ");
        int radius = boundedRadius(r.readVarInt());
        requireDrained(r, "summary request");
        return new Request(dimension, cx, cz, radius);
    }

    // ---- summary ----

    public static byte[] encodeSummary(Summary s) {
        // Bounds + length guaranteed by the record's compact ctor.
        var w = new WireBytes.Writer(32 + s.stampSeconds().length * 2);
        w.writeByte(VERSION);
        w.writeUtf(s.dimension());
        writeZigVarLong(w, s.centerTileX());
        writeZigVarLong(w, s.centerTileZ());
        w.writeVarInt(s.tileRadius());
        long prev = 0;
        for (long stamp : s.stampSeconds()) {
            long wire = stamp == STAMP_NEVER_CLEAN ? WIRE_NEVER_CLEAN : stamp;
            if (wire < WIRE_NEVER_CLEAN) {
                throw new WireFormatException("summary stamp " + stamp + " below the domain");
            }
            writeZigVarLong(w, wire - prev);
            prev = wire;
        }
        return w.toByteArray();
    }

    public static Summary decodeSummary(byte[] body) {
        var r = new WireBytes.Reader(body);
        requireVersion(r, "summary");
        String dimension = r.readUtf(MAX_DIMENSION_UTF_BYTES);
        int cx = zigToInt(readZigVarLong(r), "centerTileX");
        int cz = zigToInt(readZigVarLong(r), "centerTileZ");
        int radius = boundedRadius(r.readVarInt());
        long side = 2L * radius + 1;
        long tiles = side * side; // long arithmetic — a hostile radius must not wrap
        if (r.remaining() < tiles) {
            // Every stamp costs at least one varlong byte: a frame shorter than its own
            // tile count is hostile/truncated — reject BEFORE the ~137 KB allocation.
            throw new WireFormatException("summary frame has " + r.remaining()
                    + " bytes remaining for " + tiles + " tiles");
        }
        long[] stamps = new long[(int) tiles];
        long prev = 0;
        for (int i = 0; i < stamps.length; i++) {
            long wire = prev + readZigVarLong(r);
            if (wire < WIRE_NEVER_CLEAN) {
                throw new WireFormatException("summary stamp " + wire + " below the domain");
            }
            prev = wire;
            stamps[i] = wire == WIRE_NEVER_CLEAN ? STAMP_NEVER_CLEAN : wire;
        }
        requireDrained(r, "summary");
        return new Summary(dimension, cx, cz, radius, stamps);
    }

    // ---- guards ----

    private static void requireVersion(WireBytes.Reader r, String what) {
        int v = r.readUnsignedByte();
        if (v != VERSION) {
            throw new WireFormatException(what + " version " + v + " != " + VERSION);
        }
    }

    private static int boundedRadius(int radius) {
        if (radius < 0 || radius > MAX_SUMMARY_TILE_RADIUS) {
            throw new WireFormatException("tile radius " + radius + " outside [0, "
                    + MAX_SUMMARY_TILE_RADIUS + "]");
        }
        return radius;
    }

    private static void boundedCenter(int center) {
        if (center < -MAX_SUMMARY_TILE_ABS || center > MAX_SUMMARY_TILE_ABS) {
            throw new WireFormatException("tile center " + center + " outside ±"
                    + MAX_SUMMARY_TILE_ABS);
        }
    }

    /** Both records' dimension guard — encode-side too (the decode cap alone would let
     *  a local bug emit a frame the twin then rejects). */
    private static void requireDimension(String dimension) {
        if (dimension == null
                || dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_DIMENSION_UTF_BYTES) {
            throw new WireFormatException("dimension null or over "
                    + MAX_DIMENSION_UTF_BYTES + " UTF bytes");
        }
    }

    private static int zigToInt(long v, String what) {
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new WireFormatException(what + " outside int range: " + v);
        }
        return (int) v;
    }

    private static void requireDrained(WireBytes.Reader r, String what) {
        if (r.remaining() != 0) {
            throw new WireFormatException(what + " frame has " + r.remaining()
                    + " trailing bytes");
        }
    }

    // ---- zigzag varlong (WireBytes carries only 32-bit varints; stamps are longs) ----

    static void writeZigVarLong(WireBytes.Writer w, long value) {
        long zig = (value << 1) ^ (value >> 63);
        while ((zig & ~0x7FL) != 0) {
            w.writeByte((int) ((zig & 0x7F) | 0x80));
            zig >>>= 7;
        }
        w.writeByte((int) zig);
    }

    static long readZigVarLong(WireBytes.Reader r) {
        long zig = 0;
        int shift = 0;
        while (true) {
            int b = r.readUnsignedByte();
            zig |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift >= 64) {
                throw new WireFormatException("varlong too long");
            }
        }
        return (zig >>> 1) ^ -(zig & 1);
    }

    private RegionSummaryWire() {}
}
