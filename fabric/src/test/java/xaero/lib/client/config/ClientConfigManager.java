package xaero.lib.client.config;

import xaero.lib.common.config.option.BooleanConfigOption;
import xaero.lib.common.config.option.ConfigOption;

/** Tier-1 stub (xaerolib). */
public class ClientConfigManager {
    public Object getEffective(ConfigOption option) {
        return ((BooleanConfigOption) option).value;
    }
}
