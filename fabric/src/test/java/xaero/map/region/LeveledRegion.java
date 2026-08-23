package xaero.map.region;

/** Tier-1 stub parent (the pacing gauge lives here in the real jar). */
public class LeveledRegion<T> {
    public boolean allowAnotherRegionToLoad = true;

    public boolean shouldAllowAnotherRegionToLoad() { return this.allowAnotherRegionToLoad; }
}
