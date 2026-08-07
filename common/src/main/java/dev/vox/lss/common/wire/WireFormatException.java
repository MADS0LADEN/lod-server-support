package dev.vox.lss.common.wire;

/**
 * Structural violation while reading or writing a wire-format byte body (truncation,
 * negative or over-bounds counts, oversized VarInts, layout-rule violations). Unchecked
 * so byte-domain helpers compose without checked-exception plumbing; every hostile-input
 * path in {@link WireSectionCursor} is pinned to throw THIS (never
 * {@code ArrayIndexOutOfBoundsException}, never {@code NegativeArraySizeException},
 * never {@code OutOfMemoryError} from a wire-claimed allocation).
 */
public final class WireFormatException extends RuntimeException {
    public WireFormatException(String message) {
        super(message);
    }
}
