package dev.vox.lss.common.wire;

import java.util.ArrayList;
import java.util.function.ToIntFunction;

/**
 * Byte-domain v20 → NATIVE section-array translation — the decode/egress direction of
 * the §4.2 translator family. Consumers: the CLIENT decode (§2.3 — translate a v20
 * frame into the client's OWN native layout with the client's registry, then feed the
 * existing {@code section.read} path; the identity→id resolver IS the §3 fallback
 * ladder, injected), the C2 legacy egress rungs (server registry — exact and lossless
 * same-version), and the C4 mixed-format store serving.
 *
 * <p>Rules (§4.2):
 * <ul>
 * <li>Identity → id through the injected resolver, memoized per column (the dictionary
 *     is resolved ONCE; palettes reference resolutions by index).</li>
 * <li><b>Palette collapse + packed-index remap</b> (the Via lesson, implemented and
 *     pinned from day one): two dictionary identities may resolve to ONE id (cannot
 *     happen same-version; CAN through fallbacks or the §5.3 edge) — native palettes
 *     tolerate duplicates, but collapsing keeps the emit minimal and the remap is the
 *     correctness-critical half. Collapse preserves first-occurrence order.</li>
 * <li>Vanilla-shaped sections ship their packed longs VERBATIM (widths 4-8 blocks /
 *     0-3 biomes with an unchanged collapsed size); v20-custom widths (9-12 blocks,
 *     4-6 biomes) and any width whose collapsed palette no longer fits re-emit as
 *     native DIRECT at the injected registry width (blocks) / {@code ceillog2(size)}
 *     (biomes) when above the native palette thresholds, or repack indexed at the
 *     vanilla width otherwise.</li>
 * <li>Count shorts and light layers pass through verbatim (pinned version-neutral
 *     fields; a client whose section shape wants different counts recomputes locally
 *     — §2.1).</li>
 * <li>A clear column ({0,0}) translates to the native clear ({0}).</li>
 * </ul>
 */
public final class V20ToNativeTranslator {
    private V20ToNativeTranslator() {}

    /**
     * @param v20Body        the v20 section-array body
     * @param blockIdResolver canonical block identity → native global id (the client
     *                        injects the §3 fallback ladder; the server injects the
     *                        exact registry inverse). Must ALWAYS return a valid id —
     *                        fallback policy lives in the resolver, never here.
     * @param biomeIdResolver bare biome identity → native biome id (same contract)
     * @param blockRegistrySize the native block-state registry size (DIRECT width =
     *                        ceillog2(size) — the receiving side's registry)
     * @param biomeRegistrySize the native biome registry size
     */
    public static byte[] translate(byte[] v20Body,
                                   ToIntFunction<String> blockIdResolver,
                                   ToIntFunction<String> biomeIdResolver,
                                   int blockRegistrySize, int biomeRegistrySize) {
        var column = WireSectionCursor.parse(v20Body, WireSectionCursor.Layout.V20);
        // Resolve the dictionary ONCE; every palette entry is a lookup by index.
        var dict = column.dictionary();
        int[] blockIdFor = new int[dict.size()];
        int[] biomeIdFor = new int[dict.size()];
        java.util.Arrays.fill(blockIdFor, -1);
        java.util.Arrays.fill(biomeIdFor, -1);

        var sections = new ArrayList<WireSectionCursor.WireSection>(column.sections().size());
        for (var s : column.sections()) {
            var blocks = convert(s.blocks(), WireSectionCursor.BLOCK_ENTRIES, true,
                    dict, blockIdFor, blockIdResolver, blockRegistrySize);
            var biomes = convert(s.biomes(), WireSectionCursor.BIOME_ENTRIES, false,
                    dict, biomeIdFor, biomeIdResolver, biomeRegistrySize);
            sections.add(new WireSectionCursor.WireSection(s.sectionY(), s.nonEmptyBlockCount(),
                    s.fluidCount(), blocks, biomes, s.blockLight(), s.skyLight()));
        }
        return WireSectionCursor.emit(
                new WireSectionCursor.WireColumn(java.util.List.of(), sections),
                WireSectionCursor.Layout.NATIVE);
    }

    private static WireSectionCursor.WireContainer convert(WireSectionCursor.WireContainer c,
                                                           int entries, boolean isBlocks,
                                                           java.util.List<String> dict,
                                                           int[] memo, ToIntFunction<String> resolver,
                                                           int registrySize) {
        // Resolve this container's palette of dict indices to native ids (memoized).
        int[] resolved = new int[c.palette().length];
        for (int i = 0; i < resolved.length; i++) {
            int dictIndex = c.palette()[i];
            int id = memo[dictIndex];
            if (id < 0) {
                id = resolver.applyAsInt(dict.get(dictIndex));
                if (id < 0) {
                    throw new WireFormatException("resolver returned invalid id " + id
                            + " for identity '" + dict.get(dictIndex) + "'");
                }
                memo[dictIndex] = id;
            }
            resolved[i] = id;
        }

        if (c.bits() == 0) {
            return new WireSectionCursor.WireContainer(0, new int[] { resolved[0] }, new long[0]);
        }

        // Palette collapse: distinct ids in first-occurrence order + old-index remap.
        int[] collapsedOf = new int[resolved.length];
        var distinct = new ArrayList<Integer>(resolved.length);
        for (int i = 0; i < resolved.length; i++) {
            int id = resolved[i];
            int at = -1;
            for (int d = 0; d < distinct.size(); d++) {
                if (distinct.get(d) == id) {
                    at = d;
                    break;
                }
            }
            if (at < 0) {
                at = distinct.size();
                distinct.add(id);
            }
            collapsedOf[i] = at;
        }
        int n = distinct.size();
        int paletteThreshold = isBlocks
                ? WireSectionCursor.NATIVE_BLOCK_PALETTE_MAX_BITS
                : WireSectionCursor.NATIVE_BIOME_PALETTE_MAX_BITS;
        boolean collapsed = n != resolved.length;
        int nativeBits = nativeIndexedBits(n, isBlocks);

        if (!collapsed && c.bits() <= paletteThreshold && nativeBits == c.bits()) {
            // Vanilla-shaped and unchanged: palette values rewritten, longs verbatim.
            return new WireSectionCursor.WireContainer(c.bits(), resolved, c.data());
        }

        // Re-emit: unpack v20 indices, remap through the collapse, then pack native.
        int[] values = WireSectionCursor.unpack(c.data(), c.bits(), entries);
        int[] remapped = new int[entries];
        for (int i = 0; i < entries; i++) {
            int v = values[i];
            if (v >= collapsedOf.length) {
                // Packed bits are unvalidated wire content — an index past the palette
                // must be the pinned failure mode, not an array error.
                throw new WireFormatException("packed value " + v + " outside palette of "
                        + collapsedOf.length);
            }
            remapped[i] = collapsedOf[v];
        }
        if (n == 1) {
            return new WireSectionCursor.WireContainer(0,
                    new int[] { distinct.get(0) }, new long[0]);
        }
        if (nativeBits <= paletteThreshold) {
            int[] palette = new int[n];
            for (int i = 0; i < n; i++) {
                palette[i] = distinct.get(i);
            }
            return new WireSectionCursor.WireContainer(nativeBits, palette,
                    WireSectionCursor.pack(remapped, nativeBits));
        }
        // Native DIRECT at the receiving registry's width: data holds ids directly.
        int directBits = Math.max(paletteThreshold + 1,
                32 - Integer.numberOfLeadingZeros(Math.max(1, registrySize - 1)));
        int[] ids = new int[entries];
        for (int i = 0; i < entries; i++) {
            ids[i] = distinct.get(remapped[i]);
        }
        return new WireSectionCursor.WireContainer(directBits, null,
                WireSectionCursor.pack(ids, directBits));
    }

    /** Vanilla's indexed width for a palette of {@code n}: blocks {@code max(4, ceillog2)}
     *  (up to 8), biomes {@code ceillog2} (up to 3); above the threshold the caller goes
     *  DIRECT. n==1 is handled as single-value before this. */
    private static int nativeIndexedBits(int n, boolean isBlocks) {
        int ceillog2 = n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
        return isBlocks ? Math.max(4, ceillog2) : ceillog2;
    }
}
