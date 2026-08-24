package xaero.map;

import xaero.lib.common.config.channel.ConfigChannel;

/** Tier-1 stub — the optional crash/settings surface (plan §16). */
public class WorldMap {
    public static CrashHandler crashHandler = new CrashHandler();
    public static WorldMap INSTANCE = new WorldMap();
    public final ConfigChannel configs = new ConfigChannel();

    public ConfigChannel getConfigs() { return this.configs; }
}
