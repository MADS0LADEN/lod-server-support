package dev.vox.lss.common.wire;

import java.util.ArrayList;
import java.util.function.IntFunction;

/**
 * Byte-domain native → v20 section-array translation (the encode direction of the
 * §4.2 translator family): rewrite palettes, never voxels. Consumers: the Phase-0
 * settling measurement (real-encoder frame diff) and the schema-4 store migration
 * walk (a migrated row must byte-equal a fresh v20 encode); the v20→native
 * direction (legacy egress) is the Phase-2 sibling.
 *
 * <p>Identity lookups are injected ({@code globalId -> canonical identity}) so this
 * stays MC-free; the platform registry tables supply them. Rules (§2.1):
 * <ul>
 * <li>Natively-INDEXED containers ship their packed longs verbatim (widths already
 *     vanilla-shaped = inside the v20 sets); only the palette values are rewritten
 *     to dictionary indices.</li>
 * <li>DIRECT containers are re-palettized: enumerate distinct ids from the longs in
 *     first-seen scan order, repack at the v20 width — blocks
 *     {@code n==1 -> 0, else clamp(ceillog2(n), 4, 12)}, biomes
 *     {@code ceillog2(n)} (0-6).</li>
 * <li>The two count shorts and the light layers pass through verbatim (pinned
 *     version-neutral wire fields — recomputation is the live v20 EMITTER's concern,
 *     not the translator's).</li>
 * <li>Dictionary indices are assigned first-seen across the section walk (sections
 *     in order; blocks then biomes within each; palette order within each container)
 *     — deterministic, golden-pinned. A clear column (0 sections) translates to the
 *     empty-dictionary clear frame.</li>
 * </ul>
 */
public final class NativeToV20Translator {
    private NativeToV20Translator() {}

    /**
     * Translates a NATIVE section-array body to the v20 layout.
     *
     * @param blockIdentity global block-state id → canonical identity (strict form;
     *                      validated at dictionary insertion)
     * @param biomeIdentity biome registry id → bare identity
     * @throws WireFormatException on malformed input
     * @throws IllegalArgumentException if a lookup yields a non-canonical identity
     */
    public static byte[] translate(byte[] nativeBody,
                                   IntFunction<String> blockIdentity,
                                   IntFunction<String> biomeIdentity) {
        var column = WireSectionCursor.parse(nativeBody, WireSectionCursor.Layout.NATIVE);
        var dict = new IdentityDictionary();
        var sections = new ArrayList<WireSectionCursor.WireSection>(column.sections().size());
        for (var s : column.sections()) {
            var blocks = convert(s.blocks(), WireSectionCursor.BLOCK_ENTRIES, true, dict, blockIdentity);
            var biomes = convert(s.biomes(), WireSectionCursor.BIOME_ENTRIES, false, dict, biomeIdentity);
            sections.add(new WireSectionCursor.WireSection(s.sectionY(), s.nonEmptyBlockCount(),
                    s.fluidCount(), blocks, biomes, s.blockLight(), s.skyLight()));
        }
        return WireSectionCursor.emit(
                new WireSectionCursor.WireColumn(dict.entries(), sections),
                WireSectionCursor.Layout.V20);
    }

    private static WireSectionCursor.WireContainer convert(WireSectionCursor.WireContainer c,
                                                           int entries, boolean isBlocks,
                                                           IdentityDictionary dict,
                                                           IntFunction<String> identity) {
        if (!c.isDirect()) {
            // Indexed (single-value included): palette ids -> dict indices, longs verbatim.
            int[] palette = new int[c.palette().length];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = dict.indexOf(identityFor(identity, c.palette()[i], isBlocks));
            }
            return new WireSectionCursor.WireContainer(c.bits(), palette, c.data());
        }
        // DIRECT: synthesize a palette from the packed ids (the ViaVersion
        // PaletteType1_18.readValues maneuver), then repack at the v20 width.
        int[] ids = WireSectionCursor.unpack(c.data(), c.bits(), entries);
        // Box-free first-seen remap (thousands of these run per store-migration row —
        // a Map<Integer,Integer> boxes one Integer per voxel lookup).
        var seen = new IntFirstSeen(entries);
        int[] remapped = new int[entries];
        for (int i = 0; i < entries; i++) {
            remapped[i] = seen.indexOf(ids[i]);
        }
        int n = seen.size();
        int[] palette = new int[n];
        for (int p = 0; p < n; p++) {
            palette[p] = dict.indexOf(identityFor(identity, seen.idAt(p), isBlocks));
        }
        int bits = v20Bits(n, isBlocks);
        long[] data = bits == 0 ? new long[0] : WireSectionCursor.pack(remapped, bits);
        return new WireSectionCursor.WireContainer(bits, palette, data);
    }

    /**
     * The DIRECT-EMIT rung (the C6-triggered follow-up to translate-at-producer):
     * converts an already-INDEXED native container descriptor — palette of global ids
     * in wire order plus the verbatim long words at a vanilla-shaped width — without
     * ever materializing or re-parsing native bytes. Byte-equal to {@link #translate}
     * on the same container by the indexed rule above (longs verbatim, palette values
     * rewritten to dictionary indices); callers own the guarantee that the descriptor
     * is indexed-shaped (the NBT transcoder's fallback ladder routes every other shape
     * through the object path, which keeps the translate route).
     */
    public static WireSectionCursor.WireContainer convertIndexed(int bits, int[] paletteIds,
                                                                 long[] data, boolean isBlocks,
                                                                 IdentityDictionary dict,
                                                                 IntFunction<String> identity) {
        int[] palette = new int[paletteIds.length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = dict.indexOf(identityFor(identity, paletteIds[i], isBlocks));
        }
        return new WireSectionCursor.WireContainer(bits, palette, data);
    }

    private static String identityFor(IntFunction<String> lookup, int id, boolean isBlocks) {
        String identity = lookup.apply(id);
        if (identity == null) {
            throw new IllegalArgumentException("no identity for "
                    + (isBlocks ? "block-state" : "biome") + " id " + id);
        }
        return identity;
    }

    /** §2.1 widths: blocks {@code n==1 -> 0 else clamp(ceillog2(n), 4, 12)}; biomes {@code ceillog2(n)}. */
    static int v20Bits(int paletteSize, boolean isBlocks) {
        if (paletteSize <= 1) {
            return 0;
        }
        int ceillog2 = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return isBlocks ? Math.max(4, Math.min(12, ceillog2)) : ceillog2;
    }

    /**
     * Open-addressed {@code int -> first-seen index} map (power-of-two linear probe,
     * {@code -1} = empty) — insertion order retrievable via {@link #idAt}. Ids are
     * non-negative (unpack masks them), so {@code -1} is a safe sentinel.
     */
    private static final class IntFirstSeen {
        private int[] keys;
        private int[] values;
        private int[] order;
        private int size;

        IntFirstSeen(int expectedEntries) {
            int cap = Integer.highestOneBit(Math.max(16, expectedEntries / 4) - 1) << 1;
            this.keys = new int[cap];
            this.values = new int[cap];
            this.order = new int[16];
            java.util.Arrays.fill(this.keys, -1);
        }

        int indexOf(int id) {
            int mask = keys.length - 1;
            int slot = (id * 0x9E3779B9) >>> 16 & mask;
            while (true) {
                int k = keys[slot];
                if (k == id) {
                    return values[slot];
                }
                if (k == -1) {
                    if (size * 2 >= keys.length) {
                        grow();
                        return indexOf(id);
                    }
                    keys[slot] = id;
                    values[slot] = size;
                    if (size == order.length) {
                        order = java.util.Arrays.copyOf(order, size * 2);
                    }
                    order[size] = id;
                    return size++;
                }
                slot = (slot + 1) & mask;
            }
        }

        private void grow() {
            int[] oldKeys = keys, oldValues = values;
            keys = new int[oldKeys.length * 2];
            values = new int[oldKeys.length * 2];
            java.util.Arrays.fill(keys, -1);
            int mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != -1) {
                    int slot = (oldKeys[i] * 0x9E3779B9) >>> 16 & mask;
                    while (keys[slot] != -1) {
                        slot = (slot + 1) & mask;
                    }
                    keys[slot] = oldKeys[i];
                    values[slot] = oldValues[i];
                }
            }
        }

        int size() {
            return size;
        }

        int idAt(int firstSeenIndex) {
            return order[firstSeenIndex];
        }
    }
}
