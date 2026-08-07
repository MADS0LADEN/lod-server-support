package dev.vox.lss.common.wire;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural walker for the column SECTION-ARRAY body in both wire layouts — the
 * transcoder's layout knowledge factored out (cross-version-identity-encoding-plan
 * §4.2) so the settling measurement, the legacy egress translators, and the store
 * migration walk all share one parser/emitter instead of three.
 *
 * <p><b>NATIVE layout</b> (protocol ≤19 section bytes, what `SectionSerializer` /
 * `NbtSectionSerializer` emit today):
 * <pre>
 * VarInt sectionCount
 * repeat: byte sectionY; short nonEmptyBlockCount; short fluidCount;
 *         blocks container(4096 entries); biomes container(64 entries);
 *         bool hasBlockLight [+2048 B]; bool hasSkyLight [+2048 B]
 * container: byte bits;
 *   bits==0            -> VarInt single palette value, no data words
 *   0&lt;bits&lt;=threshold  -> VarInt paletteCount + paletteCount VarInt values, then data
 *   bits&gt;threshold     -> DIRECT: NO palette list, data holds registry ids
 * data: ceil(entries / (64/bits)) raw big-endian longs, NO length prefix
 * thresholds: blocks 8, biomes 3 (vanilla's width→shape strategy tables)
 * </pre>
 *
 * <p><b>V20 layout</b> (§2.1): a leading shared identity dictionary, then the same
 * section shape with palettes of VarInt DICTIONARY INDICES and NO DIRECT mode:
 * <pre>
 * VarInt dictCount; repeat: VarInt len + UTF-8 canonical identity
 * VarInt sectionCount; sections as above, but every bits&gt;0 container carries a
 * palette list (widths: blocks 4-12, biomes 1-6; bits==0 single index)
 * </pre>
 *
 * <p>Hostile-input containment (the plan's hostile-allocation pin, held here for
 * every consumer at once): all failures throw {@link WireFormatException}; no
 * wire-claimed count sizes an allocation before it is bounded — palette counts are
 * capped by the container's entry count, dictionary and light reads are bounded by
 * bytes actually remaining. {@code parse -> emit} is byte-identity on well-formed
 * input (both layouts, corpus-pinned); VarInts are canonical on emit, so only a
 * non-minimally-encoded (hence non-native) input can re-emit differently.
 */
public final class WireSectionCursor {
    private WireSectionCursor() {}

    public enum Layout { NATIVE, V20 }

    static final int BLOCK_ENTRIES = 4096;
    static final int BIOME_ENTRIES = 64;
    static final int NATIVE_BLOCK_PALETTE_MAX_BITS = 8;
    static final int NATIVE_BIOME_PALETTE_MAX_BITS = 3;
    /** v20 palette width ceilings (§2.1); parse-enforced so a hostile width cannot
     *  claim absurd data lengths, emit-enforced so we never produce one. */
    static final int V20_BLOCK_MAX_BITS = 12;
    static final int V20_BIOME_MAX_BITS = 6;
    private static final int LIGHT_BYTES = 2048;
    /** Identity strings are short (~20-40 B measured); 4 KiB tolerates absurd modded
     *  names while bounding a hostile length claim. */
    private static final int MAX_IDENTITY_BYTES = 4096;

    /**
     * One palette container. {@code palette == null} means DIRECT (native layout
     * only): the data words hold registry ids at {@code bits} width. A single-value
     * container is {@code bits == 0}, one palette entry, empty data.
     */
    public record WireContainer(int bits, int[] palette, long[] data) {
        public boolean isDirect() {
            return palette == null;
        }
    }

    /** {@code blockLight}/{@code skyLight} are 2048 bytes when present, else null. */
    public record WireSection(int sectionY, int nonEmptyBlockCount, int fluidCount,
                              WireContainer blocks, WireContainer biomes,
                              byte[] blockLight, byte[] skyLight) {}

    /** {@code dictionary} is empty for the NATIVE layout. */
    public record WireColumn(List<String> dictionary, List<WireSection> sections) {}

    // ---- parse ----------------------------------------------------------------

    public static WireColumn parse(byte[] body, Layout layout) {
        var in = new WireBytes.Reader(body);
        WireColumn column = readColumn(in, layout);
        if (in.remaining() != 0) {
            throw new WireFormatException(in.remaining() + " trailing bytes after section array");
        }
        return column;
    }

    /**
     * Walks one column body without retaining it, returning the consumed byte count
     * (the skip primitive: same validations as {@link #parse}, no per-section
     * allocations kept).
     */
    public static int scan(byte[] body, int offset, Layout layout) {
        var in = new WireBytes.Reader(body, offset, body.length);
        readColumn(in, layout);
        return in.position() - offset;
    }

    private static WireColumn readColumn(WireBytes.Reader in, Layout layout) {
        List<String> dictionary = List.of();
        if (layout == Layout.V20) {
            int dictCount = in.readVarIntCount("dictCount");
            if (dictCount > in.remaining()) {  // every entry is >= 1 byte
                throw new WireFormatException("dictCount " + dictCount + " exceeds remaining bytes");
            }
            var dict = new ArrayList<String>(dictCount);
            for (int i = 0; i < dictCount; i++) {
                dict.add(in.readUtf(MAX_IDENTITY_BYTES));
            }
            dictionary = dict;
        }
        int sectionCount = in.readVarIntCount("sectionCount");
        if (layout == Layout.V20 && (sectionCount == 0) != dictionary.isEmpty()) {
            throw new WireFormatException("clear-column invariant violated: dictCount="
                    + dictionary.size() + " sectionCount=" + sectionCount);
        }
        // Sections accumulate dynamically, bounded by buffer exhaustion — the claimed
        // count never sizes anything (each section is >= 8 bytes on the wire).
        if (sectionCount > in.remaining()) {
            throw new WireFormatException("sectionCount " + sectionCount + " exceeds remaining bytes");
        }
        var sections = new ArrayList<WireSection>();
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = in.readByte();
            int nonEmpty = in.readShort();
            int fluid = in.readShort();
            WireContainer blocks = readContainer(in, layout, BLOCK_ENTRIES, true);
            WireContainer biomes = readContainer(in, layout, BIOME_ENTRIES, false);
            byte[] blockLight = in.readByte() != 0 ? in.readBytes(LIGHT_BYTES) : null;
            byte[] skyLight = in.readByte() != 0 ? in.readBytes(LIGHT_BYTES) : null;
            sections.add(new WireSection(sectionY, nonEmpty, fluid, blocks, biomes,
                    blockLight, skyLight));
        }
        return new WireColumn(dictionary, sections);
    }

    private static WireContainer readContainer(WireBytes.Reader in, Layout layout,
                                               int entries, boolean isBlocks) {
        int bits = in.readUnsignedByte();
        int maxBits = layout == Layout.V20
                ? (isBlocks ? V20_BLOCK_MAX_BITS : V20_BIOME_MAX_BITS)
                : 31;  // native DIRECT width is registry-derived; cap only for sanity
        if (bits > maxBits) {
            throw new WireFormatException((isBlocks ? "block" : "biome")
                    + " container width " + bits + " exceeds " + layout + " max " + maxBits);
        }
        if (bits == 0) {
            return new WireContainer(0, new int[] { in.readVarInt() }, EMPTY_DATA);
        }
        int[] palette = null;
        int paletteThreshold = isBlocks ? NATIVE_BLOCK_PALETTE_MAX_BITS : NATIVE_BIOME_PALETTE_MAX_BITS;
        if (layout == Layout.V20 || bits <= paletteThreshold) {
            int count = in.readVarIntCount("paletteCount");
            if (count == 0 || count > entries) {
                throw new WireFormatException("palette count " + count + " outside [1, " + entries + "]");
            }
            palette = new int[count];
            for (int i = 0; i < count; i++) {
                palette[i] = in.readVarInt();
            }
        }
        int valuesPerLong = 64 / bits;
        int longCount = (entries + valuesPerLong - 1) / valuesPerLong;
        long[] data = new long[longCount];
        for (int i = 0; i < longCount; i++) {
            data[i] = in.readLong();
        }
        return new WireContainer(bits, palette, data);
    }

    private static final long[] EMPTY_DATA = new long[0];

    // ---- emit -----------------------------------------------------------------

    public static byte[] emit(WireColumn column, Layout layout) {
        var out = new WireBytes.Writer(1024);
        if (layout == Layout.V20) {
            if (column.sections().isEmpty() != column.dictionary().isEmpty()) {
                throw new WireFormatException("refusing to emit a clear-column-invariant violation: dict="
                        + column.dictionary().size() + " sections=" + column.sections().size());
            }
            out.writeVarInt(column.dictionary().size());
            for (String identity : column.dictionary()) {
                out.writeUtf(identity);
            }
        } else if (!column.dictionary().isEmpty()) {
            throw new WireFormatException("NATIVE layout has no dictionary");
        }
        out.writeVarInt(column.sections().size());
        for (WireSection s : column.sections()) {
            out.writeByte(s.sectionY());
            out.writeShort(s.nonEmptyBlockCount());
            out.writeShort(s.fluidCount());
            writeContainer(out, s.blocks(), layout, BLOCK_ENTRIES, true);
            writeContainer(out, s.biomes(), layout, BIOME_ENTRIES, false);
            writeLight(out, s.blockLight());
            writeLight(out, s.skyLight());
        }
        return out.toByteArray();
    }

    private static void writeContainer(WireBytes.Writer out, WireContainer c, Layout layout,
                                       int entries, boolean isBlocks) {
        int bits = c.bits();
        if (layout == Layout.V20 && c.isDirect()) {
            throw new WireFormatException("v20 has no DIRECT mode");
        }
        if (layout == Layout.V20 && bits > (isBlocks ? V20_BLOCK_MAX_BITS : V20_BIOME_MAX_BITS)) {
            throw new WireFormatException("v20 " + (isBlocks ? "block" : "biome")
                    + " width " + bits + " out of range");
        }
        out.writeByte(bits);
        if (bits == 0) {
            if (c.isDirect() || c.palette().length != 1 || c.data().length != 0) {
                throw new WireFormatException("malformed single-value container");
            }
            out.writeVarInt(c.palette()[0]);
            return;
        }
        if (!c.isDirect()) {
            out.writeVarInt(c.palette().length);
            for (int v : c.palette()) {
                out.writeVarInt(v);
            }
        }
        int valuesPerLong = 64 / bits;
        int expected = (entries + valuesPerLong - 1) / valuesPerLong;
        if (c.data().length != expected) {
            throw new WireFormatException("container data has " + c.data().length
                    + " longs, layout implies " + expected);
        }
        for (long l : c.data()) {
            out.writeLong(l);
        }
    }

    private static void writeLight(WireBytes.Writer out, byte[] light) {
        if (light == null) {
            out.writeByte(0);
            return;
        }
        if (light.length != LIGHT_BYTES) {
            throw new WireFormatException("light layer of " + light.length + " bytes");
        }
        out.writeByte(1);
        out.writeBytes(light);
    }

    // ---- packed-array helpers (SimpleBitStorage semantics) ---------------------

    /**
     * Unpacks {@code entries} values at {@code bits} width: LSB-first within each
     * long, no value crosses a long boundary — vanilla {@code SimpleBitStorage} /
     * the transcoder's histogram walk.
     */
    public static int[] unpack(long[] data, int bits, int entries) {
        if (bits <= 0 || bits > 32) {
            throw new WireFormatException("unpack width " + bits);
        }
        int valuesPerLong = 64 / bits;
        int expected = (entries + valuesPerLong - 1) / valuesPerLong;
        if (data.length != expected) {
            throw new WireFormatException("unpack over " + data.length + " longs, expected " + expected);
        }
        long mask = (1L << bits) - 1;
        int[] out = new int[entries];
        for (int i = 0; i < entries; i++) {
            out[i] = (int) ((data[i / valuesPerLong] >>> ((i % valuesPerLong) * bits)) & mask);
        }
        return out;
    }

    /** Inverse of {@link #unpack}; values must fit {@code bits}. */
    public static long[] pack(int[] values, int bits) {
        if (bits <= 0 || bits > 32) {
            throw new WireFormatException("pack width " + bits);
        }
        int valuesPerLong = 64 / bits;
        long[] out = new long[(values.length + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            if (v < 0 || (bits < 32 && v >= (1 << bits))) {
                throw new WireFormatException("value " + v + " does not fit " + bits + " bits");
            }
            out[i / valuesPerLong] |= ((long) v) << ((i % valuesPerLong) * bits);
        }
        return out;
    }
}
