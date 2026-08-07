package dev.vox.lss.common.processing;

/**
 * Per-player transport pressure: how many bytes are queued in the player's network
 * outbound buffer but not yet accepted by the socket.
 *
 * <p>This is the measurement the elytra chunk-wall investigation could never take
 * (docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.3, hypothesis H1). LSS
 * column payloads and vanilla's chunk packets share one netty channel with no
 * prioritisation, so a deep LSS-fed outbound buffer delays every vanilla chunk packet
 * written behind it — head-of-line blocking that no server-side counter shows today
 * (the LSS send queue reads empty while the netty buffer is full).
 *
 * <p><b>{@code -1} means NO SIGNAL</b>, never "zero". Every consumer must treat it as
 * "do not throttle": an unresolvable channel (mixin/reflection miss on a future MC, a
 * closed socket mid-tick) degrades to today's exact behaviour instead of stalling a
 * player forever. Implementations must never throw.
 */
@FunctionalInterface
public interface ChannelPressureProbe {

    /** Bytes queued in the outbound buffer, or {@code -1} when unmeasurable. */
    long pendingOutboundBytes();

    /**
     * One coherent read of the channel's outbound state, for consumers that need more
     * than the depth gauge (the transport yield's writability gate, the move-desync
     * tracer's envelope). The default keeps every lambda-shaped probe (including
     * {@link #NO_SIGNAL} and test rigs) compiling — and degrades them to
     * "writability unknown", which every consumer must treat as "do not yield":
     * the same fail-safe direction as the {@code -1} depth contract.
     */
    default Snapshot snapshot() {
        return new Snapshot(pendingOutboundBytes(), Snapshot.UNKNOWN_MARK, Writability.UNKNOWN);
    }

    /** Tri-state channel writability. {@link #UNKNOWN} must never trigger a yield. */
    enum Writability {
        WRITABLE, NOT_WRITABLE, UNKNOWN
    }

    /**
     * @param pendingBytes  queued outbound bytes, or {@code -1} for no signal
     * @param highWaterMark the channel's write-buffer high-water mark, or
     *                      {@link #UNKNOWN_MARK} when unreadable
     * @param writable      netty's own writability flag, {@link Writability#UNKNOWN}
     *                      when the channel could not be read
     */
    record Snapshot(long pendingBytes, long highWaterMark, Writability writable) {
        public static final long UNKNOWN_MARK = -1L;
    }

    /** The default for every state until a platform wires a real probe. */
    ChannelPressureProbe NO_SIGNAL = () -> -1L;
}
