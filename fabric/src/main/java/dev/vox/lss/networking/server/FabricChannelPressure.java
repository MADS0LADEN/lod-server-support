package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lss.common.processing.OutboundBufferMath;
import dev.vox.lss.mixin.AccessorConnection;
import dev.vox.lss.mixin.AccessorServerCommonPacketListener;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric adapter for the per-player outbound-buffer gauge: player → packet listener →
 * {@code Connection} → netty {@code Channel}, then
 * {@link OutboundBufferMath#pendingBytes}. See
 * docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.3.
 *
 * <p>Every failure shape degrades to no-signal, warned once. A diagnostic must never be
 * able to take a server down, and a no-signal probe leaves the deference gate inert — the
 * same fail-safe direction as {@code backgroundIncompatible} on the read path.
 */
final class FabricChannelPressure {

    private static volatile boolean warned;

    private FabricChannelPressure() {}

    /** A probe bound to one player. Re-reads the channel each call — connections are
     *  replaced on reconnect and the field is assigned on the netty thread. */
    static ChannelPressureProbe forPlayer(ServerPlayer player) {
        return () -> {
            try {
                var listener = player.connection;
                if (listener == null) return OutboundBufferMath.NO_SIGNAL;
                var connection = ((AccessorServerCommonPacketListener) listener).lss$getConnection();
                if (connection == null) return OutboundBufferMath.NO_SIGNAL;
                var channel = ((AccessorConnection) connection).lss$getChannel();
                if (channel == null) return OutboundBufferMath.NO_SIGNAL;
                var config = channel.config();
                return OutboundBufferMath.pendingBytes(
                        channel.isActive(), channel.isWritable(),
                        channel.bytesBeforeUnwritable(), channel.bytesBeforeWritable(),
                        config.getWriteBufferHighWaterMark(), config.getWriteBufferLowWaterMark());
            } catch (Throwable t) {
                // ClassCastException here means the accessor mixins did not apply (a future
                // MC renaming either field) — latch a warning and stay silent thereafter.
                if (!warned) {
                    warned = true;
                    LSSLogger.warn("Outbound-buffer gauge unavailable (" + t + ") —"
                            + " /lsslod diag will show obuf=n/a and transport deference stays inert");
                }
                return OutboundBufferMath.NO_SIGNAL;
            }
        };
    }
}
