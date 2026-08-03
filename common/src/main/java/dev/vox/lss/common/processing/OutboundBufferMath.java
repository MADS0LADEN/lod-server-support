package dev.vox.lss.common.processing;

/**
 * Recovers the ABSOLUTE outbound-buffer depth of a netty channel from its public
 * {@code Channel} API, without reaching into {@code unsafe()}/{@code ChannelOutboundBuffer}.
 *
 * <p>Pure arithmetic over primitives so it is unit-testable without netty on the classpath;
 * the platform adapters read the four values off a real channel. Validated end-to-end
 * against a real {@code EmbeddedChannel} in the Fabric test tier — a hand-written fake
 * channel would only assert this formula against itself.
 *
 * <p><b>Why absolute depth and not {@code isWritable()}.</b> Vanilla sets no
 * {@code WriteBufferWaterMark}, so netty's defaults apply (low 32 KiB / high 64 KiB) while
 * LSS flushes hundreds of KB per tick. A raw {@code isWritable()} gate would therefore read
 * false constantly on a perfectly healthy link and oscillate. The real question is "how many
 * bytes are queued", which needs the water marks folded back in.
 *
 * <p><b>The netty 4.2 identities</b> ({@code ChannelOutboundBuffer}), note the {@code +1}:
 * <pre>
 *   bytesBeforeUnwritable = high - pending + 1   (0 when already unwritable)
 *   bytesBeforeWritable   = pending - low  + 1   (0 when already writable)
 * </pre>
 */
public final class OutboundBufferMath {

    /** Returned when the channel cannot give a trustworthy answer. Never means "empty". */
    public static final long NO_SIGNAL = -1L;

    private OutboundBufferMath() {}

    /**
     * @param active                 {@code Channel.isActive()} — a closed/unregistered
     *                               channel has no outbound buffer to measure
     * @param writable               {@code Channel.isWritable()}
     * @param bytesBeforeUnwritable  {@code Channel.bytesBeforeUnwritable()}
     * @param bytesBeforeWritable    {@code Channel.bytesBeforeWritable()} — <b>returns
     *                               {@code Long.MAX_VALUE}</b> when the outbound buffer is
     *                               null, which is exactly the state {@code isWritable()}
     *                               reports as false; folding that into the formula would
     *                               overflow to ≈ -9.2e18
     * @param highWaterMark          {@code ChannelConfig.getWriteBufferHighWaterMark()}
     * @param lowWaterMark           {@code ChannelConfig.getWriteBufferLowWaterMark()}
     * @return queued bytes, or {@link #NO_SIGNAL}
     */
    public static long pendingBytes(boolean active, boolean writable,
                                    long bytesBeforeUnwritable, long bytesBeforeWritable,
                                    int highWaterMark, int lowWaterMark) {
        if (!active) return NO_SIGNAL;
        if (writable) {
            // 0 means "already at or past the mark" per netty's guard, which cannot happen while
            // writable except exactly AT it — report the mark rather than a negative.
            return bytesBeforeUnwritable <= 0
                    ? highWaterMark
                    : highWaterMark - bytesBeforeUnwritable + 1;
        }
        // Unwritable with a 0 or MAX_VALUE reading is not a depth: 0 comes from a
        // user-defined writability flag over a near-empty buffer (a phantom low-water
        // reading if folded in), MAX_VALUE from a null outbound buffer.
        if (bytesBeforeWritable <= 0 || bytesBeforeWritable == Long.MAX_VALUE) return NO_SIGNAL;
        return lowWaterMark + bytesBeforeWritable - 1;
    }
}
