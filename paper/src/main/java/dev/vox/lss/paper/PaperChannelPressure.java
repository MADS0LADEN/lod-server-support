package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lss.common.processing.OutboundBufferMath;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import java.lang.reflect.Field;

/**
 * Paper adapter for the per-player outbound-buffer gauge — the twin of Fabric's
 * {@code FabricChannelPressure}, using reflection where Fabric uses accessor mixins:
 * {@code ServerCommonPacketListenerImpl.connection} is {@code protected} and
 * {@code Connection.channel} is {@code private}, neither reachable from this package.
 *
 * <p>Resolution happens once per JVM behind a lazy holder (the
 * {@code MoonriseReadCompat} shape); any failure — a renamed field on a future NMS, a
 * module/access restriction — yields no-signal forever after one warning, which leaves the
 * gauge blank and the deference gate inert rather than misreporting.
 */
final class PaperChannelPressure {

    private PaperChannelPressure() {}

    /** Resolved once; both fields null when the shape could not be resolved. */
    private static final class Holder {
        static final Field CONNECTION;
        static final Field CHANNEL;

        static {
            Field connection = null;
            Field channel = null;
            try {
                connection = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
                connection.setAccessible(true);
                channel = Connection.class.getDeclaredField("channel");
                channel.setAccessible(true);
            } catch (Throwable t) {
                connection = null;
                channel = null;
                LSSLogger.warn("Outbound-buffer gauge unavailable (" + t + ") —"
                        + " /lsslod diag will show obuf=n/a and transport deference stays inert");
            }
            CONNECTION = connection;
            CHANNEL = channel;
        }
    }

    static ChannelPressureProbe forPlayer(ServerPlayer player) {
        if (Holder.CONNECTION == null || Holder.CHANNEL == null) {
            return ChannelPressureProbe.NO_SIGNAL;
        }
        return () -> {
            try {
                var listener = player.connection;
                if (listener == null) return OutboundBufferMath.NO_SIGNAL;
                var connection = (Connection) Holder.CONNECTION.get(listener);
                if (connection == null) return OutboundBufferMath.NO_SIGNAL;
                var channel = (Channel) Holder.CHANNEL.get(connection);
                if (channel == null) return OutboundBufferMath.NO_SIGNAL;
                var config = channel.config();
                return OutboundBufferMath.pendingBytes(
                        channel.isActive(), channel.isWritable(),
                        channel.bytesBeforeUnwritable(), channel.bytesBeforeWritable(),
                        config.getWriteBufferHighWaterMark(), config.getWriteBufferLowWaterMark());
            } catch (Throwable t) {
                return OutboundBufferMath.NO_SIGNAL;
            }
        };
    }
}
