package me.drex.antixray.common.util.controller;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TEST STUB — see {@link ChunkPacketBlockController}'s stub note. The engine-mode-1
 * controller class the LSS probe discriminates by IDENTITY ({@code isInstance}): only the
 * HIDE controller's {@code obfuscateGlobal} is a usable hidden list; the Obfuscate /
 * ObfuscateLayer subclasses (modes 2/3) carry the hidden ∪ replacement union there.
 */
public class HideChunkPacketBlockController extends ChunkPacketBlockControllerAntiXray {

    public HideChunkPacketBlockController(Object2BooleanOpenHashMap<BlockState> obfuscateGlobal,
                                          int maxBlockHeight) {
        super(obfuscateGlobal, maxBlockHeight);
    }
}
