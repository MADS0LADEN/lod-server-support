package dev.vox.lss.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the protected {@code connection} of a player's packet listener, the first hop of
 * the outbound-buffer gauge (see {@link AccessorConnection}).
 *
 * <p>Methods are {@code lss$}-prefixed because mixin adds them to the target class.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface AccessorServerCommonPacketListener {
    @Accessor("connection")
    Connection lss$getConnection();
}
