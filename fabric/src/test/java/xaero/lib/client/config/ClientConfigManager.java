package xaero.lib.client.config;

import xaero.lib.common.config.option.BooleanConfigOption;
import xaero.lib.common.config.option.ConfigOption;

/** Tier-1 stub (xaerolib). {@code override} simulates a foreign value shape; {@code throwing}
 *  a failing read — both must leave the bridge's switches OPEN. */
public class ClientConfigManager {
    public Object override;
    public boolean throwing;

    public Object getEffective(ConfigOption option) {
        if (this.throwing) throw new IllegalStateException("armed getEffective throw");
        if (this.override != null) return this.override;
        return ((BooleanConfigOption) option).value;
    }
}
