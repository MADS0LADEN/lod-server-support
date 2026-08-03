package dev.vox.lss.common.store;

import com.github.luben.zstd.Zstd;

/**
 * The shared store + wire blob codec: zstd level 1 — the store Phase 0 decision
 * (dominates deflate-1 on both deciding metrics: 24 vs 60 µs decompress and 5,342 vs
 * 5,732 B/col on the benchmark corpus, and 3× cheaper compress protecting the
 * cold-path deposit gate). Since protocol 19 (compressed-columns-design.md) the SAME
 * codec frames column payloads on the wire — both sides calling the same
 * {@code compress(raw)} at the same level is what makes the store-frame == wire-frame
 * invariant structural rather than pinned-by-test alone. The client's capability probe
 * and decode reuse {@link #zstdOrNull()}/{@link #decompress}; the bomb guard and the
 * decode-queue charge read {@link #declaredContentSize}.
 *
 * <p>Native containment: {@link #zstdOrNull()} PROBES a real round-trip at creation, so
 * an unsupported platform (a native outside the shipped matrix) fails HERE, once, at
 * store init — the caller latches the store off with one warning — rather than per-read.
 * Catches {@link Throwable}: zstd-jni surfaces missing natives as
 * {@link UnsatisfiedLinkError}/{@link ExceptionInInitializerError}, not exceptions.
 *
 * <p>Phase 2 records the codec name in the DB {@code meta} table: a codec change is a
 * drop-and-rebuild, never a migration. (deflate-1 remains the recorded zero-dependency
 * fallback should zstd ever have to be dropped — a decision, not a runtime rung.)
 */
public final class StoreCodec {

    public static final String NAME = "zstd-1";
    private static final int LEVEL = 1;

    private StoreCodec() {}

    /** A working codec, or null when the zstd native cannot serve this platform. */
    public static StoreCodec zstdOrNull() {
        try {
            var codec = new StoreCodec();
            byte[] probe = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
            byte[] back = codec.decompress(codec.compress(probe), probe.length);
            return java.util.Arrays.equals(probe, back) ? codec : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public byte[] compress(byte[] raw) {
        return Zstd.compress(raw, LEVEL);
    }

    /** {@code usize} is the stored uncompressed size — zstd needs the exact output size. */
    public byte[] decompress(byte[] compressed, int usize) {
        return Zstd.decompress(compressed, usize);
    }

    /**
     * The frame's self-declared decompressed size, or a non-positive value when the frame
     * is malformed or does not declare one. Single-shot {@link #compress} frames ALWAYS
     * declare it (Phase 0: 0 violations over 42,589 corpus frames), so a non-positive
     * return from a peer is hostile/corrupt by construction. Callers must clamp before
     * trusting it (the wire bomb guard rejects &gt; MAX_SECTIONS_SIZE; the client
     * decode-queue charge clamps into [0, MAX_SECTIONS_SIZE] and never charges below the
     * shipped length).
     */
    public long declaredContentSize(byte[] frame) {
        try {
            return Zstd.getFrameContentSize(frame);
        } catch (Throwable t) {
            return -1; // malformed header — callers treat like "not declared"
        }
    }
}
