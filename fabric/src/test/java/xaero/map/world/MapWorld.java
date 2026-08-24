package xaero.map.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Tier-1 stub. */
public class MapWorld {
    public boolean cacheOnlyMode;
    public ResourceKey<Level> currentDimensionId;

    public final MapDimension currentDimension = new MapDimension();

    public boolean isCacheOnlyMode() { return this.cacheOnlyMode; }

    public MapDimension getCurrentDimension() { return this.currentDimension; }

    public ResourceKey<Level> getCurrentDimensionId() { return this.currentDimensionId; }
}
