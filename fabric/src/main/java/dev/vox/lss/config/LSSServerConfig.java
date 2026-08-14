package dev.vox.lss.config;

import dev.vox.lss.common.config.ServerConfigBase;
import net.fabricmc.loader.api.FabricLoader;

public class LSSServerConfig extends ServerConfigBase {
    public static final LSSServerConfig CONFIG =
            load(LSSServerConfig.class, serverConfigCandidates(), FabricLoader.getInstance().getConfigDir());
}
