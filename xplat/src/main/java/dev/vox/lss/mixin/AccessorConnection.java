package dev.vox.lss.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link Connection}'s private netty channel so the per-player outbound-buffer
 * gauge can measure transport pressure — the H1 measurement of
 * docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.3.
 *
 * <p>The field is non-volatile in vanilla and assigned on the netty thread at channel
 * activation; reading it from the server thread is a benign publication race (worst case a
 * null on the very first tick of a connection, which the probe reports as no-signal).
 *
 * <p>Methods are {@code lss$}-prefixed because mixin adds them to the target class.
 */
@Mixin(Connection.class)
public interface AccessorConnection {
    @Accessor("channel")
    Channel lss$getChannel();
}
