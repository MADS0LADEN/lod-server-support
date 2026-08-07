package dev.vox.lss.paper;

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
 * histories must hit one memo entry — the {@link PaperMemoizedNbtCodec.Key} structural hash
 * is order-independent per compound level, where a sequential combine would hash them
 * apart and silently degrade to duplicate entries. The byte-level proof that the memo
 * changes nothing stays with the nbt-corpus goldens + transcode-vs-object fuzz +
 * {@code SerializerParityGameTests}.
 *
 * <p>Textual twin of Fabric's {@code MemoizedNbtCodecTest} — keep in lockstep.
 */
class PaperMemoizedNbtCodecTest {

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

    private PaperMemoizedNbtCodec<String> memo(int cap) {
        return new PaperMemoizedNbtCodec<>(countingDelegate(), cap, "default",
                s -> PaperMemoizedNbtCodec.packMeta(7, false, false));
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
        String[] names = {"Name", "Properties", "axis", "facing",
                "half", "waterlogged", "powered", "open"};
        var a = new CompoundTag();
        for (String k : names) a.putString(k, "v-" + k);
        var b = new CompoundTag();
        for (String k : names) b.putString(k, "v-" + k);
        for (int i = 0; i < 12; i++) b.putString("filler" + i, "x");
        for (int i = 0; i < 12; i++) b.remove("filler" + i);

        assertEquals(a, b, "fixture: content must be equal");
        assertNotEquals(List.copyOf(a.keySet()), List.copyOf(b.keySet()),
                "fixture must FORCE different iteration orders — if this fails the test"
                        + " has gone vacuous (backing map or spread function changed)");
        assertEquals(PaperMemoizedNbtCodec.Key.structuralHash(a),
                PaperMemoizedNbtCodec.Key.structuralHash(b),
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
        // The Properties sub-compound gets the same treatment (the recursion).
        var p1 = new CompoundTag();
        p1.putString("axis", "y");
        p1.putString("open", "false");
        var p2 = new CompoundTag();
        p2.putString("open", "false");
        for (int i = 0; i < 12; i++) p2.putString("filler" + i, "x");
        for (int i = 0; i < 12; i++) p2.remove("filler" + i);
        p2.putString("axis", "y");
        var t1 = new CompoundTag();
        t1.put("Properties", p1);
        var t2 = new CompoundTag();
        t2.put("Properties", p2);
        assertEquals(PaperMemoizedNbtCodec.Key.structuralHash(t1),
                PaperMemoizedNbtCodec.Key.structuralHash(t2));

        // ListTag order IS semantic — sequential combine (canary: not a collision
        // guarantee, but these fixtures must not hash equal).
        var l1 = new ListTag();
        l1.add(StringTag.valueOf("a"));
        l1.add(StringTag.valueOf("b"));
        var l2 = new ListTag();
        l2.add(StringTag.valueOf("b"));
        l2.add(StringTag.valueOf("a"));
        assertNotEquals(PaperMemoizedNbtCodec.Key.structuralHash(l1),
                PaperMemoizedNbtCodec.Key.structuralHash(l2));
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
        var m = new PaperMemoizedNbtCodec<>(failing, 4, "default",
                s -> PaperMemoizedNbtCodec.packMeta(7, false, false));
        var c1 = m.resolve(paletteEntry("minecraft:broken", "x"));
        assertEquals("boom", c1.hardError());
        assertEquals(7, c1.globalId(), "hard failures resolve to the default's meta");
        assertSame(c1, m.resolve(paletteEntry("minecraft:broken", "x")),
                "error results cache like successes (leniency runs above this cache)");
        assertEquals(1, decodes.get());
    }
}
