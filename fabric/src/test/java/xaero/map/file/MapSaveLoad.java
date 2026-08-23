package xaero.map.file;

import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;

/** Tier-1 stub — records the load-request dance. requestLoad is STATEFUL like the
 *  real loader (reloadHasBeenRequested flips, so canRequestReload_unsynced answers
 *  false until the test "lands" the load) and records the region's beingWritten
 *  STATE at request time — the honest setBeingWritten-before-requestLoad pin
 *  (3-Opus fold: event ORDER was vacuous, the commit probe also sets it). */
public class MapSaveLoad {
    public boolean regionDetectionComplete = true;
    public LeveledRegion<?> nextToLoadByViewing;
    public final java.util.List<MapRegion> loadRequests = new java.util.ArrayList<>();

    public boolean isRegionDetectionComplete() { return this.regionDetectionComplete; }

    public void requestLoad(MapRegion region, String reason) {
        this.loadRequests.add(region);
        region.canRequestReload = false; // models reloadHasBeenRequested
        dev.vox.lss.compat.XaeroStubEvents.record("saveLoad.requestLoad " + reason
                + (Boolean.TRUE.equals(region.beingWritten) ? " beingWritten" : " NOT-beingWritten"));
    }

    public LeveledRegion<?> getNextToLoadByViewing() { return this.nextToLoadByViewing; }

    public void setNextToLoadByViewing(LeveledRegion<?> region) {
        this.nextToLoadByViewing = region;
        dev.vox.lss.compat.XaeroStubEvents.record("saveLoad.setNextToLoadByViewing");
    }
}
