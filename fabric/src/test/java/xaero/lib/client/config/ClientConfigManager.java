package xaero.lib.client.config;

import xaero.lib.common.config.option.BooleanConfigOption;
import xaero.lib.common.config.option.ConfigOption;

/** Tier-1 stub (xaerolib). {@code override} simulates a foreign value shape; {@code throwing}
 *  a failing read — both must leave the bridge's switches OPEN. */
public class ClientConfigManager {
    public Object override;
    public boolean throwing;

    /** The real class also declares a 2-arg overload — methodByName(…, 1) must pick this one. */
    public Object getEffective(ConfigOption option, Object profile) {
        throw new IllegalStateException("the 2-arg overload must never be bound");
    }

    public Object getEffective(ConfigOption option) {
        if (this.throwing) throw new IllegalStateException("armed getEffective throw");
        if (this.override != null) return this.override;
        return ((BooleanConfigOption) option).value;
    }
}
