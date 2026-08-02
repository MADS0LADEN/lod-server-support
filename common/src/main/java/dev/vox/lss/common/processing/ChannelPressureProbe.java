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

    /** The default for every state until a platform wires a real probe. */
    ChannelPressureProbe NO_SIGNAL = () -> -1L;
}
