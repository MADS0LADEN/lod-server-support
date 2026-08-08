package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;

/**
 * One served column's section bytes in raw and/or zstd-frame form, converting lazily and
 * memoizing in both directions (docs/planning/compressed-columns-implementation-plan.md
 * §0.4). Created ONCE per drained result on the processing thread and shared by every
 * dedup-fan-out recipient build and the store deposit — so a mixed-capability recipient
 * group costs one compress (first capable recipient) and at most one decompress (first
 * raw-needing recipient of a store frame), never one per recipient.
 *
 * <p><b>Thread-confined by contract</b>: all access happens on the processing thread
 * within one delivery drain — no synchronization, plain fields.
 *
 * <p>{@link #frame()} returns null (⇒ codec 0) when compression is not worth shipping:
 * no codec attached (server natives unavailable / useCompressedColumns off), raw below
 * {@link LSSConstants#COLUMN_COMPRESS_MIN_BYTES} (tiny bodies, incl. the 1-byte
 * 0-section clear, which must stay raw — the client's clear detection reads the leading
 * varint without a decompress), or a frame that did not shrink the input (zstd worst
 * case is raw + raw/255 + header, so "frame smaller" is not a given for incompressible
 * input; shipping raw is then strictly better bytes AND spares the client a decompress).
 */
public final class ColumnBytes {

    private final StoreCodec codec; // null => frame() always null (raw-only)
    private byte[] raw;
    private byte[] frame;
    private final int rawSize;
    /** frame() computed and answered "not worth it" — memoize the refusal, not just the frame. */
    private boolean frameRefused;

    private ColumnBytes(StoreCodec codec, byte[] raw, byte[] frame, int rawSize) {
        this.codec = codec;
        this.raw = raw;
        this.frame = frame;
        this.rawSize = rawSize;
    }

    /** Raw section bytes in hand (live/disk/generation serves, clears). */
    public static ColumnBytes ofRaw(StoreCodec codec, byte[] raw) {
        return new ColumnBytes(codec, raw, null, raw.length);
    }

    /**
     * Stored zstd frame in hand (store hits — Phase 2). {@code rawSize} is the store
     * row's validated {@code usize}; {@code codec} must be non-null (a store frame is
     * only fetched while compression is live — the reader rung's gate).
     */
    public static ColumnBytes ofFrame(StoreCodec codec, byte[] frame, int rawSize) {
        return new ColumnBytes(codec, null, frame, rawSize);
    }

    /** The raw (uncompressed) section byte length, known without materializing either form. */
    public int rawSize() {
        return this.rawSize;
    }

    /**
     * Raw section bytes, decompressing the frame on first need (raw-needing recipients:
     * v16 sessions, no-capability clients, the soak probe hash, the MAX_SEND-refused
     * fallback ladder). Throws only on a corrupt frame — the store validated
     * fhash/declared-size before handing it over, so a throw here is the same
     * row-poison class the per-delivery containment already handles.
     */
    public byte[] raw() {
        if (this.raw == null) {
            byte[] out = this.codec.decompress(this.frame, this.rawSize);
            if (out.length != this.rawSize) {
                throw new IllegalStateException("column frame decompressed to " + out.length
                        + " bytes, expected " + this.rawSize);
            }
            this.raw = out;
        }
        return this.raw;
    }

    /**
     * The zstd frame to ship (codec 1), or null when this column ships raw (codec 0):
     * no codec, below {@link LSSConstants#COLUMN_COMPRESS_MIN_BYTES}, or the frame did
     * not shrink the input. Compresses on first call, memoized (including the refusal).
     */
    public byte[] frame() {
        if (this.frame != null) {
            // Store frames enter pre-built (ofFrame) and skip the compress-side refusals
            // below — but the non-shrinking rule must hold for THEM too, or codec-1
            // loses the "shipped < raw" invariant law A2's exact equality rides on
            // (4-agent round, wire F1: the batcher compresses every deposit
            // unconditionally, so a degenerate stored frame CAN be non-shrinking).
            // Refusal ships raw instead — one lazy decompress, only on this
            // near-unreachable shape (zstd worst case ~raw + raw/255 + header).
            return this.frame.length >= this.rawSize ? null : this.frame;
        }
        if (this.frameRefused || this.codec == null
                || this.rawSize < LSSConstants.COLUMN_COMPRESS_MIN_BYTES) {
            return null;
        }
        byte[] f = this.codec.compress(this.raw);
        if (f.length >= this.rawSize) {
            this.frameRefused = true;
            return null;
        }
        this.frame = f;
        return f;
    }

    // The C2 legacy-egress build memo (XVER §4.2, review MAJOR-2): the translated build
    // depends only on (this column's raw bytes × wants-compressed) — the registry is
    // server-global — so at most TWO distinct builds exist per column. Memoized here,
    // the holder every dedup recipient already shares, so an N-recipient legacy group
    // costs ONE translate (+ at most one recompress), never one per recipient. The
    // compute lives in platform code (the translators are per-platform); thread-confined
    // plain fields like everything else in this class.
    private LegacyColumnBuild legacyRawBuild;
    private LegacyColumnBuild legacyFramedBuild;

    /** The memoized legacy build for this column (see the field comment). {@code compute}
     *  runs at most once per (column × wantsCompressed); its throw propagates uncached so
     *  the per-recipient containment sees every failure. */
    public LegacyColumnBuild legacyBuild(boolean wantsCompressed,
                                         java.util.function.Supplier<LegacyColumnBuild> compute) {
        if (wantsCompressed) {
            if (this.legacyFramedBuild == null) {
                this.legacyFramedBuild = compute.get();
            }
            return this.legacyFramedBuild;
        }
        if (this.legacyRawBuild == null) {
            this.legacyRawBuild = compute.get();
        }
        return this.legacyRawBuild;
    }

    /** True when {@link #raw()} is already materialized (no decompress would run).
     *  Observability for the compress/decompress-once pins. */
    public boolean rawMaterialized() {
        return this.raw != null;
    }

    /** True when a frame is already materialized (no compress would run). */
    public boolean frameMaterialized() {
        return this.frame != null;
    }
}
