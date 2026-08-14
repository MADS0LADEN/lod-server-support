package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.store.StoreCodec;

/**
 * Client-side zstd wire support (protocol 19, compressed-columns-design.md): one lazy
 * per-JVM probe of the shared {@link StoreCodec} (zstd-jni is nested in the jar with
 * the linux/win/mac × x64/arm64 native matrix — the desktop client is covered; an
 * unsupported platform fails the probe once, logs one line, and the client simply never
 * declares {@link LSSConstants#CAPABILITY_ZSTD_COLUMNS} — the session runs raw).
 *
 * <p>Used by the VoxelColumn decode path ({@link #declaredContentSize} for the
 * netty-time size memo, {@link #decompress} on the {@code ClientColumnProcessor} drain)
 * and the handshake declaration ({@link #capabilityBit}). The SERVER side deliberately
 * does not use this holder — its probe latches at service start with its own warning
 * (plan §0.11) so a natives-less server degrades loudly-but-once to raw sessions.
 */
public final class ZstdWireSupport {

    private ZstdWireSupport() {}

    private static final class Holder {
        static final StoreCodec CODEC = probe();

        private static StoreCodec probe() {
            StoreCodec codec = StoreCodec.zstdOrNull();
            if (codec == null) {
                LSSLogger.warn("zstd native unavailable on this platform — LOD columns will"
                        + " be received uncompressed (CAPABILITY_ZSTD_COLUMNS not declared)");
            }
            return codec;
        }
    }

    /** True when the zstd native probe succeeded (round-trip verified). */
    public static boolean available() {
        return Holder.CODEC != null;
    }

    /** The capability bit to OR into the handshake: 0x2 when available, else 0. */
    public static int capabilityBit() {
        return available() ? LSSConstants.CAPABILITY_ZSTD_COLUMNS : 0;
    }

    /** Frame-declared decompressed size, or non-positive for malformed/undeclared/no-native
     *  (callers clamp — see {@link StoreCodec#declaredContentSize}). */
    public static long declaredContentSize(byte[] frame) {
        var codec = Holder.CODEC;
        return codec == null ? -1 : codec.declaredContentSize(frame);
    }

    /** Exact-size decompress; throws on any zstd failure (the drain converts a throw into
     *  an ingest-failure report). Callers must have bomb-guarded {@code usize} first. */
    public static byte[] decompress(byte[] frame, int usize) {
        var codec = Holder.CODEC;
        if (codec == null) {
            throw new IllegalStateException("zstd native unavailable — cannot decode codec-1 column");
        }
        return codec.decompress(frame, usize);
    }
}
