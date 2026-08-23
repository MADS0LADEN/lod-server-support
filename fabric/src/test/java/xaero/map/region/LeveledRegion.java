package xaero.map.region;

/** Tier-1 stub parent (the pacing gauge lives here in the real jar too; the real
 *  class is abstract with a RegionTexture-bounded type parameter — irrelevant to
 *  the resolved members). Retained SOLELY for the negative pins: the gauge
 *  consult is EVENT-RECORDED so tests can assert it is NEVER invoked (the real
 *  method synchronizes on its own — possibly BRANCH — region, and Xaero's loader
 *  thread nests parent-then-leaf, so consulting under a leaf monitor is a
 *  lock-order inversion; review MAJOR — a real client deadlock). The paired
 *  {@code setNextToLoadByViewing} token (stub MapSaveLoad) is likewise
 *  never-called-pinned: the loader never reads it, only the four native
 *  consumers' pacing does (3-Opus fold). */
public class LeveledRegion<T> {
    public boolean allowAnotherRegionToLoad = true;

    public boolean shouldAllowAnotherRegionToLoad() {
        dev.vox.lss.compat.XaeroStubEvents.record("pacing.shouldAllowAnotherRegionToLoad");
        return this.allowAnotherRegionToLoad;
    }
}
