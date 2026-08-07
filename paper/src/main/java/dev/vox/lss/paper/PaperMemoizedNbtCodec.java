package dev.vox.lss.paper;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.vox.lss.common.LSSLogger;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;

/**
 * Decode-memoizing codec wrapper for the disk-read serve path (2026-07-29 profile:
 * per-palette-entry block-state decode — Identifier parsing, registry lookups,
 * DataResult/Pair churn — was ~25% of all server CPU during saturated LOD backfill).
 *
 * <p>Palette entries repeat enormously across sections and columns, and NBT tags hash and
 * compare structurally, so the entry tag itself is the cache key — wrapped since R3 in
 * {@link Key}, which precomputes a one-pass structural hash (the raw-tag keying paid
 * {@code AbstractMap.hashCode}'s iterator churn on every lookup). Only {@link NbtOps}
 * decodes are memoized (any other ops delegates untouched); the stored key wraps a
 * defensive {@link Tag#copy()} and the cached result's remainder is normalized to that
 * copy, so a cached entry never aliases a caller's mutable tag. Error results are cached too — the
 * container codec's leniency wrapper and the caller's {@code resultOrPartial} warn run
 * per parse, above this cache, so a cached error behaves exactly like a fresh one.
 *
 * <p>Round 2 extends each cached entry with the NBT->wire transcoder's per-entry meta
 * ({@link Cached#globalId()}/{@link Cached#isAir()}/{@link Cached#hasFluid()}), resolved
 * from the value vanilla's {@code orElsePartial} leniency would put in the container: the
 * decoded value, its partial, or the codec default on a hard (no-partial) failure — whose
 * message rides {@link Cached#hardError()} so the transcoder can mirror the section-level
 * substitution warn. {@link #resolve} is the transcoder's palette-id resolver; the codec
 * {@link #decode} path shares the same cache and decode-once semantics.
 *
 * <p>Thread-safe (ConcurrentHashMap; used from the LSS reader pool). Insertions stop at
 * {@code cap} — pathological modded worlds with unbounded distinct palette entries keep
 * decoding through the delegate rather than growing the map (warned once).
 *
 * <p>Textual twin of Fabric's {@code MemoizedNbtCodec} — keep in lockstep.
 */
final class PaperMemoizedNbtCodec<A> implements Codec<A> {

    /**
     * One decoded palette entry: the codec-path result plus the transcoder's resolved
     * meta, packed {@code (globalId << 2) | (hasFluid << 1) | isAir} of the value the
     * container would hold after vanilla's element leniency. {@code hardError} is the
     * no-partial failure message (null for clean/partial decodes) — the transcoder
     * substitutes the default and mirrors the object path's throttled warn off it.
     */
    record Cached<A>(DataResult<Pair<A, Tag>> result, long meta, String hardError) {
        int globalId() {
            return (int) (this.meta >>> 2);
        }

        boolean isAir() {
            return (this.meta & 1L) != 0;
        }

        boolean hasFluid() {
            return (this.meta & 2L) != 0;
        }
    }

    /** Packs the transcoder meta; the twins' construction sites feed it identically. */
    static long packMeta(int globalId, boolean isAir, boolean hasFluid) {
        return ((long) globalId << 2) | (hasFluid ? 2L : 0L) | (isAir ? 1L : 0L);
    }

    /**
     * Memo key (R3, perf-round plan Phase 1): the entry tag plus a PRECOMPUTED one-pass
     * structural hash, bit-identical to {@code Tag.hashCode()} for every vanilla tag
     * type ({@code Map.hashCode}/{@code List.hashCode} are interface contracts — the
     * key distribution is provably unchanged from raw-tag keying). What changes is the
     * walk's SHAPE: {@code CompoundTag.forEach} instead of {@code AbstractMap.hashCode}'s
     * entrySet chain — no {@code Map.Entry} indirection, no megamorphic
     * {@code Node.hashCode} dispatch, which is where the profiled cost lived (76
     * backfill-thread samples, 328 MB/75 s of EntryIterator churn). Allocation count is
     * roughly a WASH, not a win: one {@code int[1]} + one capturing {@code BiConsumer}
     * per compound level replaces one EntryIterator per level, plus one probe Key per
     * lookup (B1 review C3 — read the Phase 1 allocation gate accordingly). Structural
     * identity is retained exactly ({@link #equals} delegates to {@code Tag.equals}) —
     * the class javadoc's "the entry tag itself is the cache key" contract is unchanged,
     * and the rejected flat-string alternative's separator-injection/type-collapse
     * collisions (a wrong globalId straight onto the wire) cannot occur.
     *
     * <p>The per-compound combine is a SUM of {@code name.hashCode() ^ hash(value)}
     * terms (matching {@code AbstractMap.hashCode} semantics), never sequential:
     * equal-content tags whose backing maps iterate in different orders
     * (capacity-history dependent) MUST hash identically or they silently degrade to
     * duplicate memo entries. {@link net.minecraft.nbt.ListTag} order IS semantic and
     * combines sequentially. No shared/static hasher state — the memo is hit from the
     * reader pool and the backfill thread concurrently; per-call locals only.
     *
     * <p>MUST stay nested in PaperMemoizedNbtCodec: the profile tooling counts this work as
     * "samples under PaperMemoizedNbtCodec" ({@code analyze_profile_jfr.py} DEFAULT_MARKERS),
     * and Phase 4's band subtraction relies on that identifier staying stable.
     */
    static final class Key {
        final Tag tag;
        final int hash;

        Key(Tag tag) {
            this(tag, structuralHash(tag));
        }

        private Key(Tag tag, int hash) {
            this.tag = tag;
            this.hash = hash;
        }

        static int structuralHash(Tag tag) {
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
                int[] sum = {0};
                compound.forEach((name, value) -> sum[0] += name.hashCode() ^ structuralHash(value));
                return sum[0];
            }
            if (tag instanceof net.minecraft.nbt.ListTag list) {
                int h = 1;
                for (int i = 0, n = list.size(); i < n; i++) {
                    h = 31 * h + structuralHash(list.get(i));
                }
                return h;
            }
            // Primitive/string leaves: their own hashCode is cheap and iterator-free.
            // Null-safe like the Objects.hashCode the replaced AbstractMap walk used
            // (unreachable from parsed NBT; transcodeSection has no exception fallback,
            // so an NPE here would escape the serializer — B1 review C2).
            return tag == null ? 0 : tag.hashCode();
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            return o instanceof Key other && other.hash == this.hash && other.tag.equals(this.tag);
        }
    }

    private final Codec<A> delegate;
    private final int cap;
    private final ToLongFunction<A> metaFn;
    private final long defaultMeta;
    private final ConcurrentHashMap<Key, Cached<A>> memo = new ConcurrentHashMap<>();
    private final AtomicBoolean capWarned = new AtomicBoolean();

    /**
     * @param defaultValue the container codec's element default (the {@code codecRW}
     *     argument — air for block states): a hard entry failure resolves to ITS meta,
     *     exactly what {@code orElsePartial} substitutes into the container.
     * @param metaFn derives the packed transcoder meta from a decoded value
     */
    PaperMemoizedNbtCodec(Codec<A> delegate, int cap, A defaultValue, ToLongFunction<A> metaFn) {
        this.delegate = delegate;
        this.cap = cap;
        this.metaFn = metaFn;
        this.defaultMeta = metaFn.applyAsLong(defaultValue);
    }

    /** The transcoder's palette-entry resolver — same cache, same decode-once semantics
     *  as the codec path. Never returns null. */
    Cached<A> resolve(Tag tag) {
        var probe = new Key(tag);
        var hit = this.memo.get(probe);
        return hit != null ? hit : decodeAndCache(probe);
    }

    @SuppressWarnings("unchecked")
    private Cached<A> decodeAndCache(Key probe) {
        var fresh = (DataResult<Pair<A, Tag>>) (DataResult<?>) this.delegate.decode(NbtOps.INSTANCE, probe.tag);
        if (this.memo.size() < this.cap) {
            // The copy is the REMAINDER normalization, not key hygiene (R3 review
            // constraint): the cached result's remainder must not pin the first
            // caller's whole chunk NBT alive (map() applies to partials too). It
            // doubles as the stored key's aliasing guard against the caller's mutable
            // tag. The stored Key re-walks the COPY rather than reusing the probe's
            // hash: the probe hashed the caller's live tag BEFORE delegate.decode ran,
            // and a caller mutation inside that window would poison the map with an
            // unreachable entry (hash of old content, tag of new) — re-walking on the
            // miss path costs nothing next to the codec decode it rides (B1 review C1).
            Tag key = probe.tag.copy();
            var cached = toCached(fresh.map(pair -> Pair.of(pair.getFirst(), key)));
            this.memo.put(new Key(key), cached);
            return cached;
        }
        if (this.capWarned.compareAndSet(false, true)) {
            LSSLogger.warn("Palette decode memo reached its cap (" + this.cap
                    + " distinct entries) — further entries decode uncached");
        }
        return toCached(fresh);
    }

    private Cached<A> toCached(DataResult<Pair<A, Tag>> result) {
        String[] err = new String[1];
        var value = result.resultOrPartial(msg -> err[0] = msg).map(Pair::getFirst);
        // A partial (value present, err set) is what orElsePartial turns into a clean
        // success — its message never reaches the section warn, so hardError stays null.
        return value.isPresent()
                ? new Cached<>(result, this.metaFn.applyAsLong(value.get()), null)
                : new Cached<>(result, this.defaultMeta,
                        err[0] == null ? "unrecoverable palette entry" : err[0]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
        // ops identity proves T == Tag for the memoized branch; the casts below are sound.
        if (ops != NbtOps.INSTANCE || !(input instanceof Tag tag)) {
            return this.delegate.decode(ops, input);
        }
        return (DataResult<Pair<A, T>>) (DataResult<?>) resolve(tag).result();
    }

    @Override
    public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
        return this.delegate.encode(input, ops, prefix);
    }

    /** Test seam: distinct entries currently memoized. */
    int memoSizeForTest() {
        return this.memo.size();
    }

    /** Test seam: the over-cap warn latch (pins the once-ness without log capture). */
    boolean capWarnedForTest() {
        return this.capWarned.get();
    }
}
