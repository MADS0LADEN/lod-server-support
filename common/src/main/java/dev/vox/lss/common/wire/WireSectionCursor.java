package dev.vox.lss.common.wire;

import java.nio.charset.StandardCharsets;
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
 * Vanilla's width→shape tables also make some widths ILLEGAL on the wire even
 * below the threshold: an indexed BLOCK container is only ever emitted at bits
 * 4-8 (1-3 map to the 4-bit linear shape, so a bits-1..3 frame desyncs a real
 * {@code section.read} — 26.2 {@code Strategy$1} disassembly). The cursor
 * enforces that on BOTH parse and emit, so "the cursor accepted it" implies
 * "vanilla can read it". Biomes have no such gap (1/2/3-bit linear shapes exist).
 *
 * <p><b>V20 layout</b> (§2.1): a leading shared identity dictionary, then the same
 * section shape with palettes of VarInt DICTIONARY INDICES and NO DIRECT mode:
 * <pre>
 * VarInt dictCount; repeat: VarInt len + UTF-8 canonical identity
 * VarInt sectionCount; sections as above, but every bits&gt;0 container carries a
 * palette list (widths: blocks 0 or 4-12, biomes 0-6; bits==0 single index)
 * </pre>
 * The §2.1 block width FLOOR (4) is enforced along with the ceilings, and every
 * palette value is range-checked against the dictionary — referential integrity
 * is the cursor's job, not each consumer's.
 *
 * <p>Hostile-input containment (the plan's hostile-allocation pin, held here for
 * every consumer at once): all failures throw {@link WireFormatException}; no
 * wire-claimed count sizes an allocation before it is bounded — palette counts are
 * capped by the container's entry count, dictionary entries and light reads are
 * bounded by bytes actually remaining, and the dictionary list grows dynamically
 * (dictCount never pre-sizes anything). {@code parse -> emit} is byte-identity on
 * well-formed input (both layouts, corpus-pinned); VarInts are canonical on emit,
 * so only a non-minimally-encoded (hence non-native) input can re-emit differently.
 *
 * <p>The records expose their backing arrays WITHOUT defensive copies — the
 * translator's verbatim-longs pass-through depends on it. Consumers must treat
 * {@code palette()}/{@code data()}/light arrays as immutable; mutating one corrupts
 * whatever shares it. Dictionary entries are deliberately NOT
 * {@code IdentityCodec}-validated at parse: an unknown-but-well-formed identity
 * from a newer peer must reach the §3 fallback ladder, not die here — do not
 * "fix" this into a validating parse.
 */
public final class WireSectionCursor {
    private WireSectionCursor() {}

    public enum Layout { NATIVE, V20 }

    static final int BLOCK_ENTRIES = 4096;
    static final int BIOME_ENTRIES = 64;
    static final int NATIVE_BLOCK_PALETTE_MAX_BITS = 8;
    static final int NATIVE_BIOME_PALETTE_MAX_BITS = 3;
    /** Indexed block containers exist only at 4-8 bits in vanilla's shape table —
     *  1-3 desync a real {@code section.read} (blocks only; biome linear shapes
     *  cover 1-3). Shared by both layouts: v20 keeps the same floor (§2.1). */
    static final int BLOCK_MIN_INDEXED_BITS = 4;
    /** v20 palette width ceilings (§2.1); parse-enforced so a hostile width cannot
     *  claim absurd data lengths, emit-enforced so we never produce one. */
    static final int V20_BLOCK_MAX_BITS = 12;
    static final int V20_BIOME_MAX_BITS = 6;
    private static final int LIGHT_BYTES = 2048;
    /** Identity strings are short (~20-40 B measured); 4 KiB tolerates absurd modded
     *  names while bounding a hostile length claim. Enforced on parse AND emit. */
    static final int MAX_IDENTITY_BYTES = 4096;

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
     * (the skip primitive: same validations as {@link #parse}, allocations are
     * transient). The {@code limit} overload walks a body embedded in a larger
     * buffer with a known end.
     */
    public static int scan(byte[] body, int offset, Layout layout) {
        return scan(body, offset, body.length, layout);
    }

    public static int scan(byte[] body, int offset, int limit, Layout layout) {
        var in = new WireBytes.Reader(body, offset, limit);
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
            // Grows dynamically — the wire-claimed count must never pre-size an
            // allocation (§2.3's hostile-allocation pin, dictCount included).
            var dict = new ArrayList<String>();
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
        int dictSize = dictionary.size();
        var sections = new ArrayList<WireSection>();
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = in.readByte();
            int nonEmpty = in.readShort();
            // V-2/S1: the second count short is NATIVE-per-line (26.x carries the
            // split pair, 1.21.x one short — NativeSectionShape); V20 ALWAYS carries
            // both (line-invariant wire spec, never descriptor-derived).
            int fluid = (layout == Layout.V20
                    || NativeSectionShape.NATIVE_COUNT_SHORTS == 2) ? in.readShort() : 0;
            WireContainer blocks = readContainer(in, layout, BLOCK_ENTRIES, true, dictSize);
            WireContainer biomes = readContainer(in, layout, BIOME_ENTRIES, false, dictSize);
            byte[] blockLight = in.readByte() != 0 ? in.readBytes(LIGHT_BYTES) : null;
            byte[] skyLight = in.readByte() != 0 ? in.readBytes(LIGHT_BYTES) : null;
            sections.add(new WireSection(sectionY, nonEmpty, fluid, blocks, biomes,
                    blockLight, skyLight));
        }
        return new WireColumn(dictionary, sections);
    }

    private static WireContainer readContainer(WireBytes.Reader in, Layout layout,
                                               int entries, boolean isBlocks, int dictSize) {
        int bits = in.readUnsignedByte();
        checkWidth(bits, layout, isBlocks);
        if (bits == 0) {
            return new WireContainer(0,
                    new int[] { readPaletteValue(in, layout, isBlocks, dictSize) }, EMPTY_DATA);
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
                palette[i] = readPaletteValue(in, layout, isBlocks, dictSize);
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

    /** Palette values are registry ids (native) or dictionary indices (v20) — never
     *  negative, and v20 indices must reference the dictionary that precedes them. */
    private static int readPaletteValue(WireBytes.Reader in, Layout layout,
                                        boolean isBlocks, int dictSize) {
        int v = in.readVarIntCount((isBlocks ? "block" : "biome") + " palette value");
        if (layout == Layout.V20 && v >= dictSize) {
            throw new WireFormatException("palette index " + v + " outside dictionary of " + dictSize);
        }
        return v;
    }

    /** The width/shape legality shared by parse and emit (see class javadoc). */
    private static void checkWidth(int bits, Layout layout, boolean isBlocks) {
        int maxBits = layout == Layout.V20
                ? (isBlocks ? V20_BLOCK_MAX_BITS : V20_BIOME_MAX_BITS)
                : 31;  // native DIRECT width is registry-derived; cap only for sanity
        if (bits > maxBits) {
            throw new WireFormatException((isBlocks ? "block" : "biome")
                    + " container width " + bits + " exceeds " + layout + " max " + maxBits);
        }
        if (isBlocks && bits >= 1 && bits < BLOCK_MIN_INDEXED_BITS) {
            throw new WireFormatException("block container width " + bits
                    + " has no vanilla shape (indexed blocks are 4-8"
                    + (layout == Layout.V20 ? "; v20 floor is 4" : "") + ")");
        }
    }

    private static final long[] EMPTY_DATA = new long[0];

    // ---- emit -----------------------------------------------------------------

    public static byte[] emit(WireColumn column, Layout layout) {
        var out = new WireBytes.Writer(estimateSize(column));
        int dictSize = column.dictionary().size();
        if (layout == Layout.V20) {
            if (column.sections().isEmpty() != column.dictionary().isEmpty()) {
                throw new WireFormatException("refusing to emit a clear-column-invariant violation: dict="
                        + dictSize + " sections=" + column.sections().size());
            }
            out.writeVarInt(dictSize);
            for (String identity : column.dictionary()) {
                if (identity.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTITY_BYTES) {
                    throw new WireFormatException("dictionary entry exceeds " + MAX_IDENTITY_BYTES
                            + " UTF-8 bytes — our own parse would reject it");
                }
                out.writeUtf(identity);
            }
        } else if (dictSize != 0) {
            throw new WireFormatException("NATIVE layout has no dictionary");
        }
        out.writeVarInt(column.sections().size());
        for (WireSection s : column.sections()) {
            if (s.sectionY() < Byte.MIN_VALUE || s.sectionY() > Byte.MAX_VALUE) {
                throw new WireFormatException("sectionY " + s.sectionY() + " outside byte range");
            }
            checkShortRange(s.nonEmptyBlockCount(), "nonEmptyBlockCount");
            checkShortRange(s.fluidCount(), "fluidCount");
            out.writeByte(s.sectionY());
            if (layout == Layout.V20 || NativeSectionShape.NATIVE_COUNT_SHORTS == 2) {
                out.writeShort(s.nonEmptyBlockCount());
                out.writeShort(s.fluidCount());
            } else {
                // One-short NATIVE emit: the LINE-level fold (V-2 review MAJOR-1 —
                // recorded 1.21.11: nonEmpty + fluid, because that line's vanilla
                // counts fluid cells into its single count and the consumer of
                // cursor-emitted native bytes is always a CLIENT of that line).
                // A line rule, deliberately not a family fold: this cursor is common
                // and serves BOTH families' v20→native egress. The folded SUM gets
                // its own range check (the recorded flavor's rule).
                int folded = NativeSectionShape.foldedCountForNativeHeader(
                        s.nonEmptyBlockCount(), s.fluidCount());
                checkShortRange(folded, "foldedCount");
                out.writeShort(folded);
            }
            writeContainer(out, s.blocks(), layout, BLOCK_ENTRIES, true, dictSize);
            writeContainer(out, s.biomes(), layout, BIOME_ENTRIES, false, dictSize);
            writeLight(out, s.blockLight());
            writeLight(out, s.skyLight());
        }
        return out.toByteArray();
    }

    private static void checkShortRange(int v, String what) {
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            throw new WireFormatException(what + " " + v + " outside short range");
        }
    }

    private static void writeContainer(WireBytes.Writer out, WireContainer c, Layout layout,
                                       int entries, boolean isBlocks, int dictSize) {
        int bits = c.bits();
        if (layout == Layout.V20 && c.isDirect()) {
            throw new WireFormatException("v20 has no DIRECT mode");
        }
        checkWidth(bits, layout, isBlocks);
        out.writeByte(bits);
        if (bits == 0) {
            if (c.isDirect() || c.palette().length != 1 || c.data().length != 0) {
                throw new WireFormatException("malformed single-value container");
            }
            out.writeVarInt(checkPaletteValue(c.palette()[0], layout, isBlocks, dictSize));
            return;
        }
        if (!c.isDirect()) {
            int paletteThreshold = isBlocks
                    ? NATIVE_BLOCK_PALETTE_MAX_BITS : NATIVE_BIOME_PALETTE_MAX_BITS;
            if (layout == Layout.NATIVE && bits > paletteThreshold) {
                // Asymmetry guard (review C1-16): parse treats bits>threshold as DIRECT,
                // so an indexed container at that width would re-parse as garbage.
                throw new WireFormatException("indexed " + (isBlocks ? "block" : "biome")
                        + " container at DIRECT width " + bits);
            }
            out.writeVarInt(c.palette().length);
            for (int v : c.palette()) {
                out.writeVarInt(checkPaletteValue(v, layout, isBlocks, dictSize));
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

    private static int checkPaletteValue(int v, Layout layout, boolean isBlocks, int dictSize) {
        if (v < 0 || (layout == Layout.V20 && v >= dictSize)) {
            throw new WireFormatException((isBlocks ? "block" : "biome") + " palette value " + v
                    + (layout == Layout.V20 ? " outside dictionary of " + dictSize : " is negative"));
        }
        return v;
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

    /** Close-enough writer pre-size so a real column pays no growth copies. */
    private static int estimateSize(WireColumn column) {
        int size = 16;
        for (String identity : column.dictionary()) {
            size += 5 + identity.length() * 2;
        }
        for (WireSection s : column.sections()) {
            size += 7 + 2 * (1 + LIGHT_BYTES)
                    + containerEstimate(s.blocks()) + containerEstimate(s.biomes());
        }
        return size;
    }

    private static int containerEstimate(WireContainer c) {
        return 1 + 5 + (c.isDirect() ? 0 : c.palette().length * 3) + c.data().length * 8;
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
