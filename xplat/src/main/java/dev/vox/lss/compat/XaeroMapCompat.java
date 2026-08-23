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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Xaero's World Map bridge (issue #223, docs/planning/xaero-map-bridge-plan.md):
 * writes LSS-delivered LOD columns into Xaero's World Map so the map records
 * terrain far beyond vanilla render distance. Pure reflection — zero compile-time
 * dependency, zero mixins (every member the bridge touches is public in Xaero WM
 * 1.45.0, verified 26.2 ≡ 1.21.1) — following the {@code VoxyCompat}/
 * {@code MoonriseReadCompat} interop discipline: any resolve failure disables the
 * bridge with one warn (diag shows {@code state=unavailable}); runtime failures
 * latch it dead for the session after {@value #THROW_LATCH} consecutive failures;
 * LOD delivery is NEVER affected — the consumer swallows every throwable
 * ({@code Error}s included: {@code LSSApi.dispatchColumn} converts ANY escape
 * into an ingest-failure report, and a map problem must not trigger re-serves).
 *
 * <p>Two-stage pipeline (plan §2.4): the registered {@link VoxelColumnConsumer}
 * extracts a {@link XaeroTileExtractor.PreparedTile} on the LSS decode thread and
 * offers it to a bounded (count AND bytes) latest-wins queue; {@link #pump()} —
 * the shared end-of-client-tick body, MAIN CLIENT THREAD (Xaero enforces it with
 * {@code isSameThread} throws) — re-runs the native writer's gate ladder
 * verbatim, then commits under Xaero's own locks in the decompiled
 * {@code MapWriter.writeChunk} sequence, including the mandatory
 * {@code requestLoad} dance for regions Xaero hasn't loaded (fresh regions never
 * self-promote) and the set-never-clear {@code setBeingWritten} lifecycle (the
 * save path owns the reset). The drain is REGION-BUCKETED with a MEMORYLESS
 * outstanding-load window (plan §14 as reshaped by the 3-Opus fold): entries
 * group by their Xaero map region (32×32 chunks — Xaero's consent granularity),
 * loaded regions commit in clusters, and load requests go to the pending regions
 * holding the most tiles, at most {@value #MAX_OUTSTANDING_LOADS} in flight —
 * where "in flight" is recognized fresh each pump from Xaero's OWN state
 * ({@code canRequestReload_unsynced()} is false exactly while a request is
 * queued/loading/refreshing), never from a bookkeeping set that could leak
 * against the loader's dead-end outcomes. Xaero's shared load-pacing surface is
 * deliberately untouched in BOTH directions: {@code shouldAllowAnotherRegionToLoad}
 * is never consulted (it synchronizes on its own — possibly BRANCH — region, a
 * lock-order inversion against Xaero's parent-then-leaf loader thread; review
 * MAJOR, a real client deadlock), and {@code setNextToLoadByViewing} is never
 * called (the loader itself never reads it — it is purely the pacing token of
 * the four native consumers, and pointing it at a far bridge region vetoed all
 * four; left alone, native requests front-insert AHEAD of our batch, the right
 * priority). GPU work stays Xaero's: the bridge only flags
 * {@code setToUpdateBuffers}, never calls {@code updateBuffers}.
 *
 * <p><b>Registration lifecycle</b> (review MAJOR): the consumer is what holds the
 * handshake's CAPABILITY_VOXEL_COLUMNS bit ({@code LSSApi.hasVoxelConsumers()}),
 * so an Xaero-only install (no Voxy) legitimately subscribes to LOD data — that
 * IS the feature. But deregistering MID-SESSION would put every arriving column
 * through the no-consumer ingest-failure path (up to 4 re-serves per position
 * before parking — a whole-disc churn for a map problem), so: registration is
 * add-only while a session may be live (init + pump), a disabled or dead bridge
 * becomes a silent no-op consumer (offers are dropped), and deregistration
 * happens ONLY at {@link #onDisconnect()} — which is also where the death latch
 * re-arms (session-scoped: one bad session must not disable the feature for the
 * whole JVM; genuine Xaero drift re-latches within {@value #THROW_LATCH}
 * commits next session).
 */
final class XaeroMapCompat {

    static final int MAX_QUEUE = 8192;
    /** Byte gauge companion to the count cap (the ClientColumnProcessor discipline —
     *  a count cap alone admits ~0.5 GB of max-overlay tiles at ~68 KB each; plain
     *  tiles are ~4.7 KB but ocean tiles carry per-pixel overlay runs). Estimated,
     *  not exact. */
    static final long MAX_QUEUE_BYTES = 48L * 1024 * 1024;
    /** Safety ceiling only — the nanos budget below is the binding constraint
     *  (review MAJOR: 8 committed only 160 tiles/s against 300-1000 delivered
     *  columns/s, making every backfill drop most of the map). */
    static final int MAX_COMMITS_PER_PUMP = 64;
    static final long PUMP_NANOS_BUDGET = 2_000_000L;
    /** Ladder-ready deferrals (busy region, PBO download) before an entry drops. */
    static final int DEFER_CAP = 200;
    /** Our in-flight region-load window — the honest generalization of Xaero's own
     *  1-in-flight gauge (plan §14): the loader drains unlimited CHEAP (virgin)
     *  loads per cycle but only one expensive file load (~10/s), so a small fixed
     *  window self-clocks the request rate to the real drain rate. In-flight is
     *  derived fresh each pump from {@code canRequestReload_unsynced()} — see
     *  {@link #grantLoads}. Budget-truncated pumps may under-count in-flight
     *  regions (unprobed buckets are unknown), transiently over-granting by at
     *  most one window per pump; requests are idempotent (an already-queued
     *  region answers not-requestable), so the excess is bounded and harmless. */
    static final int MAX_OUTSTANDING_LOADS = 8;
    /** Consecutive failures (commit-side or extraction-side) before the bridge
     *  latches dead for the SESSION (re-armed at disconnect). */
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
    /** Xaero present but its internal surface unrecognized — drives the
     *  {@code state=unavailable} diag line (without it a drifted Xaero would be
     *  indistinguishable from "not installed", hiding the plan's top risk). */
    private static volatile boolean resolveFailed;

    /** Client init, Xaero present: resolve + register the consumer (if enabled). */
    static boolean init() {
        return initWith(Class::forName);
    }

    /** The init body with an injectable resolver (the resolve-failure path's test seam). */
    static boolean initWith(ClassResolver resolver) {
        try {
            var h = Handles.resolve(resolver);
            var bridge = new XaeroMapCompat(h, PRODUCTION_LEVEL_OPS,
                    () -> LSSClientConfig.CONFIG.enableXaeroMapBridge,
                    LSSApi::isServerEnabled,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer);
            bridge.maybeRegister();
            instance = bridge;
            LSSLogger.info(LSSClientConfig.CONFIG.enableXaeroMapBridge
                    ? "Xaero's World Map detected — LOD map bridge active"
                    : "Xaero's World Map detected — LOD map bridge ready"
                            + " (disabled by enableXaeroMapBridge)");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                 | IllegalAccessException e) {
            resolveFailed = true;
            LSSLogger.warn("Xaero map bridge: this Xaero's World Map version has a different"
                    + " internal surface — bridge disabled (" + e + ")");
            return false;
        } catch (Throwable e) {
            resolveFailed = true;
            LSSLogger.error("Failed to initialize the Xaero map bridge", e);
            return false;
        }
    }

    /** End-of-client-tick body (main client thread). */
    static void clientTick() {
        var bridge = instance;
        if (bridge != null) bridge.pump();
    }

    /** Disconnect body — session teardown (queue, latches, registration). */
    static void onDisconnect() {
        var bridge = instance;
        if (bridge != null) bridge.onSessionEnd();
    }

    /** The conditional {@code /lss diag} line, or null when Xaero was never detected. */
    static String diagLine() {
        var bridge = instance;
        if (bridge != null) return bridge.describe();
        return resolveFailed
                ? "XaeroMap: state=unavailable (unrecognized Xaero internals — bridge off)"
                : null;
    }

    /** Test seam: forget the static facade state. */
    static void resetFacadeForTest() {
        instance = null;
        resolveFailed = false;
    }

    // ---- instance ----

    private final Handles h;
    private final LevelOps levelOps;
    private final BooleanSupplier enabled;
    /** An LSS session is live — offers outside one are dropped (closes the
     *  disconnect-drain race that could carry one stale tile into the NEXT
     *  server's — or a singleplayer world's — persistent map). */
    private final BooleanSupplier sessionActive;
    private final java.util.function.Consumer<VoxelColumnConsumer> registrar;
    private final java.util.function.Consumer<VoxelColumnConsumer> deregistrar;
    private final VoxelColumnConsumer consumer;
    /** Whether the consumer is currently registered with LSSApi. Main thread only. */
    private boolean registered;

    private final Object queueLock = new Object();
    /** Packed chunk pos → entry; insertion-ordered, latest tile wins in place. */
    private final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();
    private long queuedBytes; // under queueLock

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skippedNative = new AtomicLong();
    private final AtomicLong deferEvents = new AtomicLong();
    private final AtomicLong droppedOverflow = new AtomicLong();
    private final AtomicLong droppedStale = new AtomicLong();
    private final AtomicLong droppedExpired = new AtomicLong();
    private final AtomicLong commitFailures = new AtomicLong();
    private final AtomicLong loadRequests = new AtomicLong();
    private volatile boolean dead;
    private int consecutiveFailures; // main thread only
    /** Decode-thread twin of the commit-side latch: a permanently-throwing
     *  extractor must not burn CPU + hold the capability subscription forever. */
    private final AtomicInteger consecutiveExtractFailures = new AtomicInteger();
    /** The per-pump time budget — a field so tests can neutralize MethodHandle warmup. */
    long pumpNanosBudget = PUMP_NANOS_BUDGET;
    /** Rotating drain start (the IncomingRequestRouter M4 precedent): without it a
     *  permanently-deferring queue prefix starves committable entries forever. */
    private int drainRotation; // main thread only
    /** Regions with queued tiles awaiting their Xaero load, as PROBED by the last
     *  pump — a diag gauge, and a lower bound under budget truncation (buckets the
     *  commit loop never reached are unknown). */
    private volatile int regionsWaiting;

    private static final class Entry {
        volatile XaeroTileExtractor.PreparedTile tile; // replaced under queueLock (latest wins)
        final Object dimension;
        int bytes; // under queueLock
        /** Pump-side (++) with a decode-side reset on tile replace — the race is
         *  benign (one deferral tick lost or kept; the cap is approximate). */
        int ladderReadyDeferrals;

        Entry(Object dimension, XaeroTileExtractor.PreparedTile tile, int bytes) {
            this.dimension = dimension;
            this.tile = tile;
            this.bytes = bytes;
        }
    }

    XaeroMapCompat(Handles h, LevelOps levelOps, BooleanSupplier enabled,
                   BooleanSupplier sessionActive,
                   java.util.function.Consumer<VoxelColumnConsumer> registrar,
                   java.util.function.Consumer<VoxelColumnConsumer> deregistrar) {
        this.h = h;
        this.levelOps = levelOps;
        this.enabled = enabled;
        this.sessionActive = sessionActive;
        this.registrar = registrar;
        this.deregistrar = deregistrar;
        this.consumer = buildConsumer();
    }

    /**
     * ADD-only registration reconcile (init + every pump): a mid-session enable
     * starts feeding the map (when a stream exists — an Xaero-only install that
     * joined disabled has no capability bit until rejoin, which the tooltip's
     * wording tolerates). Deregistration is deliberately NOT here — see the class
     * javadoc's registration-lifecycle rule and {@link #onSessionEnd()}.
     */
    void maybeRegister() {
        if (!this.dead && this.enabled.getAsBoolean() && !this.registered) {
            this.registrar.accept(this.consumer);
            this.registered = true;
        }
    }

    /**
     * Session teardown (the loaders' disconnect events): drop the session's queue,
     * re-arm the death latches (session-scoped — one bad session must not disable
     * the feature until restart), and settle registration for the NEXT handshake
     * (a disabled bridge releases the capability bit here, never mid-session).
     */
    void onSessionEnd() {
        clearQueue();
        this.regionsWaiting = 0;
        this.dead = false;
        this.consecutiveFailures = 0;
        this.consecutiveExtractFailures.set(0);
        if (this.registered && !this.enabled.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        } else {
            maybeRegister();
        }
    }

    /** The registered consumer — a thin shell over {@link #offerColumn}. */
    private VoxelColumnConsumer buildConsumer() {
        return (level, dimension, chunkX, chunkZ, columnData) -> {
            try {
                offerColumn(dimension, chunkX, chunkZ,
                        level.getMinY(), level.getMaxY() + 1, columnData);
                this.consecutiveExtractFailures.set(0);
            } catch (Throwable t) {
                // Swallow EVERYTHING, Errors included: LSSApi.dispatchColumn converts
                // any escape into reportIngestFailure — a re-serve loop for a map
                // problem (review MAJOR). A VM-fatal Error will resurface on a frame
                // that can afford it; here it would cost LOD correctness.
                long n = EXTRACT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.warn("Xaero map bridge: tile extraction failed (" + n
                            + " failure(s) since the last report)", t);
                }
                if (this.consecutiveExtractFailures.incrementAndGet() >= THROW_LATCH
                        && !this.dead) {
                    this.dead = true;
                    clearQueue();
                    LSSLogger.error("Xaero map bridge: " + THROW_LATCH + " consecutive"
                            + " extraction failures — disabling the bridge for this session"
                            + " (LODs are unaffected)", t);
                }
            }
        };
    }

    /** Decode-thread entry: extract + enqueue (latest-wins, bounded, oldest drops). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData) {
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            return;
        }
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        synchronized (this.queueLock) {
            // Don't pay the 256-pixel extraction for a tile the full queue would
            // evict on arrival (sustained-overflow CPU on the LOD decode thread).
            if (this.queue.size() >= MAX_QUEUE && !this.queue.containsKey(key)) {
                this.droppedOverflow.incrementAndGet();
                return;
            }
        }
        var tile = XaeroTileExtractor.extract(chunkX, chunkZ, worldBottomY, worldTopY, columnData);
        offerPrepared(dimension, tile);
    }

    /** Approximate retained bytes for the byte gauge (shallow arrays + overlay runs). */
    static int approxBytes(XaeroTileExtractor.PreparedTile tile) {
        int bytes = 4800;
        for (var runs : tile.overlays()) {
            if (runs != null) bytes += 24 + runs.length * 32;
        }
        return bytes;
    }

    /** Enqueue seam (tests build {@link XaeroTileExtractor.PreparedTile}s directly). */
    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile) {
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        int bytes = approxBytes(tile);
        synchronized (this.queueLock) {
            var existing = this.queue.get(key);
            if (existing != null) {
                if (existing.dimension == dimension) {
                    this.queuedBytes += bytes - existing.bytes;
                    existing.tile = tile;
                    existing.bytes = bytes;
                    existing.ladderReadyDeferrals = 0; // fresh serve = fresh patience
                    return;
                }
                // Stale-dimension entry: the new serve replaces it (fresh Entry, so
                // an in-flight pump pass's compare-and-remove cannot delete it).
                this.queuedBytes -= existing.bytes;
                this.queue.remove(key);
            }
            while (!this.queue.isEmpty()
                    && (this.queue.size() >= MAX_QUEUE
                        || this.queuedBytes + bytes > MAX_QUEUE_BYTES)) {
                var it = this.queue.entrySet().iterator();
                this.queuedBytes -= it.next().getValue().bytes;
                it.remove();
                this.droppedOverflow.incrementAndGet();
            }
            this.queuedBytes += bytes;
            this.queue.put(key, new Entry(dimension, tile, bytes));
        }
    }

    void clearQueue() {
        synchronized (this.queueLock) {
            this.queue.clear();
            this.queuedBytes = 0;
        }
    }

    /**
     * Remove only if the entry AND its tile are still the ones this pump pass
     * examined — a plain remove would silently delete a fresher tile (or a
     * replacement Entry) the decode thread installed mid-commit (review MINOR:
     * the latest-wins guarantee must survive the commit window). Returns whether
     * the removal actually happened, so drop counters count DROPS, not attempts
     * (3-Opus fold: a survived entry must not re-count every pump).
     */
    private boolean removeIfCurrent(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {
        synchronized (this.queueLock) {
            var current = this.queue.get(key);
            if (current == entry && entry.tile == tile) {
                this.queuedBytes -= entry.bytes;
                this.queue.remove(key);
                return true;
            }
            return false;
        }
    }

    int queuedForTest() {
        synchronized (this.queueLock) {
            return this.queue.size();
        }
    }

    boolean hasQueuedForTest(int chunkX, int chunkZ) {
        synchronized (this.queueLock) {
            return this.queue.containsKey(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }
    }

    long queuedBytesForTest() {
        synchronized (this.queueLock) {
            return this.queuedBytes;
        }
    }

    boolean deadForTest() {
        return this.dead;
    }

    int regionsWaitingForTest() {
        return this.regionsWaiting;
    }

    boolean registeredForTest() {
        return this.registered;
    }

    long counterForTest(String name) {
        return switch (name) {
            case "written" -> this.written.get();
            case "skipped_native" -> this.skippedNative.get();
            case "defer_events" -> this.deferEvents.get();
            case "dropped_overflow" -> this.droppedOverflow.get();
            case "dropped_stale" -> this.droppedStale.get();
            case "dropped_expired" -> this.droppedExpired.get();
            case "commit_failures" -> this.commitFailures.get();
            case "load_requests" -> this.loadRequests.get();
            default -> throw new IllegalArgumentException(name);
        };
    }

    String describe() {
        String state = this.dead ? "dead" : this.enabled.getAsBoolean() ? "active" : "disabled";
        long dropped = this.droppedOverflow.get() + this.droppedStale.get()
                + this.droppedExpired.get();
        return "XaeroMap: state=" + state + ", queued=" + queuedForTest()
                + ", written=" + this.written.get()
                + ", skipped_native=" + this.skippedNative.get()
                + ", defer_events=" + this.deferEvents.get()
                + ", dropped=" + dropped
                + ", commit_failures=" + this.commitFailures.get()
                + ", load_requests=" + this.loadRequests.get()
                + ", regions_waiting=" + this.regionsWaiting;
    }

    // ---- the pump (main client thread) ----

    void pump() {
        maybeRegister();
        if (this.dead) return;
        if (!this.enabled.getAsBoolean()) {
            clearQueue(); // the live toggle: flipping off drops the backlog immediately
            return;
        }
        synchronized (this.queueLock) {
            if (this.queue.isEmpty()) {
                this.regionsWaiting = 0;
                return;
            }
        }
        try {
            // No blanket failure-count reset here: commit failures are contained per
            // entry inside the drain, so the ladder returning normally proves nothing —
            // only a successful COMMIT resets the death-latch count.
            pumpLadder();
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
    private void pumpLadder() throws Throwable {
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
            drainEntries(mp, saveLoad, world, dimensionId);
        }
    }

    /** One queue entry paired with its key for the bucketed drain. */
    private record Pending(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {}

    /** A region probed this pump whose bucket is awaiting its Xaero load — the
     *  verdict is Xaero's own state, read inside the probe's region monitor. */
    private record WaitingRegion(long regionKey, int tiles, Outcome verdict) {}

    /**
     * The bucketed drain (the region-throughput round, plan §14 as reshaped by the
     * 3-Opus fold). ONE queue-lock snapshot, then a pure-arithmetic grouping by
     * Xaero MAP REGION (32×32 chunks — Xaero's consent granularity: no tile may
     * commit until its region's save file is loaded; the old per-entry re-fetch
     * paid a lock acquisition per queued entry and ran the chunk-lookup filters
     * OUTSIDE the nanos budget — the live-lock MAJOR). Then: COMMIT phase over
     * region buckets (rotated — the IncomingRequestRouter M4 precedent), the
     * stale-dimension/natively-writable filters running per entry INSIDE the
     * budgeted loop, probing each region ONCE per pump and short-circuiting its
     * whole bucket on a region-scoped not-ready outcome (at large radius a spiral
     * ring crosses ~r/4 regions, and per-entry probing burned the budget on
     * thousands of identical awaiting-load answers); then the GRANT phase
     * ({@link #grantLoads}). The budget check is skipped until the pump has made
     * at least ONE unit of progress (a drop or a commit attempt), so even a
     * degenerate budget drains the queue over pumps instead of live-locking.
     */
    private void drainEntries(Object mp, Object saveLoad,
                              Object world, Object dimensionId) throws Throwable {
        long start = System.nanoTime();

        List<Pending> snapshot;
        synchronized (this.queueLock) {
            snapshot = new ArrayList<>(this.queue.size());
            for (var e : this.queue.entrySet()) {
                snapshot.add(new Pending(e.getKey(), e.getValue(), e.getValue().tile));
            }
        }
        var buckets = new LinkedHashMap<Long, List<Pending>>(); // keeps spiral locality
        for (var pending : snapshot) {
            long regionKey = (((long) (pending.tile().chunkX() >> 5)) << 32)
                    | ((pending.tile().chunkZ() >> 5) & 0xFFFFFFFFL);
            buckets.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(pending);
        }

        var bucketKeys = new ArrayList<>(buckets.keySet());
        var waiting = new ArrayList<WaitingRegion>();
        int commits = 0;
        boolean progressed = false;
        int size = bucketKeys.size();
        int startIndex = size == 0 ? 0 : Math.floorMod(this.drainRotation++, size);
        bucketLoop:
        for (int n = 0; n < size; n++) {
            Long regionKey = bucketKeys.get((startIndex + n) % size);
            var bucket = buckets.get(regionKey);
            for (var pending : bucket) {
                if (progressed && (commits >= MAX_COMMITS_PER_PUMP
                        || System.nanoTime() - start > this.pumpNanosBudget)) {
                    break bucketLoop;
                }
                if (pending.entry().dimension != dimensionId) {
                    // Can never become valid — the pump-side stale-dimension drop (§2.5).
                    if (removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        this.droppedStale.incrementAndGet();
                    }
                    progressed = true;
                    continue;
                }
                if (nativelyWritable(world, pending.tile().chunkX(), pending.tile().chunkZ())) {
                    // The native writer owns these chunks and rewrites them on its
                    // clean-flag anyway — never fight it (plan §2.6).
                    if (removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                        this.skippedNative.incrementAndGet();
                    }
                    progressed = true;
                    continue;
                }
                progressed = true;
                var outcome = commitEntry(mp, pending.tile());
                switch (outcome) {
                    case COMMITTED -> {
                        removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        this.written.incrementAndGet();
                        this.consecutiveFailures = 0;
                        commits++;
                    }
                    case DEFERRED_TILE -> {
                        // TILE-CHUNK-scoped busy (its 4×4 loadState / PBO download):
                        // the region is fine, so siblings keep committing and only
                        // THIS entry's patience burns (3-Opus fold MAJOR: the
                        // region-wide burn expired whole buckets over one busy
                        // tile chunk).
                        this.deferEvents.incrementAndGet();
                        if (++pending.entry().ladderReadyDeferrals > DEFER_CAP
                                && removeIfCurrent(pending.key(), pending.entry(), pending.tile())) {
                            this.droppedExpired.incrementAndGet();
                        }
                    }
                    case DEFERRED -> {
                        // REGION-scoped busy (being saved / not resting): the whole
                        // bucket shares the state — burn each entry's counter once
                        // (cap semantics preserved) and move on.
                        this.deferEvents.incrementAndGet();
                        for (var p : bucket) {
                            if (++p.entry().ladderReadyDeferrals > DEFER_CAP
                                    && removeIfCurrent(p.key(), p.entry(), p.tile())) {
                                this.droppedExpired.incrementAndGet();
                            }
                        }
                        continue bucketLoop;
                    }
                    case AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT -> {
                        // The whole bucket waits on this region's load: one defer
                        // event per BUCKET per pump, entries stay queued (awaiting-
                        // load is exempt from the deferral cap), and the verdict
                        // feeds the grant phase's memoryless window.
                        this.deferEvents.incrementAndGet();
                        waiting.add(new WaitingRegion(regionKey, bucket.size(), outcome));
                        continue bucketLoop;
                    }
                    case FAILED -> {
                        // Possibly entry-specific (a hostile state) — drop it and keep
                        // trying the bucket's siblings unless the latch fired.
                        removeIfCurrent(pending.key(), pending.entry(), pending.tile());
                        if (this.dead) return;
                    }
                }
            }
        }
        this.regionsWaiting = waiting.size();
        grantLoads(mp, saveLoad, waiting);
    }

    /**
     * The GRANT phase: request Xaero loads for waiting regions, at most
     * {@value #MAX_OUTSTANDING_LOADS} in flight. The window is MEMORYLESS —
     * in-flight regions are recognized each pump from Xaero's own
     * {@code canRequestReload_unsynced()} (false exactly while a request is
     * queued/loading/refreshing), read under the region monitor by the commit
     * probe. No bookkeeping set to leak (3-Opus fold MAJORs): the loader's
     * dead-end load outcomes all come back requestable by themselves — a failed
     * or empty load ends in {@code removeMapRegion}, and the next probe's
     * {@code getLeafMapRegion(create=true)} hands back a FRESH loadState-0
     * region; a cache-only load parks at loadState 3 and is revived via Xaero's
     * own 3→4 transition (the {@code clearRegion} idiom) in
     * {@link #requestRegionLoad}. Requests go to the largest pending clusters,
     * ISSUED smallest-first: the loader drains {@code toLoad.get(0)} against our
     * priority front-inserts (LIFO), so the largest cluster must be the FINAL
     * front-insert to drain first. Cost is bounded: ≤{@value #MAX_OUTSTANDING_LOADS}
     * requestLoad calls per pump (each runs Xaero's main-thread highlight
     * prepare), and in steady state the window self-clocks near the loader's
     * real expensive-load drain rate (~10/s at the 100 ms MapRunner cadence).
     */
    private void grantLoads(Object mp, Object saveLoad, List<WaitingRegion> waiting) {
        int inFlight = 0;
        var candidates = new ArrayList<WaitingRegion>();
        for (var w : waiting) {
            if (w.verdict() == Outcome.AWAITING_IN_FLIGHT) inFlight++;
            else candidates.add(w);
        }
        int budget = MAX_OUTSTANDING_LOADS - inFlight;
        if (budget <= 0 || candidates.isEmpty()) return;
        candidates.sort((a, b) -> Integer.compare(b.tiles(), a.tiles()));
        var chosen = candidates.subList(0, Math.min(budget, candidates.size()));
        for (int i = chosen.size() - 1; i >= 0; i--) {
            if (this.dead) return;
            if (requestRegionLoad(mp, saveLoad, chosen.get(i).regionKey())) {
                this.loadRequests.incrementAndGet();
            }
        }
    }

    /**
     * The native writer's load-request dance for one region (MapWriter:340-348 —
     * region monitor only): setBeingWritten-BEFORE-request is load-bearing (it stops
     * the load drain demoting an empty fresh region), and requestLoad front-inserts
     * with priority (verified: the 2-arg overload passes prioritize=true, which also
     * bypasses the loader's mid-drain add guard). A cache-parked region (loadState 3
     * — the loader's cache-only dead end, where isResting AND canRequestReload are
     * both false forever) is first revived via Xaero's own 3→4 transition (the
     * {@code clearRegion} idiom, the SP-bridge-proven revival), and RESTORED to 3 if
     * the guards still refuse — pending native work owns it. {@code
     * setNextToLoadByViewing} is deliberately NOT called (3-Opus fold): the loader
     * never reads it — it is purely the pacing token of Xaero's four native
     * consumers (writer/minimap/GUI/reloader), and pointing it at a far bridge
     * region vetoed all four for multi-second stretches after each granted region's
     * save; left alone, the native writer's own requests front-insert AHEAD of our
     * batch, which is the right priority. NB: requestLoad is main-thread-only
     * despite its queue-add look — its tail runs a highlight prepare that
     * hard-throws off Minecraft.isSameThread().
     */
    private boolean requestRegionLoad(Object mp, Object saveLoad, long regionKey) {
        try {
            int regionX = (int) (regionKey >> 32);
            int regionZ = (int) regionKey;
            Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    regionX, regionZ, true);
            if (region == null) return false;
            synchronized (region) {
                byte loadState = (byte) this.h.getLoadState.invoke(region);
                if (loadState == 2) return false;
                boolean revived = false;
                if (loadState == 3) {
                    this.h.setLoadState.invoke(region, (byte) 4);
                    revived = true;
                }
                if (!(boolean) this.h.isResting.invoke(region)
                        || !(boolean) this.h.canRequestReload.invoke(region)) {
                    if (revived) this.h.setLoadState.invoke(region, (byte) 3);
                    return false;
                }
                this.h.setBeingWritten.invoke(region, true);
                this.h.requestLoad.invoke(saveLoad, region, "lss-xaero-bridge");
                return true;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return false;
        }
    }

    /**
     * Will the NATIVE writer actually write this chunk? Its edge rule (decompiled
     * {@code writeChunk}) requires the chunk AND all 8 neighbors loaded — so the
     * outermost ring of loaded vanilla chunks is never natively written. Skipping
     * on "loaded" alone left that ring written by NOBODY: a 1-chunk black circle
     * at the vanilla/LOD boundary around every join point (field-tested 2026-08-23
     * — the columns had been served during the join window, and the broad skip
     * threw the tiles away). A loaded-but-edge chunk is bridge-written instead;
     * the native writer reclaims it on its clean-flag once fully surrounded.
     */
    private boolean nativelyWritable(Object world, int chunkX, int chunkZ) {
        if (!this.levelOps.isChunkLoaded(world, chunkX, chunkZ)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0)
                        && !this.levelOps.isChunkLoaded(world, chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Region-scoped outcomes short-circuit the whole bucket; DEFERRED_TILE is
     *  tile-chunk-scoped (siblings keep committing); the three AWAITING flavors
     *  carry the region's requestability — Xaero's own state, read inside the
     *  probe's region monitor — into the grant phase's memoryless window. */
    private enum Outcome {
        COMMITTED, DEFERRED, DEFERRED_TILE,
        AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT,
        FAILED
    }

    /**
     * One entry against its region — the decompiled {@code MapWriter.writeChunk}
     * region discipline: {@code writerThreadPauseSync} + {@code !isWritingPaused()}
     * (the save-race exclusion), the region monitor for load-state/visit/resting,
     * {@code setBeingWritten(true)} set and NEVER cleared by us (save-eligibility —
     * the save path owns the reset), tile-chunk creation with its cache flags, then
     * the pixel commit. Load REQUESTS live in the grant phase
     * ({@link #requestRegionLoad}) — an unloaded region answers an AWAITING flavor
     * here, classified from {@code canRequestReload_unsynced()} + loadState in the
     * same monitor read.
     */
    private Outcome commitEntry(Object mp, XaeroTileExtractor.PreparedTile tile) {
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
                boolean resting;
                boolean createdTileChunk = false;
                Object tileChunk = null;
                synchronized (region) {
                    byte loadState = (byte) this.h.getLoadState.invoke(region);
                    boolean proper = loadState == 2;
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
                        // Fresh regions NEVER self-promote to loadState 2 — the grant
                        // phase requests the load; this entry (and its whole bucket)
                        // just waits, classified from Xaero's own state right here in
                        // the region monitor (the memoryless window's input):
                        // requestable now, cache-parked (needs the 3→4 revival), or
                        // genuinely in flight (queued/loading/refreshing — occupies a
                        // window slot).
                        if ((boolean) this.h.canRequestReload.invoke(region)) {
                            return Outcome.AWAITING_REQUESTABLE;
                        }
                        return loadState == 3 ? Outcome.AWAITING_PARKED
                                : Outcome.AWAITING_IN_FLIGHT;
                    }
                }
                if (!resting || tileChunk == null) return Outcome.DEFERRED;
                if ((int) this.h.tileChunkGetLoadState.invoke(tileChunk) != 2) {
                    return Outcome.DEFERRED_TILE;
                }
                Object leafTexture = this.h.getLeafTexture.invoke(tileChunk);
                if ((boolean) this.h.shouldDownloadFromPBO.invoke(leafTexture)) {
                    return Outcome.DEFERRED_TILE;
                }

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
        this.commitFailures.incrementAndGet();
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
        final MethodHandle writerThreadPauseSync;
        final MethodHandle regionIsWritingPaused;
        final MethodHandle getLoadState;
        final MethodHandle setLoadState;
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

            this.writerThreadPauseSync = getter(lookup, regionClass, "writerThreadPauseSync");
            this.regionIsWritingPaused = virtual(lookup, regionClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLoadState = virtual(lookup, regionClass, "getLoadState",
                    MethodType.methodType(byte.class), byte.class);
            this.setLoadState = lookup.findVirtual(regionClass, "setLoadState",
                            MethodType.methodType(void.class, byte.class))
                    .asType(MethodType.methodType(void.class, Object.class, byte.class));
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
                if (m.getName().equals(name) && m.getParameterCount() == paramCount
                        && !m.isSynthetic() && !m.isBridge()) {
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
