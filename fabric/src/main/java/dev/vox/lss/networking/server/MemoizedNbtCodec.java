package dev.vox.lss.networking.server;

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
 * compare structurally, so the entry tag itself is the cache key. Only {@link NbtOps}
 * decodes are memoized (any other ops delegates untouched); the key is a defensive
 * {@link Tag#copy()} and the cached result's remainder is normalized to that copy, so a
 * cached entry never aliases a caller's mutable tag. Error results are cached too — the
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
 * <p><b>Raw-tag keying is a MEASURED decision, do not "optimize" it away (B1, 2026-08-07):</b>
 * the perf-round Phase 1 attempt (R3 — a nested Key wrapper precomputing a one-pass
 * {@code CompoundTag.forEach} structural hash) was built, review-hardened, and REVERTED
 * on a failed A/B gate: the forEach walk cost statistically the same as the
 * {@code AbstractMap.hashCode} recursion it replaced (memo marker 249→286 backfill /
 * 258→290 serve, the new Key walk itself 278/239 samples), and allocation was a wash
 * (EntrySet iterators −2.2 KB/col vs Key objects +2.9 KB/col). The walk is the
 * irreducible cost of structural keying; a flat-string key was rejected for real
 * collision classes (wrong globalId on the wire). Full numbers:
 * docs/planning/v0.10.0-progress.md measurement ledger. MemoizedNbtCodecTest pins the
 * behavior contract, including capacity-history keying independence.
 *
 * <p>Textual twin of Paper's {@code PaperMemoizedNbtCodec} — keep in lockstep.
 */
final class MemoizedNbtCodec<A> implements Codec<A> {

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

    private final Codec<A> delegate;
    private final int cap;
    private final ToLongFunction<A> metaFn;
    private final long defaultMeta;
    private final ConcurrentHashMap<Tag, Cached<A>> memo = new ConcurrentHashMap<>();
    private final AtomicBoolean capWarned = new AtomicBoolean();

    /**
     * @param defaultValue the container codec's element default (the {@code codecRW}
     *     argument — air for block states): a hard entry failure resolves to ITS meta,
     *     exactly what {@code orElsePartial} substitutes into the container.
     * @param metaFn derives the packed transcoder meta from a decoded value
     */
    MemoizedNbtCodec(Codec<A> delegate, int cap, A defaultValue, ToLongFunction<A> metaFn) {
        this.delegate = delegate;
        this.cap = cap;
        this.metaFn = metaFn;
        this.defaultMeta = metaFn.applyAsLong(defaultValue);
    }

    /** The transcoder's palette-entry resolver — same cache, same decode-once semantics
     *  as the codec path. Never returns null. */
    Cached<A> resolve(Tag tag) {
        var hit = this.memo.get(tag);
        return hit != null ? hit : decodeAndCache(tag);
    }

    @SuppressWarnings("unchecked")
    private Cached<A> decodeAndCache(Tag tag) {
        var fresh = (DataResult<Pair<A, Tag>>) (DataResult<?>) this.delegate.decode(NbtOps.INSTANCE, tag);
        if (this.memo.size() < this.cap) {
            Tag key = tag.copy();
            // Normalize the remainder to the cached key so the entry doesn't pin the
            // first caller's whole chunk NBT alive (map() applies to partials too).
            var cached = toCached(fresh.map(pair -> Pair.of(pair.getFirst(), key)));
            this.memo.put(key, cached);
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
