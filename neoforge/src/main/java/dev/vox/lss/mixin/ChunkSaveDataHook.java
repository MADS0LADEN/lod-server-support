package dev.vox.lss.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge twin of the fabric {@code ChunkSaveDataHook} (issue #69): dirty
 * detection hangs off {@code SerializableChunkData.copyOf} — the
 * snapshot-for-saving choke point every chunk system funnels through. Same
 * vanilla target, same {@code require = 0} soft-fail (a missing target must
 * degrade dirty DETECTION, never crash the server); the body delegates to this
 * loader's {@code LSSServerNetworking.onChunkSaveData} → the shared
 * {@code ServerReceiverGlue}.
 */
@Mixin(SerializableChunkData.class)
public abstract class ChunkSaveDataHook {

    @Inject(method = "copyOf", at = @At("RETURN"), require = 0)
    private static void lss$onChunkSaveData(ServerLevel level, ChunkAccess chunk,
                                            CallbackInfoReturnable<SerializableChunkData> cir) {
        dev.vox.lss.networking.server.LSSServerNetworking.onChunkSaveData(level, chunk);
    }
}
