package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Xaero's World Map bridge (issue #223, docs/planning/xaero-map-bridge-plan.md):
 * writes LSS-delivered LOD columns into Xaero's World Map so the map records
 * terrain far beyond vanilla render distance. Pure reflection — zero compile-time
 * dependency, zero mixins (every member the bridge touches is public in Xaero WM
 * 1.45.0, verified 26.2 ≡ 1.21.1) — following the {@code VoxyCompat}/
 * {@code MoonriseReadCompat} interop discipline: any resolve failure disables the
 * bridge with one warn; runtime failures latch it dead after
 * {@value #THROW_LATCH} consecutive commit failures; LOD delivery is NEVER
 * affected (the consumer swallows everything and never reports ingest failure —
 * a map problem must not trigger re-serves).
 *
 * <p>Two-stage pipeline (plan §2.4): the registered {@link VoxelColumnConsumer}
 * extracts a {@link XaeroTileExtractor.PreparedTile} on the LSS decode thread and
 * offers it to a bounded latest-wins queue; {@link #pump()} — the shared
 * end-of-client-tick body, MAIN CLIENT THREAD (Xaero enforces it with
 * {@code isSameThread} throws) — re-runs the native writer's gate ladder
 * verbatim, then commits under Xaero's own locks in the decompiled
 * {@code MapWriter.writeChunk} sequence, including the mandatory paced
 * {@code requestLoad} dance for regions Xaero hasn't loaded (fresh regions never
 * self-promote) and the set-never-clear {@code setBeingWritten} lifecycle (the
 * save path owns the reset). GPU work stays Xaero's: the bridge only flags
 * {@code setToUpdateBuffers}, never calls {@code updateBuffers}.
 */
final class XaeroMapCompat {

    static final int MAX_QUEUE = 2048;
    static final int MAX_COMMITS_PER_PUMP = 8;
    static final long PUMP_NANOS_BUDGET = 2_000_000L;
    /** Ladder-ready deferrals (busy region, PBO download) before an entry drops. */
    static final int DEFER_CAP = 200;
    /** Consecutive pump/commit failures before the bridge latches dead for the session. */
    static final int THROW_LATCH = 5;
    /** The surface layer — native {@code caveLayer} sentinel. */
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    private static final LogThrottle EXTRACT_FAIL_WARN = new LogThrottle(60_000);
    private static final LogThrottle COMMIT_FAIL_WARN = new LogThrottle(60_000);

    // ---- test seams (the VoxyCompat discipline: default-wired to production) ----

    /** Resolves the reflected Xaero class names — test seam. */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /**
     * The two operations the pump needs on Xaero's world object. A seam because
     * {@code ClientLevel} is unconstructible under fabric-loader-junit — the stub
     * {@code MapProcessor.getWorld()} returns a plain marker object and tests map
     * it here; production casts.
     */
    interface LevelOps {
        Object dimension(Object world);
        boolean isChunkLoaded(Object world, int chunkX, int chunkZ);
    }

    static final LevelOps PRODUCTION_LEVEL_OPS = new LevelOps() {
        @Override
        public Object dimension(Object world) {
            return ((ClientLevel) world).dimension();
        }

        @Override
        public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
            var chunk = ((ClientLevel) world).getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return chunk != null && !(chunk instanceof EmptyLevelChunk);
        }
    };

    // ---- static facade (production wiring; ModCompat owns the instance) ----

    private static volatile XaeroMapCompat instance;

    /** Client init, Xaero present: resolve + register the consumer (if enabled). */
    static boolean init() {
        try {
            var h = Handles.resolve(Class::forName);
            var bridge = new XaeroMapCompat(h, PRODUCTION_LEVEL_OPS,
                    () -> LSSClientConfig.CONFIG.enableXaeroMapBridge,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer);
            bridge.reconcileRegistration();
            instance = bridge;
            LSSLogger.info("Xaero's World Map detected — LOD map bridge active");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                 | IllegalAccessException e) {
            LSSLogger.warn("Xaero map bridge: this Xaero's World Map version has a different"
                    + " internal surface — bridge disabled (" + e + ")");
            return false;
        } catch (Throwable e) {
            LSSLogger.error("Failed to initialize the Xaero map bridge", e);
            return false;
        }
    }

    /** End-of-client-tick body (main client thread). */
    static void clientTick() {
        var bridge = instance;
        if (bridge != null) bridge.pump();
    }

    /** Disconnect body — a session's queued tiles never outlive it. */
    static void onDisconnect() {
        var bridge = instance;
        if (bridge != null) bridge.clearQueue();
    }

    /** The conditional {@code /lss diag} line, or null when Xaero was never detected. */
    static String diagLine() {
        var bridge = instance;
        return bridge == null ? null : bridge.describe();
    }

    // ---- instance ----

    private final Handles h;
    private final LevelOps levelOps;
    private final BooleanSupplier enabled;
    private final java.util.function.Consumer<VoxelColumnConsumer> registrar;
    private final java.util.function.Consumer<VoxelColumnConsumer> deregistrar;
    private final VoxelColumnConsumer consumer;
    /** Whether the consumer is currently registered with LSSApi. Main thread only. */
    private boolean registered;

    private final Object queueLock = new Object();
    /** Packed chunk pos → entry; insertion-ordered, latest tile wins in place. */
    private final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skippedLoaded = new AtomicLong();
    private final AtomicLong deferrals = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong loadRequests = new AtomicLong();
    private volatile boolean dead;
    private int consecutiveFailures; // main thread only
    /** The per-pump time budget — a field so tests can neutralize MethodHandle warmup. */
    long pumpNanosBudget = PUMP_NANOS_BUDGET;

    private static final class Entry {
        volatile XaeroTileExtractor.PreparedTile tile; // replaced under queueLock (latest wins)
        final Object dimension;
        int ladderReadyDeferrals; // main thread only

        Entry(Object dimension, XaeroTileExtractor.PreparedTile tile) {
            this.dimension = dimension;
            this.tile = tile;
        }
    }

    XaeroMapCompat(Handles h, LevelOps levelOps, BooleanSupplier enabled,
                   java.util.function.Consumer<VoxelColumnConsumer> registrar,
                   java.util.function.Consumer<VoxelColumnConsumer> deregistrar) {
        this.h = h;
        this.levelOps = levelOps;
        this.enabled = enabled;
        this.registrar = registrar;
        this.deregistrar = deregistrar;
        this.consumer = buildConsumer();
    }

    /**
     * (De)register the consumer to match enabled ∧ ¬dead. Live registration matters
     * beyond this bridge: {@code LSSApi.hasVoxelConsumers()} drives the handshake's
     * CAPABILITY_VOXEL_COLUMNS bit, so an Xaero-only install (no Voxy) legitimately
     * subscribes to LOD data — that IS the feature — while a disabled or dead bridge
     * must not hold the bit (or a map-less client would download the whole disc for
     * nothing on its next join). Main thread (init + pump).
     */
    void reconcileRegistration() {
        boolean want = !this.dead && this.enabled.getAsBoolean();
        if (want && !this.registered) {
            this.registrar.accept(this.consumer);
            this.registered = true;
        } else if (!want && this.registered) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        }
    }

    /** The registered consumer — a thin shell over {@link #offerColumn}. */
    private VoxelColumnConsumer buildConsumer() {
        return (level, dimension, chunkX, chunkZ, columnData) -> {
            try {
                offerColumn(dimension, chunkX, chunkZ,
                        level.getMinY(), level.getMaxY() + 1, columnData);
            } catch (Throwable t) {
                // NEVER escape: LSSApi treats a consumer throw as an ingest failure and
                // re-serves the column — a map problem must not churn LOD delivery.
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                long n = EXTRACT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.warn("Xaero map bridge: tile extraction failed (" + n
                            + " failure(s) since the last report)", t);
                }
            }
        };
    }

    /** Decode-thread entry: extract + enqueue (latest-wins, bounded, oldest drops). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData) {
        if (this.dead || !this.enabled.getAsBoolean()) return;
        offerPrepared(dimension,
                XaeroTileExtractor.extract(chunkX, chunkZ, worldBottomY, worldTopY, columnData));
    }

    /** Enqueue seam (tests build {@link XaeroTileExtractor.PreparedTile}s directly). */
    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile) {
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        synchronized (this.queueLock) {
            var existing = this.queue.get(key);
            if (existing != null) {
                if (existing.dimension == dimension) {
                    existing.tile = tile;
                    return;
                }
                this.queue.remove(key); // stale-dimension entry: the new serve replaces it
            }
            if (this.queue.size() >= MAX_QUEUE) {
                var it = this.queue.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                    this.droppedCount.incrementAndGet();
                }
            }
            this.queue.put(key, new Entry(dimension, tile));
        }
    }

    void clearQueue() {
        synchronized (this.queueLock) {
            this.queue.clear();
        }
    }

    int queuedForTest() {
        synchronized (this.queueLock) {
            return this.queue.size();
        }
    }

    boolean deadForTest() {
        return this.dead;
    }

    long counterForTest(String name) {
        return switch (name) {
            case "written" -> this.written.get();
            case "skipped_loaded" -> this.skippedLoaded.get();
            case "deferred" -> this.deferrals.get();
            case "dropped" -> this.droppedCount.get();
            case "load_requests" -> this.loadRequests.get();
            default -> throw new IllegalArgumentException(name);
        };
    }

    String describe() {
        String state = this.dead ? "dead" : this.enabled.getAsBoolean() ? "active" : "disabled";
        return "XaeroMap: state=" + state + " queued=" + queuedForTest()
                + " written=" + this.written.get()
                + " skipped_loaded=" + this.skippedLoaded.get()
                + " deferred=" + this.deferrals.get()
                + " dropped=" + this.droppedCount.get()
                + " load_requests=" + this.loadRequests.get();
    }

    // ---- the pump (main client thread) ----

    void pump() {
        reconcileRegistration();
        if (this.dead) return;
        if (!this.enabled.getAsBoolean()) {
            clearQueue(); // the live toggle: flipping off drops the backlog immediately
            return;
        }
        List<Long> keys;
        synchronized (this.queueLock) {
            if (this.queue.isEmpty()) return;
            keys = new ArrayList<>(this.queue.keySet());
        }
        try {
            // No blanket failure-count reset here: commit failures are contained per
            // entry inside the drain, so the ladder returning normally proves nothing —
            // only a successful COMMIT resets the death-latch count.
            pumpLadder(keys);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
        }
    }

    /**
     * The native {@code MapWriter.onRender} gate ladder, verbatim (plan §2.7). Any
     * not-ready gate returns — entries stay queued (deferral, not deletion; the
     * bounded queue is the TTL). The {@code mainStuffSync} dimension equality is
     * THE anti-wrong-dimension binding: like Xaero's own writer, commits pause
     * while the user browses another dimension's map.
     */
    private void pumpLadder(List<Long> keys) throws Throwable {
        Object session = this.h.getCurrentSession.invoke();
        if (session == null || !(boolean) this.h.sessionIsUsable.invoke(session)) return;
        Object mp = this.h.getMapProcessor.invoke(session);
        if (mp == null) return;
        Object renderPause = this.h.renderThreadPauseSync.invoke(mp);
        synchronized (renderPause) {
            if ((boolean) this.h.isWritingPaused.invoke(mp)) return;
            if ((boolean) this.h.isWaitingForWorldUpdate.invoke(mp)) return;
            Object saveLoad = this.h.getMapSaveLoad.invoke(mp);
            if (!(boolean) this.h.isRegionDetectionComplete.invoke(saveLoad)) return;
            if (!(boolean) this.h.isCurrentMultiworldWritable.invoke(mp)) return;
            Object world = this.h.getWorld.invoke(mp);
            Object mapWorld = this.h.getMapWorld.invoke(mp);
            if (world == null || (boolean) this.h.isCurrentMapLocked.invoke(mp)
                    || (boolean) this.h.isCacheOnlyMode.invoke(mapWorld)) {
                return;
            }
            if (this.h.getCurrentWorldId.invoke(mp) == null
                    || (boolean) this.h.ignoreWorld.invoke(mp, world)) {
                return;
            }
            Object dimensionId;
            Object mainSync = this.h.mainStuffSync.invoke(mp);
            synchronized (mainSync) {
                if (this.h.mainWorld.invoke(mp) != world) return;
                dimensionId = this.h.getCurrentDimensionId.invoke(mapWorld);
                if (this.levelOps.dimension(world) != dimensionId) return;
            }
            drainEntries(keys, mp, saveLoad, world, dimensionId);
        }
    }

    private void drainEntries(List<Long> keys, Object mp, Object saveLoad,
                              Object world, Object dimensionId) throws Throwable {
        long start = System.nanoTime();
        int commits = 0;
        boolean loadRequestedThisPump = false;
        for (Long key : keys) {
            if (commits >= MAX_COMMITS_PER_PUMP || System.nanoTime() - start > this.pumpNanosBudget) {
                return;
            }
            Entry entry;
            synchronized (this.queueLock) {
                entry = this.queue.get(key);
            }
            if (entry == null) continue;
            if (entry.dimension != dimensionId) {
                // Can never become valid — the pump-side stale-dimension drop (plan §2.5).
                remove(key);
                this.droppedCount.incrementAndGet();
                continue;
            }
            var tile = entry.tile;
            if (this.levelOps.isChunkLoaded(world, tile.chunkX(), tile.chunkZ())) {
                // The native writer owns loaded chunks and reclaims them on its
                // clean-flag — never fight it (plan §2.6).
                remove(key);
                this.skippedLoaded.incrementAndGet();
                continue;
            }
            var outcome = commitEntry(mp, saveLoad, tile, loadRequestedThisPump);
            switch (outcome) {
                case COMMITTED -> {
                    remove(key);
                    this.written.incrementAndGet();
                    this.consecutiveFailures = 0;
                    commits++;
                }
                case AWAITING_LOAD_REQUESTED -> {
                    loadRequestedThisPump = true;
                    this.deferrals.incrementAndGet();
                }
                case AWAITING_LOAD -> this.deferrals.incrementAndGet();
                case DEFERRED -> {
                    // Ladder was ready but the region/tile-chunk wasn't — this is the
                    // only defer flavor that burns the per-entry budget (plan §2.7).
                    this.deferrals.incrementAndGet();
                    if (++entry.ladderReadyDeferrals > DEFER_CAP) {
                        remove(key);
                        this.droppedCount.incrementAndGet();
                    }
                }
                case FAILED -> {
                    remove(key);
                    this.droppedCount.incrementAndGet();
                    if (this.dead) return;
                }
            }
        }
    }

    private void remove(Long key) {
        synchronized (this.queueLock) {
            this.queue.remove(key);
        }
    }

    private enum Outcome { COMMITTED, DEFERRED, AWAITING_LOAD, AWAITING_LOAD_REQUESTED, FAILED }

    /**
     * One entry against its region — the decompiled {@code MapWriter.writeChunk}
     * region discipline: {@code writerThreadPauseSync} + {@code !isWritingPaused()}
     * (the save-race exclusion), the region monitor for load-state/visit/resting,
     * {@code setBeingWritten(true)} set and NEVER cleared by us (save-eligibility —
     * the save path owns the reset), the paced {@code requestLoad} dance for
     * unloaded regions, tile-chunk creation with its cache flags, then the pixel
     * commit.
     */
    private Outcome commitEntry(Object mp, Object saveLoad,
                                XaeroTileExtractor.PreparedTile tile,
                                boolean loadRequestedThisPump) {
        try {
            int chunkX = tile.chunkX();
            int chunkZ = tile.chunkZ();
            int tileChunkX = chunkX >> 2;
            int tileChunkZ = chunkZ >> 2;
            int localTcX = tileChunkX & 7;
            int localTcZ = tileChunkZ & 7;
            Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    tileChunkX >> 3, tileChunkZ >> 3, true);
            if (region == null) return Outcome.DEFERRED; // detection-completeness race
            Object writerPause = this.h.writerThreadPauseSync.invoke(region);
            synchronized (writerPause) {
                if ((boolean) this.h.regionIsWritingPaused.invoke(region)) return Outcome.DEFERRED;
                boolean proper;
                boolean resting;
                boolean createdTileChunk = false;
                Object tileChunk = null;
                synchronized (region) {
                    proper = (byte) this.h.getLoadState.invoke(region) == 2;
                    if (proper) this.h.registerVisit.invoke(region);
                    resting = (boolean) this.h.isResting.invoke(region);
                    if (resting) {
                        this.h.setBeingWritten.invoke(region, true);
                        if (proper) {
                            tileChunk = this.h.regionGetChunk.invoke(region, localTcX, localTcZ);
                            if (tileChunk == null) {
                                tileChunk = this.h.newMapTileChunk.invoke(region, tileChunkX, tileChunkZ);
                                this.h.regionSetChunk.invoke(region, localTcX, localTcZ, tileChunk);
                                this.h.tileChunkSetLoadState.invoke(tileChunk, (byte) 2);
                                this.h.setAllCachePrepared.invoke(region, false);
                                createdTileChunk = true;
                            }
                        }
                    }
                    if (!proper) {
                        // The mandatory load dance (fresh regions NEVER self-promote to
                        // loadState 2): setBeingWritten-BEFORE-request is load-bearing —
                        // it stops the load drain demoting an empty fresh region.
                        if (resting && !loadRequestedThisPump
                                && shouldAllowAnotherLoad(saveLoad)
                                && (boolean) this.h.canRequestReload.invoke(region)) {
                            this.h.requestLoad.invoke(saveLoad, region, "lss-xaero-bridge");
                            this.h.setNextToLoadByViewing.invoke(saveLoad, region);
                            this.loadRequests.incrementAndGet();
                            return Outcome.AWAITING_LOAD_REQUESTED;
                        }
                        return Outcome.AWAITING_LOAD;
                    }
                }
                if (!resting || tileChunk == null) return Outcome.DEFERRED;
                if ((int) this.h.tileChunkGetLoadState.invoke(tileChunk) != 2) return Outcome.DEFERRED;
                Object leafTexture = this.h.getLeafTexture.invoke(tileChunk);
                if ((boolean) this.h.shouldDownloadFromPBO.invoke(leafTexture)) return Outcome.DEFERRED;

                commitPixels(mp, region, tileChunk, createdTileChunk,
                        localTcX, localTcZ, tile);
                return Outcome.COMMITTED;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return Outcome.FAILED;
        }
    }

    private boolean shouldAllowAnotherLoad(Object saveLoad) throws Throwable {
        Object nextToLoad = this.h.getNextToLoadByViewing.invoke(saveLoad);
        return nextToLoad == null
                || (boolean) this.h.shouldAllowAnotherRegionToLoad.invoke(nextToLoad);
    }

    /** The decompiled per-tile commit sequence, verbatim order (plan §1). */
    private void commitPixels(Object mp, Object region, Object tileChunk,
                              boolean createdTileChunk, int localTcX, int localTcZ,
                              XaeroTileExtractor.PreparedTile tile) throws Throwable {
        int insideX = tile.chunkX() & 3;
        int insideZ = tile.chunkZ() & 3;
        Object mapTile = this.h.getTile.invoke(tileChunk, insideX, insideZ);
        if (mapTile == null) {
            Object pool = this.h.getTilePool.invoke(mp);
            String dimensionToken = (String) this.h.getCurrentDimension.invoke(mp);
            mapTile = this.h.poolGet.invoke(pool, dimensionToken, tile.chunkX(), tile.chunkZ());
            this.h.tileChunkSetChanged.invoke(tileChunk, true);
        }
        Object overlayManager = this.h.getOverlayManager.invoke(mp);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = x * 16 + z;
                Object block = this.h.newMapBlock.invoke();
                this.h.prepareForWriting.invoke(block, tile.worldBottomY());
                var runs = tile.overlays()[i];
                if (runs != null) {
                    for (var run : runs) {
                        Object overlay = this.h.newOverlay.invoke(run.state(), run.light(), run.glowing());
                        this.h.increaseOpacity.invoke(overlay, run.opacity());
                        Object original = this.h.getOriginal.invoke(overlayManager, overlay);
                        this.h.addOverlay.invoke(block, original);
                    }
                }
                this.h.blockWrite.invoke(block, tile.floorState()[i],
                        (int) tile.floorY()[i], (int) tile.topY()[i],
                        tile.biome()[i], tile.light()[i], tile.glowing()[i], false);
                this.h.setBlock.invoke(mapTile, x, z, block);
            }
        }
        this.h.setWorldInterpretationVersion.invoke(mapTile, 1);
        this.h.setWrittenCave.invoke(mapTile, SURFACE_LAYER,
                (int) this.h.getCaveModeDepthConfig.invoke(mp));
        this.h.tileChunkSetChanged.invoke(tileChunk, true);
        this.h.setTile.invoke(tileChunk, insideX, insideZ, mapTile,
                this.h.getBlockStateShortShapeCache.invoke(mp), mp);
        this.h.setWrittenOnce.invoke(mapTile, true);
        this.h.setLoaded.invoke(mapTile, true);
        if (createdTileChunk) {
            if ((boolean) this.h.includeInSave.invoke(tileChunk)) {
                this.h.setHasHadTerrain.invoke(tileChunk);
            }
            Object highlights = this.h.getMapRegionHighlightsPreparer.invoke(mp);
            this.h.highlightsPrepare.invoke(highlights, region, localTcX, localTcZ, false);
        }
        // No direct updateBuffers (GPU work stays in Xaero's preUpload sweep) — the
        // native right/bottom-right neighbor pattern: flag, then consume the change.
        this.h.setToUpdateBuffers.invoke(tileChunk, true);
        this.h.tileChunkSetChanged.invoke(tileChunk, false);
    }

    private void noteFailure(Throwable t) {
        long n = COMMIT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) {
            LSSLogger.warn("Xaero map bridge: commit failed (" + n
                    + " failure(s) since the last report)", t);
        }
        if (++this.consecutiveFailures >= THROW_LATCH) {
            this.dead = true;
            clearQueue();
            LSSLogger.error("Xaero map bridge: " + THROW_LATCH + " consecutive failures — "
                    + "disabling the bridge for this session (LODs are unaffected)", t);
        }
    }

    // ---- the reflective surface (plan §4; all members verified public) ----

    /**
     * Resolve-once handle set. All-or-nothing: any missing member throws and the
     * bridge stays off. Xaero-typed members resolve with exact types from the
     * resolved classes; the three {@code ClientLevel}-typed members
     * ({@code getWorld}, {@code mainWorld}, {@code ignoreWorld}) resolve by
     * name-scan (the {@code MoonriseReadCompat} shape-scan precedent) and are
     * handled as Objects behind {@link LevelOps}, because tests cannot construct
     * a {@code ClientLevel}.
     */
    static final class Handles {
        final MethodHandle getCurrentSession;
        final MethodHandle sessionIsUsable;
        final MethodHandle getMapProcessor;
        final MethodHandle renderThreadPauseSync;
        final MethodHandle mainStuffSync;
        final MethodHandle mainWorld;
        final MethodHandle isWritingPaused;
        final MethodHandle isWaitingForWorldUpdate;
        final MethodHandle isCurrentMapLocked;
        final MethodHandle isCurrentMultiworldWritable;
        final MethodHandle getCurrentWorldId;
        final MethodHandle getCurrentDimension;
        final MethodHandle getWorld;
        final MethodHandle ignoreWorld;
        final MethodHandle getMapWorld;
        final MethodHandle getMapSaveLoad;
        final MethodHandle getLeafMapRegion;
        final MethodHandle getTilePool;
        final MethodHandle getOverlayManager;
        final MethodHandle getBlockStateShortShapeCache;
        final MethodHandle getMapRegionHighlightsPreparer;
        final MethodHandle getCaveModeDepthConfig;
        final MethodHandle isCacheOnlyMode;
        final MethodHandle getCurrentDimensionId;
        final MethodHandle isRegionDetectionComplete;
        final MethodHandle requestLoad;
        final MethodHandle getNextToLoadByViewing;
        final MethodHandle setNextToLoadByViewing;
        final MethodHandle shouldAllowAnotherRegionToLoad;
        final MethodHandle writerThreadPauseSync;
        final MethodHandle regionIsWritingPaused;
        final MethodHandle getLoadState;
        final MethodHandle isResting;
        final MethodHandle registerVisit;
        final MethodHandle setBeingWritten;
        final MethodHandle canRequestReload;
        final MethodHandle setAllCachePrepared;
        final MethodHandle regionGetChunk;
        final MethodHandle regionSetChunk;
        final MethodHandle newMapTileChunk;
        final MethodHandle tileChunkGetLoadState;
        final MethodHandle tileChunkSetLoadState;
        final MethodHandle tileChunkSetChanged;
        final MethodHandle setToUpdateBuffers;
        final MethodHandle setHasHadTerrain;
        final MethodHandle includeInSave;
        final MethodHandle getLeafTexture;
        final MethodHandle shouldDownloadFromPBO;
        final MethodHandle getTile;
        final MethodHandle setTile;
        final MethodHandle poolGet;
        final MethodHandle setBlock;
        final MethodHandle setWorldInterpretationVersion;
        final MethodHandle setWrittenCave;
        final MethodHandle setWrittenOnce;
        final MethodHandle setLoaded;
        final MethodHandle newMapBlock;
        final MethodHandle prepareForWriting;
        final MethodHandle blockWrite;
        final MethodHandle addOverlay;
        final MethodHandle newOverlay;
        final MethodHandle increaseOpacity;
        final MethodHandle getOriginal;
        final MethodHandle highlightsPrepare;

        static Handles resolve(ClassResolver resolver) throws ClassNotFoundException,
                NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
            return new Handles(resolver, MethodHandles.lookup());
        }

        private Handles(ClassResolver resolver, MethodHandles.Lookup lookup)
                throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException,
                IllegalAccessException {
            Class<?> sessionClass = resolver.resolve("xaero.map.WorldMapSession");
            Class<?> processorClass = resolver.resolve("xaero.map.MapProcessor");
            Class<?> saveLoadClass = resolver.resolve("xaero.map.file.MapSaveLoad");
            Class<?> mapWorldClass = resolver.resolve("xaero.map.world.MapWorld");
            Class<?> regionClass = resolver.resolve("xaero.map.region.MapRegion");
            Class<?> leveledRegionClass = resolver.resolve("xaero.map.region.LeveledRegion");
            Class<?> tileChunkClass = resolver.resolve("xaero.map.region.MapTileChunk");
            Class<?> tileClass = resolver.resolve("xaero.map.region.MapTile");
            Class<?> blockClass = resolver.resolve("xaero.map.region.MapBlock");
            Class<?> overlayClass = resolver.resolve("xaero.map.region.Overlay");
            Class<?> overlayManagerClass = resolver.resolve("xaero.map.region.OverlayManager");
            Class<?> poolClass = resolver.resolve("xaero.map.pool.MapTilePool");
            Class<?> leafTextureClass = resolver.resolve("xaero.map.region.texture.LeafRegionTexture");
            Class<?> shapeCacheClass = resolver.resolve("xaero.map.cache.BlockStateShortShapeCache");
            Class<?> highlightsClass = resolver.resolve("xaero.map.highlight.MapRegionHighlightsPreparer");

            this.getCurrentSession = lookup.findStatic(sessionClass, "getCurrentSession",
                    MethodType.methodType(sessionClass)).asType(MethodType.methodType(Object.class));
            this.sessionIsUsable = virtual(lookup, sessionClass, "isUsable",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getMapProcessor = virtual(lookup, sessionClass, "getMapProcessor",
                    MethodType.methodType(processorClass), Object.class);

            this.renderThreadPauseSync = getter(lookup, processorClass, "renderThreadPauseSync");
            this.mainStuffSync = getter(lookup, processorClass, "mainStuffSync");
            this.mainWorld = getterByName(lookup, processorClass, "mainWorld");
            this.isWritingPaused = virtual(lookup, processorClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isWaitingForWorldUpdate = virtual(lookup, processorClass, "isWaitingForWorldUpdate",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isCurrentMapLocked = virtual(lookup, processorClass, "isCurrentMapLocked",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isCurrentMultiworldWritable = virtual(lookup, processorClass,
                    "isCurrentMultiworldWritable",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getCurrentWorldId = virtual(lookup, processorClass, "getCurrentWorldId",
                    MethodType.methodType(String.class), Object.class);
            this.getCurrentDimension = virtual(lookup, processorClass, "getCurrentDimension",
                    MethodType.methodType(String.class), String.class);
            this.getWorld = methodByName(lookup, processorClass, "getWorld", 0);
            this.ignoreWorld = methodByName(lookup, processorClass, "ignoreWorld", 1);
            this.getMapWorld = virtual(lookup, processorClass, "getMapWorld",
                    MethodType.methodType(mapWorldClass), Object.class);
            this.getMapSaveLoad = virtual(lookup, processorClass, "getMapSaveLoad",
                    MethodType.methodType(saveLoadClass), Object.class);
            this.getLeafMapRegion = lookup.findVirtual(processorClass, "getLeafMapRegion",
                            MethodType.methodType(regionClass, int.class, int.class, int.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, Object.class,
                            int.class, int.class, int.class, boolean.class));
            this.getTilePool = virtual(lookup, processorClass, "getTilePool",
                    MethodType.methodType(poolClass), Object.class);
            this.getOverlayManager = virtual(lookup, processorClass, "getOverlayManager",
                    MethodType.methodType(overlayManagerClass), Object.class);
            this.getBlockStateShortShapeCache = virtual(lookup, processorClass,
                    "getBlockStateShortShapeCache",
                    MethodType.methodType(shapeCacheClass), Object.class);
            this.getMapRegionHighlightsPreparer = virtual(lookup, processorClass,
                    "getMapRegionHighlightsPreparer",
                    MethodType.methodType(highlightsClass), Object.class);
            this.getCaveModeDepthConfig = virtual(lookup, processorClass, "getCaveModeDepthConfig",
                    MethodType.methodType(int.class), int.class);

            this.isCacheOnlyMode = virtual(lookup, mapWorldClass, "isCacheOnlyMode",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getCurrentDimensionId = virtual(lookup, mapWorldClass, "getCurrentDimensionId",
                    MethodType.methodType(ResourceKey.class), Object.class);

            this.isRegionDetectionComplete = virtual(lookup, saveLoadClass, "isRegionDetectionComplete",
                    MethodType.methodType(boolean.class), boolean.class);
            this.requestLoad = lookup.findVirtual(saveLoadClass, "requestLoad",
                            MethodType.methodType(void.class, regionClass, String.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, String.class));
            this.getNextToLoadByViewing = virtual(lookup, saveLoadClass, "getNextToLoadByViewing",
                    MethodType.methodType(leveledRegionClass), Object.class);
            this.setNextToLoadByViewing = lookup.findVirtual(saveLoadClass, "setNextToLoadByViewing",
                            MethodType.methodType(void.class, leveledRegionClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            this.shouldAllowAnotherRegionToLoad = virtual(lookup, leveledRegionClass,
                    "shouldAllowAnotherRegionToLoad",
                    MethodType.methodType(boolean.class), boolean.class);

            this.writerThreadPauseSync = getter(lookup, regionClass, "writerThreadPauseSync");
            this.regionIsWritingPaused = virtual(lookup, regionClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLoadState = virtual(lookup, regionClass, "getLoadState",
                    MethodType.methodType(byte.class), byte.class);
            this.isResting = virtual(lookup, regionClass, "isResting",
                    MethodType.methodType(boolean.class), boolean.class);
            this.registerVisit = virtual(lookup, regionClass, "registerVisit",
                    MethodType.methodType(void.class), void.class);
            this.setBeingWritten = lookup.findVirtual(regionClass, "setBeingWritten",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.canRequestReload = virtual(lookup, regionClass, "canRequestReload_unsynced",
                    MethodType.methodType(boolean.class), boolean.class);
            this.setAllCachePrepared = lookup.findVirtual(regionClass, "setAllCachePrepared",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.regionGetChunk = lookup.findVirtual(regionClass, "getChunk",
                            MethodType.methodType(tileChunkClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.regionSetChunk = lookup.findVirtual(regionClass, "setChunk",
                            MethodType.methodType(void.class, int.class, int.class, tileChunkClass))
                    .asType(MethodType.methodType(void.class, Object.class,
                            int.class, int.class, Object.class));

            this.newMapTileChunk = lookup.findConstructor(tileChunkClass,
                            MethodType.methodType(void.class, regionClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.tileChunkGetLoadState = virtual(lookup, tileChunkClass, "getLoadState",
                    MethodType.methodType(int.class), int.class);
            this.tileChunkSetLoadState = lookup.findVirtual(tileChunkClass, "setLoadState",
                            MethodType.methodType(void.class, byte.class))
                    .asType(MethodType.methodType(void.class, Object.class, byte.class));
            this.tileChunkSetChanged = lookup.findVirtual(tileChunkClass, "setChanged",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.setToUpdateBuffers = lookup.findVirtual(tileChunkClass, "setToUpdateBuffers",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.setHasHadTerrain = virtual(lookup, tileChunkClass, "setHasHadTerrain",
                    MethodType.methodType(void.class), void.class);
            this.includeInSave = virtual(lookup, tileChunkClass, "includeInSave",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLeafTexture = virtual(lookup, tileChunkClass, "getLeafTexture",
                    MethodType.methodType(leafTextureClass), Object.class);
            this.shouldDownloadFromPBO = virtual(lookup, leafTextureClass, "shouldDownloadFromPBO",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getTile = lookup.findVirtual(tileChunkClass, "getTile",
                            MethodType.methodType(tileClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.setTile = lookup.findVirtual(tileChunkClass, "setTile",
                            MethodType.methodType(void.class, int.class, int.class, tileClass,
                                    shapeCacheClass, processorClass))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class,
                            Object.class, Object.class, Object.class));

            this.poolGet = lookup.findVirtual(poolClass, "get",
                            MethodType.methodType(tileClass, String.class, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class,
                            String.class, int.class, int.class));
            this.setBlock = lookup.findVirtual(tileClass, "setBlock",
                            MethodType.methodType(void.class, int.class, int.class, blockClass))
                    .asType(MethodType.methodType(void.class, Object.class,
                            int.class, int.class, Object.class));
            this.setWorldInterpretationVersion = lookup.findVirtual(tileClass,
                            "setWorldInterpretationVersion",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.setWrittenCave = lookup.findVirtual(tileClass, "setWrittenCave",
                            MethodType.methodType(void.class, int.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class));
            this.setWrittenOnce = lookup.findVirtual(tileClass, "setWrittenOnce",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.setLoaded = lookup.findVirtual(tileClass, "setLoaded",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));

            this.newMapBlock = lookup.findConstructor(blockClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            this.prepareForWriting = lookup.findVirtual(blockClass, "prepareForWriting",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.blockWrite = lookup.findVirtual(blockClass, "write",
                            MethodType.methodType(void.class, BlockState.class, int.class, int.class,
                                    ResourceKey.class, byte.class, boolean.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, BlockState.class,
                            int.class, int.class, ResourceKey.class, byte.class,
                            boolean.class, boolean.class));
            this.addOverlay = lookup.findVirtual(blockClass, "addOverlay",
                            MethodType.methodType(void.class, overlayClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));

            this.newOverlay = lookup.findConstructor(overlayClass,
                            MethodType.methodType(void.class, BlockState.class, byte.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, BlockState.class,
                            byte.class, boolean.class));
            this.increaseOpacity = lookup.findVirtual(overlayClass, "increaseOpacity",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.getOriginal = lookup.findVirtual(overlayManagerClass, "getOriginal",
                            MethodType.methodType(overlayClass, overlayClass))
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));
            this.highlightsPrepare = lookup.findVirtual(highlightsClass, "prepare",
                            MethodType.methodType(void.class, regionClass, int.class, int.class,
                                    boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class,
                            int.class, int.class, boolean.class));
        }

        /** Exact-typed no-arg virtual, adapted to an Object receiver. */
        private static MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner,
                                            String name, MethodType type, Class<?> genericReturn)
                throws NoSuchMethodException, IllegalAccessException {
            return lookup.findVirtual(owner, name, type)
                    .asType(MethodType.methodType(genericReturn, Object.class));
        }

        private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name)
                throws NoSuchFieldException, IllegalAccessException {
            return lookup.findGetter(owner, name, Object.class)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        /** Field getter tolerant of the declared type (mainWorld is ClientLevel-typed). */
        private static MethodHandle getterByName(MethodHandles.Lookup lookup, Class<?> owner,
                                                 String name)
                throws NoSuchFieldException, IllegalAccessException {
            var field = owner.getField(name);
            return lookup.unreflectGetter(field)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        /**
         * Name+arity scan for the ClientLevel-typed boundary methods — the exact
         * parameter/return types stay whatever the class declares, so the stub
         * classes can declare them as Object (tests cannot construct a ClientLevel).
         */
        private static MethodHandle methodByName(MethodHandles.Lookup lookup, Class<?> owner,
                                                 String name, int paramCount)
                throws NoSuchMethodException, IllegalAccessException {
            Method found = null;
            for (Method m : owner.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException(owner.getName() + "." + name + "/" + paramCount);
            }
            var handle = lookup.unreflect(found);
            var generic = MethodType.genericMethodType(paramCount + 1);
            if (found.getReturnType() == boolean.class) {
                generic = generic.changeReturnType(boolean.class);
            }
            return handle.asType(generic);
        }
    }
}
