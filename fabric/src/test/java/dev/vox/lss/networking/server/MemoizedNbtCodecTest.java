package dev.vox.lss.networking.server;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First direct suite for the palette decode memo (R3, perf-round plan Phase 1 — the
 * {@code memoSizeForTest} seam had zero callers before this). The headline pin is the
 * collision-adjacent case: equal-content tags with DIFFERENT backing-map capacity
 * histories must hit one memo entry — the {@link MemoizedNbtCodec.Key} structural hash
 * is order-independent per compound level, where a sequential combine would hash them
 * apart and silently degrade to duplicate entries. The byte-level proof that the memo
 * changes nothing stays with the nbt-corpus goldens + transcode-vs-object fuzz +
 * {@code SerializerParityGameTests}.
 *
 * <p>Textual twin of Paper's {@code PaperMemoizedNbtCodecTest} — keep in lockstep.
 */
class MemoizedNbtCodecTest {

    private final AtomicInteger decodes = new AtomicInteger();

    private Codec<String> countingDelegate() {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<String, T>> decode(DynamicOps<T> ops, T input) {
                return DataResult.success(Pair.of("decode#" + decodes.incrementAndGet(), input));
            }

            @Override
            public <T> DataResult<T> encode(String input, DynamicOps<T> ops, T prefix) {
                return DataResult.success(prefix);
            }
        };
    }

    private MemoizedNbtCodec<String> memo(int cap) {
        return new MemoizedNbtCodec<>(countingDelegate(), cap, "default",
                s -> MemoizedNbtCodec.packMeta(7, false, false));
    }

    /**
     * The capacity-divergence fixture's verified key set: "open" spreads to bucket
     * 14-of-16 but 30-of-32 (HashMap index = (h ^ h>>>16) & (n-1); String.hashCode is
     * spec-fixed, so this is deterministic on every JVM) while every other key keeps
     * its bucket — the two capacities provably iterate differently.
     */
    private static final String[] DIVERGING_KEYS = {"Name", "Properties", "axis",
            "facing", "half", "waterlogged", "powered", "open"};

    private static final String VACUITY_RECIPE =
            "fixture must FORCE different iteration orders — this test has gone vacuous: "
                    + "the backing map or its spread changed (26.2 verified: CompoundTag is "
                    + "backed by java.util.HashMap; 'open' = bucket 14/16 vs 30/32). "
                    + "Re-derive a discriminator key k with ((h^(h>>>16))&15) != ((h^(h>>>16))&31)";

    /** Equal-content pair with DIFFERENT backing-map capacity histories: b inserts 12
     *  fillers (HashMap resizes past a's table) and removes them — HashMap never
     *  shrinks, so a=16 buckets, b=32, and DIVERGING_KEYS iterate differently. */
    private static CompoundTag[] divergingPair() {
        var a = new CompoundTag();
        for (String k : DIVERGING_KEYS) a.putString(k, "v-" + k);
        var b = new CompoundTag();
        for (String k : DIVERGING_KEYS) b.putString(k, "v-" + k);
        for (int i = 0; i < 12; i++) b.putString("filler" + i, "x");
        for (int i = 0; i < 12; i++) b.remove("filler" + i);
        return new CompoundTag[]{a, b};
    }

    /** A realistic palette entry: Name + a Properties sub-compound (the nested shape
     *  whose iterator churn R3 exists to eliminate). */
    private static CompoundTag paletteEntry(String name, String axis) {
        var props = new CompoundTag();
        props.putString("axis", axis);
        props.putString("waterlogged", "false");
        var tag = new CompoundTag();
        tag.putString("Name", name);
        tag.put("Properties", props);
        return tag;
    }

    @Test
    void equalContentHitsOnceAndReturnsTheSameCachedEntry() {
        var m = memo(16);
        var first = m.resolve(paletteEntry("minecraft:stone", "y"));
        var second = m.resolve(paletteEntry("minecraft:stone", "y"));
        assertSame(first, second, "an equal-content fresh instance must HIT");
        assertEquals(1, decodes.get(), "the delegate decodes once per distinct entry");
        assertEquals(1, m.memoSizeForTest());
        var third = m.resolve(paletteEntry("minecraft:granite", "y"));
        assertNotSame(first, third);
        assertEquals(2, decodes.get());
        assertEquals(2, m.memoSizeForTest());
    }

    /**
     * THE R3 collision-adjacent pin (plan constraint, quoted): the test must FORCE
     * different backing-map capacities, since same-capacity maps usually iterate
     * identically and the test passes vacuously otherwise. Tag B inserts 20 keys
     * (HashMap resizes past A's table size) and removes the extras — equal content,
     * different capacity history. The key set is chosen so the orders provably differ:
     * "open" spreads to bucket 14 of 16 but 30 of 32 (String.hashCode is spec-fixed,
     * so this is deterministic on every JVM), and the fixture guard asserts it.
     */
    @Test
    void equalContentDifferentCapacityHistoryHitsOneEntry() {
        var pair = divergingPair();
        var a = pair[0];
        var b = pair[1];
        assertEquals(a, b, "fixture: content must be equal");
        assertNotEquals(List.copyOf(a.keySet()), List.copyOf(b.keySet()), VACUITY_RECIPE);
        assertEquals(MemoizedNbtCodec.Key.structuralHash(a),
                MemoizedNbtCodec.Key.structuralHash(b),
                "order-independent structural hash: equal content must hash equal"
                        + " regardless of iteration order");

        var m = memo(16);
        assertSame(m.resolve(a), m.resolve(b),
                "iteration order must not split one logical entry into two");
        assertEquals(1, m.memoSizeForTest());
        assertEquals(1, decodes.get());
    }

    @Test
    void nestedCompoundOrderIsAlsoIndependentAndListOrderIsSemantic() {
        // The recursion pin, one level down (review F1: the original fixture here was
        // VACUOUS — its two-key sub-compounds iterated identically at both capacities;
        // computed, not assumed. Same verified diverging key set as the flagship test,
        // nested under Properties, with the same vacuity guard).
        var pair = divergingPair();
        assertNotEquals(List.copyOf(pair[0].keySet()), List.copyOf(pair[1].keySet()),
                VACUITY_RECIPE);
        var t1 = new CompoundTag();
        t1.put("Properties", pair[0]);
        var t2 = new CompoundTag();
        t2.put("Properties", pair[1]);
        assertEquals(MemoizedNbtCodec.Key.structuralHash(t1),
                MemoizedNbtCodec.Key.structuralHash(t2),
                "the recursion must be order-independent one level down too");
        var m = memo(16);
        assertSame(m.resolve(t1), m.resolve(t2), "…and pinned through the memo");
        assertEquals(1, m.memoSizeForTest());

        // ListTag order IS semantic — sequential combine (canary: not a collision
        // guarantee, but these fixtures must not hash equal).
        var l1 = new ListTag();
        l1.add(StringTag.valueOf("a"));
        l1.add(StringTag.valueOf("b"));
        var l2 = new ListTag();
        l2.add(StringTag.valueOf("b"));
        l2.add(StringTag.valueOf("a"));
        assertNotEquals(MemoizedNbtCodec.Key.structuralHash(l1),
                MemoizedNbtCodec.Key.structuralHash(l2));

        // A list nested INSIDE a compound drives the recursion's list branch through
        // the memo itself (review C4c — the class is generic; palette Properties never
        // carry lists, but the recursion must not care).
        var withList1 = new CompoundTag();
        withList1.put("list", l1);
        var withList2 = new CompoundTag();
        withList2.put("list", l1.copy());
        var m2 = memo(16);
        assertSame(m2.resolve(withList1), m2.resolve(withList2));
        assertEquals(1, m2.memoSizeForTest());
    }

    /**
     * B1 review C4a/C4b: the structural hash is BIT-IDENTICAL to {@code Tag.hashCode()}
     * ({@code Map.hashCode}/{@code List.hashCode} are interface contracts) — the
     * invariant proving the key distribution is unchanged from pre-R3 raw-tag keying.
     * A future "optimization" of the combine must red here. Copy-equality is the
     * premise the stored key's copy re-walk relies on.
     */
    @Test
    void structuralHashMatchesTagHashCodeAndSurvivesCopy() {
        var tag = paletteEntry("minecraft:stone", "y");
        var list = new ListTag();
        list.add(StringTag.valueOf("a"));
        list.add(paletteEntry("minecraft:granite", "x"));
        tag.put("mixed", list);
        assertEquals(tag.hashCode(), MemoizedNbtCodec.Key.structuralHash(tag),
                "structuralHash must equal Tag.hashCode for every vanilla tag shape");
        assertEquals(MemoizedNbtCodec.Key.structuralHash(tag),
                MemoizedNbtCodec.Key.structuralHash(tag.copy()),
                "a deep copy preserves content, hence hash");
        assertEquals(new CompoundTag().hashCode(),
                MemoizedNbtCodec.Key.structuralHash(new CompoundTag()));
    }

    /**
     * B1 review F2: Key must stay NESTED — analyze_profile_jfr.py's DEFAULT_MARKERS
     * count "samples under MemoizedNbtCodec" by class-name prefix (the $Key frames
     * ride the same prefix, plus their own separate new-key-cost marker), and Phase 4's
     * band subtraction depends on the identifier staying stable.
     */
    @Test
    void keyStaysNestedForTheProfileMarkerContract() {
        assertEquals("dev.vox.lss.networking.server.MemoizedNbtCodec$Key",
                MemoizedNbtCodec.Key.class.getName());
    }

    @Test
    void defensiveCopyKeepsTheCacheImmuneToCallerMutation() {
        var m = memo(16);
        var caller = paletteEntry("minecraft:stone", "y");
        var cached = m.resolve(caller);
        caller.putString("Name", "minecraft:tnt");

        var again = m.resolve(paletteEntry("minecraft:stone", "y"));
        assertSame(cached, again, "the stored key must be a copy, not the caller's tag");
        assertEquals(1, decodes.get());

        // The remainder normalization: the cached result carries the COPY, never the
        // caller's instance (deleting the copy() re-pins caller chunk NBT for the JVM).
        Tag remainder = cached.result().result().orElseThrow().getSecond();
        assertNotSame(caller, remainder);
        assertEquals(paletteEntry("minecraft:stone", "y"), remainder);

        // The mutated caller tag is now genuinely different content -> fresh decode.
        m.resolve(caller);
        assertEquals(2, decodes.get());
    }

    @Test
    void capStopsInsertionsAndLatchesTheWarnOnceButNeverStopsDecoding() {
        var m = memo(2);
        assertFalse(m.capWarnedForTest());
        m.resolve(paletteEntry("minecraft:a", "x"));
        m.resolve(paletteEntry("minecraft:b", "x"));
        assertFalse(m.capWarnedForTest(), "at-cap is not over-cap");
        m.resolve(paletteEntry("minecraft:c", "x"));
        assertTrue(m.capWarnedForTest(), "first over-cap decode latches the one-shot warn");
        m.resolve(paletteEntry("minecraft:c", "x"));
        assertEquals(2, m.memoSizeForTest(), "insertions stop at cap");
        assertEquals(4, decodes.get(), "over-cap entries decode uncached every time");
        m.resolve(paletteEntry("minecraft:a", "x"));
        assertEquals(4, decodes.get(), "cached entries still hit at cap");
    }

    @Test
    void codecDecodePathSharesTheCacheAndForeignOpsBypassIt() {
        var m = memo(16);
        Tag tag = paletteEntry("minecraft:stone", "y");
        var viaCodec = m.decode(NbtOps.INSTANCE, tag);
        assertTrue(viaCodec.result().isPresent());
        assertEquals(1, decodes.get());
        assertSame(m.resolve(paletteEntry("minecraft:stone", "y")).result().result().orElseThrow().getFirst(),
                viaCodec.result().orElseThrow().getFirst(),
                "resolve() and decode() share one cache");
        assertEquals(1, m.memoSizeForTest());

        m.decode(JsonOps.INSTANCE, new com.google.gson.JsonObject());
        assertEquals(2, decodes.get(), "foreign ops delegate through");
        assertEquals(1, m.memoSizeForTest(), "...and never cache");
    }

    @Test
    void errorResultsCacheWithHardErrorAndDefaultMeta() {
        Codec<String> failing = new Codec<>() {
            @Override
            public <T> DataResult<Pair<String, T>> decode(DynamicOps<T> ops, T input) {
                decodes.incrementAndGet();
                return DataResult.error(() -> "boom");
            }

            @Override
            public <T> DataResult<T> encode(String input, DynamicOps<T> ops, T prefix) {
                return DataResult.success(prefix);
            }
        };
        var m = new MemoizedNbtCodec<>(failing, 4, "default",
                s -> MemoizedNbtCodec.packMeta(7, false, false));
        var c1 = m.resolve(paletteEntry("minecraft:broken", "x"));
        assertEquals("boom", c1.hardError());
        assertEquals(7, c1.globalId(), "hard failures resolve to the default's meta");
        assertSame(c1, m.resolve(paletteEntry("minecraft:broken", "x")),
                "error results cache like successes (leniency runs above this cache)");
        assertEquals(1, decodes.get());
    }
}
