package xaero.map.region;

/** Tier-1 stub — records the writeChunk lifecycle calls the compat must mirror. */
public class MapRegion extends LeveledRegion<Object> {
    public final Object writerThreadPauseSync = new Object();
    public boolean writingPaused;
    public byte loadState = 2;
    public boolean resting = true;
    public boolean canRequestReload = true;
    public int visits;
    public Boolean beingWritten; // null until first set — pins "set true, never cleared"
    public final MapTileChunk[][] chunks = new MapTileChunk[8][8];

    public boolean isWritingPaused() { return this.writingPaused; }

    public byte getLoadState() { return this.loadState; }

    public void setLoadState(byte state) { this.loadState = state; }

    public boolean isResting() { return this.resting; }

    public void registerVisit() { this.visits++; }

    public void setBeingWritten(boolean beingWritten) {
        this.beingWritten = beingWritten;
        dev.vox.lss.compat.XaeroStubEvents.record("region.setBeingWritten " + beingWritten);
    }

    public boolean canRequestReload_unsynced() { return this.canRequestReload; }

    public void setAllCachePrepared(boolean prepared) {
        dev.vox.lss.compat.XaeroStubEvents.record("region.setAllCachePrepared " + prepared);
    }

    public MapTileChunk getChunk(int x, int z) { return this.chunks[x][z]; }

    public void setChunk(int x, int z, MapTileChunk chunk) { this.chunks[x][z] = chunk; }
}
