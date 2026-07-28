package dev.vox.lss.mixin;

import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public class IntegratedServerLanHook {
    // MC 26.2 has two publishServer overloads. The 4-arg (scope, gameType, allowCheats,
    // port) is a thin wrapper that delegates into THIS 2-arg overload — and the LAN screen
    // (MultiplayerOptionsScreen.changeMultiplayerScope) calls the 2-arg one DIRECTLY, so
    // hooking the 4-arg (the shipped v0.6.0–v0.8.0 target) never fired for "Open to LAN";
    // only /publish worked. Injecting here covers both entry points exactly once. The full
    // descriptor stays pinned so mixin resolution cannot drift to the wrapper; the
    // descriptor-vs-real-overload agreement is enforced by LanHookContractTest.
    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
            at = @At("RETURN"))
    private void lss$onLanPublished(MinecraftServer.MultiplayerScope scope, int port,
                                     CallbackInfoReturnable<Boolean> cir) {
        // getReturnValue() is read HERE, in the callback frame (false for scope-off,
        // already-published, and listener IOException — no spurious starts); only the
        // start itself is deferred (see startServiceForLan's server-thread hop).
        if (cir.getReturnValue()) {
            LSSServerNetworking.startServiceForLan((IntegratedServer) (Object) this);
        }
    }
}
