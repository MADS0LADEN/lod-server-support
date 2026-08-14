package dev.vox.lss.config;

import dev.vox.lss.common.config.ServerConfigBase;

public class LSSServerConfig extends ServerConfigBase {
    public static final LSSServerConfig CONFIG =
            load(LSSServerConfig.class, serverConfigCandidates(), dev.vox.lss.platform.LoaderServices.get().configDir());
}
