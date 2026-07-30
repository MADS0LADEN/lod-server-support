package dev.vox.lss.mixin;

import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dirty-detection source: fires whenever a chunk save actually proceeds, on the thread
 * that owns the live chunk. Retargeted from {@code ChunkMap.save} to
 * {@code SerializableChunkData.copyOf} (issue #69): {@code copyOf(level, chunk)} is the
 * synchronous "snapshot the live chunk for saving" choke point that BOTH chunk systems
 * share — vanilla's {@code ChunkMap.save} calls it directly, and Moonrise's replacement
 * pipeline ({@code NewChunkHolder.saveChunk}, which @Overwrites {@code ChunkMap.save}
 * into a dead throw-stub and saves through its own scheduler) calls the exact same
 * static (verified against Moonrise-Fabric 1.1.0 bytecode). Both callers reach it only
 * after {@code tryMarkSaved()}/needs-saving gating, so "copyOf ran" means a real save is
 * committing, the same evidence the old RETURN-value check gave. The hook body lives in
 * {@link LSSServerNetworking#onChunkSaveData} (mixin classes refuse classloading under
 * fabric-loader-junit, so logic in the mixin is untestable logic).
 */
@Mixin(SerializableChunkData.class)
public class ChunkSaveDataHook {

    // require = 0 (issue #69's crash lesson, kept on the new target): a future
    // chunk-system overhaul that bypasses even copyOf must degrade to "no save-driven
    // dirty detection" (edits refresh on rejoin), never to a fatal InjectionError under
    // the config's defaultRequire=1. Vanilla-path regression cover: the Tier-2
    // DirtyContentFilter gametests and the dirty-broadcast soak both fail if this hook
    // silently stops firing on an unmodified server, and Mixin still logs its own
    // expect-level warning when the target is missing. Pinned by SaveHookContractTest
    // (source-regex).
    @Inject(method = "copyOf", at = @At("RETURN"), require = 0)
    private static void lss$onChunkSaveData(ServerLevel level, ChunkAccess chunk,
                                            CallbackInfoReturnable<SerializableChunkData> cir) {
        LSSServerNetworking.onChunkSaveData(level, chunk);
    }
}
