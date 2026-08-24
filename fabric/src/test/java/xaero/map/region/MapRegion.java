package xaero.map.region;

/** Tier-1 stub — records the writeChunk lifecycle calls the compat must mirror,
 *  and ENFORCES the region-monitor discipline: the state accessors throw unless
 *  the caller holds the monitors the native writer holds (review MAJOR: without
 *  this, dropping a synchronized block ships green). In the real jar
 *  setAllCachePrepared and the load-pacing members live on LeveledRegion. */
public class MapRegion extends LeveledRegion<Object> {
    public final Object writerThreadPauseSync = new Object();
    public boolean writingPaused;
    public byte loadState = 2;
    public boolean resting = true;
    /** Models the real formula's pending-flags conjunct (!reloadHasBeenRequested
     *  && !recacheHasBeenRequested && !isRefreshing) — the stub MapSaveLoad flips
     *  it false on requestLoad, so a granted region honestly reads in-flight. */
    public boolean canRequestReload = true;
    public int visits;
    public Boolean beingWritten; // null until first set — pins "set true, never cleared"
    /** The saver's cache precondition (real: LeveledRegion) — a rebuild flips it false. */
    public boolean allCachePrepared = true;
    public final MapTileChunk[][] chunks = new MapTileChunk[8][8];

    private void requireRegionMonitor(String site) {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException(site + " outside the region monitor — the native"
                    + " writeChunk discipline requires synchronized(region)");
        }
    }

    /** Gate probes (writer-pause monitor acquisitions) — the flush must probe a
     *  not-ready region ONCE per pump, not once per owed tile chunk. */
    public int gateProbes;

    public boolean isWritingPaused() {
        this.gateProbes++;
        if (!Thread.holdsLock(this.writerThreadPauseSync)) {
            throw new IllegalStateException("region.isWritingPaused outside"
                    + " writerThreadPauseSync — the save-race exclusion's monitor");
        }
        return this.writingPaused;
    }

    public byte getLoadState() {
        requireRegionMonitor("getLoadState");
        return this.loadState;
    }

    public void setLoadState(byte state) {
        requireRegionMonitor("setLoadState");
        this.loadState = state;
        dev.vox.lss.compat.XaeroStubEvents.record("region.setLoadState " + state);
    }

    /** FAITHFUL formula (decompiled): loadState != 3 && != 1 && !recache — so a
     *  cache-parked (3) region reads not-resting until the 3→4 revival, exactly
     *  like the real loader's dead end. The manual field models the recache term. */
    public boolean isResting() {
        requireRegionMonitor("isResting");
        return this.resting && this.loadState != 3 && this.loadState != 1;
    }

    public void registerVisit() {
        requireRegionMonitor("registerVisit");
        this.visits++;
    }

    public void setBeingWritten(boolean beingWritten) {
        requireRegionMonitor("setBeingWritten");
        this.beingWritten = beingWritten;
        dev.vox.lss.compat.XaeroStubEvents.record("region.setBeingWritten " + beingWritten);
    }

    /** FAITHFUL formula (decompiled): pending-flags-clear && (ls 0 | 4 | 2-and-
     *  beingWritten) — the memoryless window's honest in-flight predicate. */
    public boolean canRequestReload_unsynced() {
        requireRegionMonitor("canRequestReload_unsynced");
        return this.canRequestReload && (this.loadState == 0 || this.loadState == 4
                || (this.loadState == 2 && Boolean.TRUE.equals(this.beingWritten)));
    }

    public void setAllCachePrepared(boolean prepared) {
        this.allCachePrepared = prepared;
        dev.vox.lss.compat.XaeroStubEvents.record("region.setAllCachePrepared " + prepared);
    }

    public MapTileChunk getChunk(int x, int z) { return this.chunks[x][z]; }

    /** Records the monitor state: the bridge's rollback is a chosen tightening (native's
     *  own pc-1526 rollback runs outside the region monitor), pinned as "locked". */
    public void setChunk(int x, int z, MapTileChunk chunk) {
        this.chunks[x][z] = chunk;
        dev.vox.lss.compat.XaeroStubEvents.record("region.setChunk " + x + "," + z
                + (chunk == null ? " null" : "") + (Thread.holdsLock(this) ? " locked" : " unlocked"));
    }
}
