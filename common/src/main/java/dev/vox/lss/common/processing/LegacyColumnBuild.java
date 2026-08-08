package dev.vox.lss.common.processing;

/**
 * One translated column build for a legacy (v19/v18/v16) recipient (C2, XVER §4.2):
 * the shipped bytes (native-layout body, zstd-framed for a compression-capable v19
 * session), the codec tag, and the rawSize RE-DERIVED from the translated body — the
 * legacy client's charge rule (and soak law A2's server book) read the bytes it
 * receives, never the v20 original's. Produced by each platform's
 * {@code buildLegacyColumn} and memoized on {@link ColumnBytes#legacyBuild} so a dedup
 * fan-out costs one translation per column, not one per recipient.
 */
public record LegacyColumnBuild(byte[] shipped, byte codecTag, int rawSize) {}
