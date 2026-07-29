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
 * <p>Thread-safe (ConcurrentHashMap; used from the LSS reader pool). Insertions stop at
 * {@code cap} — pathological modded worlds with unbounded distinct palette entries keep
 * decoding through the delegate rather than growing the map (warned once).
 *
 * <p>Textual twin of Fabric's {@code MemoizedNbtCodec} — keep in lockstep.
 */
final class PaperMemoizedNbtCodec<A> implements Codec<A> {
    private final Codec<A> delegate;
    private final int cap;
    private final ConcurrentHashMap<Tag, DataResult<Pair<A, Tag>>> memo = new ConcurrentHashMap<>();
    private final AtomicBoolean capWarned = new AtomicBoolean();

    PaperMemoizedNbtCodec(Codec<A> delegate, int cap) {
        this.delegate = delegate;
        this.cap = cap;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
        // ops identity proves T == Tag for the memoized branch; the casts below are sound.
        if (ops != NbtOps.INSTANCE || !(input instanceof Tag tag)) {
            return this.delegate.decode(ops, input);
        }
        var hit = this.memo.get(tag);
        if (hit == null) {
            var fresh = this.delegate.decode(ops, input);
            if (this.memo.size() < this.cap) {
                Tag key = tag.copy();
                // Normalize the remainder to the cached key so the entry doesn't pin the
                // first caller's whole chunk NBT alive (map() applies to partials too).
                hit = ((DataResult<Pair<A, Tag>>) (DataResult<?>) fresh)
                        .map(pair -> Pair.of(pair.getFirst(), key));
                this.memo.put(key, hit);
            } else {
                if (this.capWarned.compareAndSet(false, true)) {
                    LSSLogger.warn("Palette decode memo reached its cap (" + this.cap
                            + " distinct entries) — further entries decode uncached");
                }
                hit = (DataResult<Pair<A, Tag>>) (DataResult<?>) fresh;
            }
        }
        return (DataResult<Pair<A, T>>) (DataResult<?>) hit;
    }

    @Override
    public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
        return this.delegate.encode(input, ops, prefix);
    }

    /** Test seam: distinct entries currently memoized. */
    int memoSizeForTest() {
        return this.memo.size();
    }
}
