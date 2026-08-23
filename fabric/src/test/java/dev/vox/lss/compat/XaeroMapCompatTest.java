package dev.vox.lss.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Xaero map bridge against real-package-name stubs (xaero-map-bridge-plan.md §3;
 * the {@code MoonriseReadCompatTest} stub discipline — the stubs under
 * {@code fabric/src/test/java/xaero/map} mirror exactly the public surface
 * {@code XaeroMapCompat.Handles} resolves, and their state accessors ENFORCE the
 * native monitor discipline via holdsLock checks, so dropping a synchronized block
 * fails here rather than racing a live client). Pins:
 * <ul>
 *   <li>resolve is all-or-nothing and fail-soft; a resolve failure renders
 *       {@code state=unavailable} in diag (a drifted Xaero must be visible);</li>
 *   <li>the pump mirrors the native writer's gate ladder — every not-ready gate
 *       DEFERS (entries retained), a stale-dimension entry DROPS, a loaded chunk
 *       SKIPS; the region-level save-race gate defers too;</li>
 *   <li>the decompiled commit sequence order, incl. setChanged(true) before
 *       setTile, worldInterpretationVersion before setTile, the flag-then-consume
 *       buffers pattern (no updateBuffers handle exists at all), the faithful
 *       prepare→overlays→write per-pixel order (the stub's prepareForWriting
 *       clears overlays like the real one, so a wrong order wipes them), and
 *       {@code setBeingWritten} set-and-NEVER-cleared;</li>
 *   <li>the mandatory paced requestLoad dance: setBeingWritten BEFORE requestLoad,
 *       the pacing gauge consulted BEFORE any region monitor (lock-order — the
 *       deadlock pin), one request per pump, awaiting-load exempt from the
 *       deferral cap;</li>
 *   <li>queue policy: latest-wins with DISTINCT tiles, oldest-first eviction,
 *       count AND byte bounds, cross-dimension replacement, config-off clear,
 *       no-session drop;</li>
 *   <li>the death latches (commit-side and extraction-side): 5 CONSECUTIVE
 *       failures latch — across pumps, not reset by a clean ladder pass — a
 *       success resets, and {@code onSessionEnd} re-arms (session-scoped);</li>
 *   <li>registration lifecycle: add-only while live, deregistration only at
 *       session end (mid-session deregistration would put every column through
 *       the no-consumer ingest-failure re-serve path);</li>
 *   <li>the consumer contract: a throwing extraction NEVER escapes and the
 *       default {@code pendingIngestBacklog} is not overridden.</li>
 * </ul>
 */
class XaeroMapCompatTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_nether"));

    private MapProcessor processor;
    private final Object worldToken = new Object();
    private final Set<Long> loadedChunks = new HashSet<>();
    private boolean enabled = true;
    private boolean sessionActive = true;
    private final List<dev.vox.lss.api.VoxelColumnConsumer> registered = new ArrayList<>();
    private XaeroMapCompat bridge;

    private final XaeroMapCompat.LevelOps fakeLevelOps = new XaeroMapCompat.LevelOps() {
        @Override
        public Object dimension(Object world) {
            return OVERWORLD;
        }

        @Override
        public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
            return loadedChunks.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }
    };

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        this.processor = new MapProcessor();
        this.processor.world = this.worldToken;
        this.processor.mainWorld = this.worldToken;
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        var session = new WorldMapSession();
        session.processor = this.processor;
        WorldMapSession.current = session;
        this.loadedChunks.clear();
        this.enabled = true;
        this.sessionActive = true;
        this.registered.clear();
        this.bridge = new XaeroMapCompat(
                XaeroMapCompat.Handles.resolve(Class::forName),
                this.fakeLevelOps,
                () -> this.enabled,
                () -> this.sessionActive,
                this.registered::add,
                this.registered::remove);
        this.bridge.pumpNanosBudget = Long.MAX_VALUE; // neutralize MethodHandle warmup
        this.bridge.maybeRegister();
    }

    @AfterEach
    void tearDownStubStatics() {
        WorldMapSession.current = null;
        XaeroStubEvents.clear();
        XaeroMapCompat.resetFacadeForTest();
    }

    @SuppressWarnings("unchecked")
    private XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        var floor = new BlockState[256];
        var biome = (ResourceKey<Biome>[]) new ResourceKey[256];
        return new XaeroTileExtractor.PreparedTile(chunkX, chunkZ, -64,
                floor, new short[256], new short[256], biome, new byte[256],
                new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }

    private void offer(int chunkX, int chunkZ) {
        this.bridge.offerPrepared(OVERWORLD, tile(chunkX, chunkZ));
    }

    // ---- resolve / facade ----

    @Test
    void resolveFailsSoftWhenAClassIsMissing() {
        assertThrows(ClassNotFoundException.class, () -> XaeroMapCompat.Handles.resolve(name -> {
            if (name.equals("xaero.map.region.MapTile")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }));
    }

    @Test
    void resolveFailsSoftWhenAMemberIsMissing() {
        // A class of the wrong SHAPE (right name, no members) must fail resolution —
        // the all-or-nothing rule that keeps a drifted Xaero from a half-bound bridge.
        assertThrows(ReflectiveOperationException.class,
                () -> XaeroMapCompat.Handles.resolve(name -> {
                    if (name.equals("xaero.map.region.MapTile")) return Object.class;
                    return Class.forName(name);
                }));
    }

    @Test
    void facadeIsNullSafeAndInitRegistersTheConsumer() {
        XaeroMapCompat.resetFacadeForTest();
        assertDoesNotThrow(XaeroMapCompat::clientTick);
        assertDoesNotThrow(XaeroMapCompat::onDisconnect);
        org.junit.jupiter.api.Assertions.assertNull(XaeroMapCompat.diagLine(),
                "no Xaero detected → no diag line");
        var cfg = dev.vox.lss.config.LSSClientConfig.CONFIG;
        boolean old = cfg.enableXaeroMapBridge;
        try {
            cfg.enableXaeroMapBridge = true;
            assertTrue(XaeroMapCompat.init(), "init must succeed against the stubs");
            assertTrue(dev.vox.lss.api.LSSApi.hasVoxelConsumers(),
                    "init must register the column consumer");
            assertNotNull(XaeroMapCompat.diagLine());
        } finally {
            // Deregister the production consumer: session end with the toggle off.
            cfg.enableXaeroMapBridge = false;
            XaeroMapCompat.onDisconnect();
            cfg.enableXaeroMapBridge = old;
            XaeroMapCompat.resetFacadeForTest();
        }
    }

    @Test
    void aResolveFailureIsVisibleAsUnavailableInDiag() {
        // The drift case (plan §7.1's top risk) must be distinguishable from "not
        // installed": init fails → no instance → but the diag line still renders.
        XaeroMapCompat.resetFacadeForTest();
        org.junit.jupiter.api.Assertions.assertNull(XaeroMapCompat.diagLine());
        assertFalse(XaeroMapCompat.initWith(name -> {
            throw new ClassNotFoundException(name);
        }));
        var line = XaeroMapCompat.diagLine();
        assertNotNull(line, "resolve-failed must render a diag line");
        assertTrue(line.contains("state=unavailable"), line);
    }

    // ---- registration lifecycle ----

    @Test
    void registrationIsAddOnlyMidSessionAndSettlesAtSessionEnd() {
        assertEquals(1, this.registered.size(), "enabled at init registers the consumer");
        this.enabled = false;
        this.bridge.pump();
        assertEquals(1, this.registered.size(),
                "mid-session disable must NOT deregister — the no-consumer path would"
                        + " report every arriving column as an ingest failure (re-serve"
                        + " churn for a map problem)");
        this.bridge.onSessionEnd();
        assertTrue(this.registered.isEmpty(),
                "session end releases the capability bit for the next handshake");
        this.enabled = true;
        this.bridge.pump();
        assertEquals(1, this.registered.size(), "re-enabling re-registers");
    }

    @Test
    void sessionEndClearsQueueAndReArmsTheDeathLatch() {
        latchTheBridgeDead();
        assertTrue(this.bridge.deadForTest(), "premise: the latch fired");
        this.bridge.onSessionEnd();
        assertFalse(this.bridge.deadForTest(),
                "the latch is SESSION-scoped — one bad session must not disable the"
                        + " feature until restart");
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.registered.size(), "enabled bridge stays registered for next session");
    }

    @Test
    void offersOutsideALiveSessionAreDropped() {
        this.sessionActive = false;
        this.bridge.offerColumn(OVERWORLD, 3, 3, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(0, this.bridge.queuedForTest(),
                "the disconnect-drain race must not carry a stale tile into the next"
                        + " server's (or a singleplayer world's) persistent map");
    }

    @Test
    void disabledPumpClearsTheQueue() {
        offer(100, 100);
        assertEquals(1, this.bridge.queuedForTest());
        this.enabled = false;
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
    }

    // ---- queue policy ----

    @Test
    void latestWinsKeepsTheNewerDistinctTile() {
        var first = tile(5, 5);
        var second = tile(5, 5);
        second.floorState()[0] = Blocks.STONE.defaultBlockState();
        this.bridge.offerPrepared(OVERWORLD, first);
        this.bridge.offerPrepared(OVERWORLD, second);
        assertEquals(1, this.bridge.queuedForTest(), "same position coalesces");
        this.bridge.pump();
        var region = this.processor.regions.values().iterator().next();
        var block = region.getChunk(1, 1).getTile(1, 1).blocks[0][0];
        assertEquals(Blocks.STONE.defaultBlockState(), block.state,
                "the SECOND tile's content must win (latest-wins, not first-wins)");
    }

    @Test
    void boundedOverflowDropsTheOldestEntry() {
        offer(9999, 9999); // the oldest — must be the one evicted
        for (int i = 0; i < XaeroMapCompat.MAX_QUEUE; i++) {
            offer(1000 + i, 0);
        }
        assertEquals(XaeroMapCompat.MAX_QUEUE, this.bridge.queuedForTest());
        assertTrue(this.bridge.counterForTest("dropped_overflow") >= 1);
        assertFalse(this.bridge.hasQueuedForTest(9999, 9999),
                "eviction must take the OLDEST entry, not an arbitrary one");
        assertTrue(this.bridge.hasQueuedForTest(1000 + XaeroMapCompat.MAX_QUEUE - 1, 0),
                "the newest entry must survive");
    }

    @Test
    void theByteGaugeBoundsOverlayHeavyTiles() {
        // Max-overlay tiles are ~87 KB by the gauge's estimate; the 24 MB budget
        // admits ~290 of them — far below the 2048 count cap.
        var runs = new XaeroTileExtractor.OverlayRun[256][];
        for (int i = 0; i < 256; i++) {
            runs[i] = new XaeroTileExtractor.OverlayRun[XaeroTileExtractor.MAX_OVERLAYS];
            for (int r = 0; r < runs[i].length; r++) {
                runs[i][r] = new XaeroTileExtractor.OverlayRun(
                        Blocks.WATER.defaultBlockState(), (byte) 0, false, 1);
            }
        }
        for (int i = 0; i < 700; i++) {
            var heavy = tile(i * 4, 0);
            var withRuns = new XaeroTileExtractor.PreparedTile(heavy.chunkX(), heavy.chunkZ(),
                    heavy.worldBottomY(), heavy.floorState(), heavy.floorY(), heavy.topY(),
                    heavy.biome(), heavy.light(), heavy.glowing(), runs);
            this.bridge.offerPrepared(OVERWORLD, withRuns);
        }
        assertTrue(this.bridge.queuedForTest() < 700,
                "the byte gauge must evict before the count cap on overlay-heavy tiles"
                        + " (queued=" + this.bridge.queuedForTest() + ")");
        assertTrue(this.bridge.queuedBytesForTest() <= XaeroMapCompat.MAX_QUEUE_BYTES);
        assertTrue(this.bridge.counterForTest("dropped_overflow") > 0);
    }

    @Test
    void aCrossDimensionServeReplacesTheStaleEntry() {
        this.bridge.offerPrepared(NETHER, tile(3, 3));
        this.bridge.offerPrepared(OVERWORLD, tile(3, 3));
        assertEquals(1, this.bridge.queuedForTest());
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "the replacement (current-dimension) entry must commit");
        assertEquals(0, this.bridge.counterForTest("dropped_stale"));
    }

    // ---- the gate ladder (each not-ready gate defers: entry retained, no events) ----

    @Test
    void ladderNotReadyStatesDeferWithoutTouchingXaero() {
        offer(64, 64);
        record CaseSetter(String name, Runnable arm, Runnable disarm) {}
        var cases = List.of(
                new CaseSetter("no session", () -> WorldMapSession.current = null,
                        () -> { var s = new WorldMapSession(); s.processor = this.processor; WorldMapSession.current = s; }),
                new CaseSetter("unusable session", () -> WorldMapSession.current.usable = false,
                        () -> WorldMapSession.current.usable = true),
                new CaseSetter("null processor", () -> WorldMapSession.current.processor = null,
                        () -> WorldMapSession.current.processor = this.processor),
                new CaseSetter("writing paused", () -> this.processor.writingPaused = true,
                        () -> this.processor.writingPaused = false),
                new CaseSetter("waiting for world update", () -> this.processor.waitingForWorldUpdate = true,
                        () -> this.processor.waitingForWorldUpdate = false),
                new CaseSetter("detection incomplete", () -> this.processor.saveLoad.regionDetectionComplete = false,
                        () -> this.processor.saveLoad.regionDetectionComplete = true),
                new CaseSetter("multiworld unwritable", () -> this.processor.multiworldWritable = false,
                        () -> this.processor.multiworldWritable = true),
                new CaseSetter("no world", () -> this.processor.world = null,
                        () -> this.processor.world = this.worldToken),
                new CaseSetter("map locked", () -> this.processor.currentMapLocked = true,
                        () -> this.processor.currentMapLocked = false),
                new CaseSetter("cache-only mode", () -> this.processor.mapWorld.cacheOnlyMode = true,
                        () -> this.processor.mapWorld.cacheOnlyMode = false),
                new CaseSetter("no world id", () -> this.processor.currentWorldId = null,
                        () -> this.processor.currentWorldId = "stub-world"),
                new CaseSetter("ignored world", () -> this.processor.ignoreWorldResult = true,
                        () -> this.processor.ignoreWorldResult = false),
                new CaseSetter("mainWorld mismatch", () -> this.processor.mainWorld = new Object(),
                        () -> this.processor.mainWorld = this.worldToken),
                new CaseSetter("dimension browsing", () -> this.processor.mapWorld.currentDimensionId = NETHER,
                        () -> this.processor.mapWorld.currentDimensionId = OVERWORLD));
        for (var c : cases) {
            c.arm().run();
            XaeroStubEvents.clear();
            this.bridge.pump();
            assertEquals(1, this.bridge.queuedForTest(), c.name() + ": entry must be RETAINED");
            assertTrue(XaeroStubEvents.snapshot().stream().noneMatch(e -> e.startsWith("region.")
                            || e.startsWith("tileChunk.") || e.startsWith("tile.")),
                    c.name() + ": a not-ready ladder must not touch region/tile state");
            c.disarm().run();
        }
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "ladder ready again: the entry commits");
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void theRegionSaveRaceGateDefers() {
        offer(8, 8);
        var region = new MapRegion();
        region.writingPaused = true; // MapSaveLoad is saving this region (pushWriterPause)
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest(),
                "a region being saved must DEFER — committing would race the save");
        assertTrue(this.bridge.counterForTest("defer_events") >= 1);
        region.writingPaused = false;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void staleDimensionEntriesDropAtThePump() {
        this.bridge.offerPrepared(NETHER, tile(3, 3));
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.bridge.counterForTest("dropped_stale"));
        assertEquals(0, this.bridge.counterForTest("written"));
    }

    private void loadChunk(int chunkX, int chunkZ) {
        this.loadedChunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    @Test
    void fullySurroundedLoadedChunksAreSkippedNotWritten() {
        offer(7, 9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loadChunk(7 + dx, 9 + dz);
            }
        }
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.bridge.counterForTest("skipped_native"));
        assertEquals(0, this.bridge.counterForTest("written"));
    }

    @Test
    void aLoadedEdgeChunkIsBridgeWritten() {
        // The boundary-ring regression (field-tested 2026-08-23): the native writer's
        // edge rule refuses any chunk without all 8 neighbors loaded, so the OUTERMOST
        // ring of loaded vanilla chunks is never natively written — skipping it here
        // too left a 1-chunk black circle at the vanilla/LOD boundary around every
        // join point. A loaded-but-edge chunk must be bridge-written; the native
        // writer reclaims it on its clean-flag once fully surrounded.
        offer(7, 9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loadChunk(7 + dx, 9 + dz);
            }
        }
        this.loadedChunks.remove(((long) 8 << 32) | 10L); // one missing neighbor → edge
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "an edge chunk (native-unwritable) must be bridge-written");
        assertEquals(0, this.bridge.counterForTest("skipped_native"));
    }

    // ---- deferral flavors (the dead-knob branches) ----

    @Test
    void aNullLeafRegionDefers() {
        offer(2, 2);
        this.processor.leafMapRegionReturnsNull = true; // detection-completeness race
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest());
        this.processor.leafMapRegionReturnsNull = false;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void aPboDownloadingTileChunkDefers() {
        offer(4, 4);
        var region = new MapRegion();
        var tileChunk = new MapTileChunk(region, 1, 1);
        tileChunk.loadState = 2;
        tileChunk.leafTexture.downloadFromPBO = true;
        region.setChunk(1, 1, tileChunk);
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest(), "PBO download in flight → defer");
        tileChunk.leafTexture.downloadFromPBO = false;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void anUnloadedTileChunkDefers() {
        offer(4, 4);
        var region = new MapRegion();
        var tileChunk = new MapTileChunk(region, 1, 1); // loadState 0
        region.setChunk(1, 1, tileChunk);
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest(), "tile chunk not at loadState 2 → defer");
        tileChunk.loadState = 2;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    // ---- the region load dance ----

    @Test
    void unloadedRegionIsLoadRequestedWithBeingWrittenFirstAndNeverReRequested() {
        offer(64, 64); // region (2,2), fresh
        var region = new MapRegion();
        region.loadState = 0;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "an unloaded region must be load-REQUESTED (fresh regions never self-promote)");
        assertEquals(1, this.bridge.counterForTest("load_requests"));
        assertEquals(1, this.bridge.regionsWaitingForTest());
        var events = XaeroStubEvents.snapshot();
        int setBeingWritten = events.indexOf("region.setBeingWritten true");
        int requestLoad = events.indexOf("saveLoad.requestLoad lss-xaero-bridge");
        assertTrue(setBeingWritten >= 0 && requestLoad > setBeingWritten,
                "setBeingWritten(true) must precede requestLoad (it stops the load drain"
                        + " demoting an empty fresh region): " + events);
        assertTrue(events.contains("saveLoad.setNextToLoadByViewing"),
                "the request must register with Xaero's shared admission bookkeeping");
        assertEquals(1, this.bridge.queuedForTest(), "awaiting-load entries stay queued");

        // The outstanding window: while the load hasn't landed, further pumps issue
        // NO re-request…
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 50; i++) {
            this.bridge.pump();
        }
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "outstanding window: one request per region until its load lands");
        // …and awaiting-load deferrals are EXEMPT from the deferral cap.
        assertEquals(1, this.bridge.queuedForTest(),
                "an entry awaiting a region load must never be dropped by the deferral cap");

        // The load lands: the next pump commits and frees the window slot.
        region.loadState = 2;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(0, this.bridge.outstandingLoadsForTest());
    }

    @Test
    void theSharedPacingGaugeIsNeverConsulted() {
        // The deadlock class is structurally gone (throughput round, plan §14):
        // shouldAllowAnotherRegionToLoad synchronizes on its own (possibly BRANCH)
        // region — a lock-order inversion against Xaero's parent-then-leaf loader —
        // and the bridge replaces it with its own outstanding window, so the method
        // must never be invoked at all.
        offer(64, 64);
        var gauge = new MapRegion(); // park a "previous" region in the pacing slot
        this.processor.saveLoad.nextToLoadByViewing = gauge;
        var region = new MapRegion();
        region.loadState = 0;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        var events = XaeroStubEvents.snapshot();
        assertFalse(events.contains("pacing.shouldAllowAnotherRegionToLoad"),
                "the shared gauge must never be consulted (deadlock-class removal): " + events);
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "the outstanding window still grants the load");
    }

    @Test
    void grantsGoToTheLargestPendingRegionsUpToTheWindow() {
        // 10 pending regions; region (2,2) holds THREE tiles, the rest one each. The
        // grant phase must batch up to MAX_OUTSTANDING_LOADS requests per pump and
        // spend them largest-cluster-first, so each grant unlocks the most tiles.
        offer(64, 64);
        offer(68, 64);
        offer(72, 64); // three tiles in region (2,2)
        for (int i = 1; i <= 9; i++) {
            offer(64 + i * 32, 320); // regions (2+i, 10), one tile each
        }
        var bigRegion = unloadedRegion();
        this.processor.regions.put((2L << 32) | 2L, bigRegion);
        for (int i = 1; i <= 9; i++) {
            this.processor.regions.put(((long) (2 + i) << 32) | 10L, unloadedRegion());
        }
        this.bridge.pump();
        assertEquals(XaeroMapCompat.MAX_OUTSTANDING_LOADS,
                this.processor.saveLoad.loadRequests.size(),
                "one pump must batch a full window of load requests");
        assertEquals(XaeroMapCompat.MAX_OUTSTANDING_LOADS, this.bridge.outstandingLoadsForTest());
        assertTrue(this.processor.saveLoad.loadRequests.get(0) == bigRegion,
                "the region holding the most queued tiles must be requested FIRST");
        assertEquals(10, this.bridge.regionsWaitingForTest());
    }

    @Test
    void aBucketOfManyEntriesCountsOneDeferEventPerPump() {
        // The bucketed drain probes each region ONCE per pump — at large radius a
        // ring crosses ~r/4 regions, and per-entry probing burned the whole budget
        // on identical AWAITING_LOAD answers (the throughput round's motivation).
        for (int i = 0; i < 20; i++) {
            offer(64 + i, 64); // 20 tiles, all in region (2,2) — chunkX 64..83
        }
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("defer_events"),
                "one defer event per BUCKET per pump, not per entry");
        assertEquals(20, this.bridge.queuedForTest());
    }

    @Test
    void aRegionThatCannotRequestReloadAwaitsWithoutARequest() {
        offer(64, 64);
        var region = new MapRegion();
        region.loadState = 0;
        region.canRequestReload = false;
        this.processor.regions.put((2L << 32) | 2L, region);
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 10; i++) {
            this.bridge.pump();
        }
        assertTrue(this.processor.saveLoad.loadRequests.isEmpty(), "no request possible");
        assertEquals(1, this.bridge.queuedForTest(),
                "awaiting-load (even requestless) is exempt from the deferral cap");
    }

    @Test
    void twoUnloadedRegionsAreGrantedInTheSamePump() {
        offer(64, 64);   // region (2,2)
        offer(320, 320); // region (10,10)
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.processor.regions.put((10L << 32) | 10L, unloadedRegion());
        this.bridge.pump();
        assertEquals(2, this.processor.saveLoad.loadRequests.size(),
                "both fit the outstanding window — one pump grants both");
    }

    private MapRegion unloadedRegion() {
        var r = new MapRegion();
        r.loadState = 0;
        return r;
    }

    // ---- the commit sequence ----

    @Test
    void commitMirrorsTheDecompiledSequence() {
        offer(64, 65); // region (2,2), tileChunk (16,16) local (0,0), inside (0,1)
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        var events = XaeroStubEvents.snapshot();

        // The region lookup targets the SURFACE layer (a cave-layer regression would
        // write LODs into Xaero's cave map invisibly).
        assertTrue(events.contains("processor.getLeafMapRegion layer=" + Integer.MAX_VALUE + " 2,2"),
                "surface-layer region lookup: " + events);

        // setBeingWritten is set and NEVER cleared by the bridge — the save path owns
        // the reset; a false here means tiles silently never persist.
        assertTrue(events.contains("region.setBeingWritten true"));
        assertFalse(events.contains("region.setBeingWritten false"),
                "the bridge must NEVER clear setBeingWritten: " + events);

        // Created tile chunk: ctor gets WORLD tile-chunk coords, loadState 2 + region
        // cache invalidated + terrain marked + highlights prepared.
        assertTrue(events.contains("tileChunk.new 16,16"), events.toString());
        assertTrue(events.contains("tileChunk.setLoadState 2"));
        assertTrue(events.contains("region.setAllCachePrepared false"));
        assertTrue(events.contains("tileChunk.setHasHadTerrain"));
        assertTrue(events.contains("highlights.prepare"));

        // Order: setChanged(true) precedes setTile (the native new-tile mark), then
        // worldInterpretationVersion → writtenCave → setTile → writtenOnce → loaded
        // (setTile's tileWasLoadedWithTopHeightValues branch reads the version).
        int changedTrue = events.indexOf("tileChunk.setChanged true");
        int version = events.indexOf("tile.setWorldInterpretationVersion 1");
        int cave = events.indexOf("tile.setWrittenCave");
        int setTile = events.indexOf("tileChunk.setTile 0,1");
        int writtenOnce = events.indexOf("tile.setWrittenOnce true");
        int loaded = events.indexOf("tile.setLoaded true");
        assertTrue(changedTrue >= 0 && changedTrue < setTile,
                "setChanged(true) must precede setTile: " + events);
        assertTrue(version >= 0 && cave > version && setTile > cave
                        && writtenOnce > setTile && loaded > writtenOnce,
                "commit order must mirror the decompiled writeChunk: " + events);

        // Buffers: flag-then-consume, never a direct updateBuffers (no handle exists).
        int toUpdate = events.lastIndexOf("tileChunk.setToUpdateBuffers true");
        int consumed = events.lastIndexOf("tileChunk.setChanged false");
        assertTrue(toUpdate > loaded && consumed > toUpdate,
                "buffers are flagged for Xaero's preUpload sweep, then the change is"
                        + " consumed (the native neighbor pattern): " + events);
    }

    @Test
    void committedPixelsCarryTheTileInputs() {
        var prepared = tile(4, 4);
        prepared.floorState()[0] = Blocks.GLOWSTONE.defaultBlockState();
        prepared.floorY()[0] = 63;
        prepared.topY()[0] = 66;
        prepared.light()[0] = 7;
        prepared.glowing()[0] = true;
        prepared.biome()[0] = Biomes.DESERT;
        prepared.overlays()[0] = new XaeroTileExtractor.OverlayRun[]{
                new XaeroTileExtractor.OverlayRun(
                        Blocks.WATER.defaultBlockState(), (byte) 3, false, 3)};
        this.bridge.offerPrepared(OVERWORLD, prepared);
        this.bridge.pump();
        var region = this.processor.regions.values().iterator().next();
        MapTileChunk tileChunk = region.getChunk(1, 1); // tileChunk (1,1) for chunk (4,4)
        assertNotNull(tileChunk);
        var mapTile = tileChunk.getTile(0, 0);
        assertNotNull(mapTile);
        var block = mapTile.blocks[0][0];
        assertEquals(Blocks.GLOWSTONE.defaultBlockState(), block.state);
        assertEquals(63, block.height);
        assertEquals(66, block.topHeight);
        assertEquals(7, block.light);
        assertTrue(block.glowing, "glowing must pass through to MapBlock.write");
        assertEquals(Biomes.DESERT, block.biome, "biome must pass through to MapBlock.write");
        assertEquals(-64, block.preparedBottomY, "prepareForWriting must run (and first)");
        assertFalse(block.cave, "surface layer writes cave=false");
        // The faithful stub CLEARS overlays in prepareForWriting, so a surviving
        // overlay is a REAL prepare→addOverlay→write order pin (review MAJOR: the
        // old no-op stub made this vacuous).
        assertEquals(1, block.overlays.size(),
                "the overlay must survive — prepareForWriting after addOverlay would wipe it");
        assertEquals(3, block.overlays.get(0).opacity);
        assertTrue(this.processor.overlayManager.internCalls >= 1,
                "overlays are interned through OverlayManager.getOriginal");
        assertEquals(Integer.MAX_VALUE, mapTile.writtenCaveStart, "surface cave sentinel");
        // Pixel (0,1) had no data in the helper tile: write(null biome/state) keeps
        // the prepared reset values (a REAL extractor tile never ships null states —
        // voidColumnIsTheEraseShape pins the actual erase shape).
        var voidBlock = mapTile.blocks[0][1];
        org.junit.jupiter.api.Assertions.assertNull(voidBlock.state);
    }

    @Test
    void anExistingTileChunkSkipsTheCreatedBlock() {
        // The setHasHadTerrain/highlights work belongs to the native createdTileChunk
        // branch ONLY — running it on every commit would churn Xaero's highlight
        // preparer and re-mark terrain per tile.
        offer(4, 4);
        var region = new MapRegion();
        var tileChunk = new MapTileChunk(region, 1, 1);
        tileChunk.loadState = 2;
        region.setChunk(1, 1, tileChunk);
        this.processor.regions.put(0L, region);
        XaeroStubEvents.clear();
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        var events = XaeroStubEvents.snapshot();
        assertFalse(events.contains("tileChunk.setHasHadTerrain"),
                "existing tile chunk: no created-block work — " + events);
        assertFalse(events.contains("highlights.prepare"),
                "existing tile chunk: no highlight prepare — " + events);
        assertFalse(tileChunk.hasHadTerrain);
    }

    @Test
    void budgetStopsAfterMaxCommitsPerPump() {
        for (int i = 0; i < XaeroMapCompat.MAX_COMMITS_PER_PUMP + 2; i++) {
            offer(i * 4, 0); // distinct tile chunks
        }
        this.bridge.pump();
        assertEquals(XaeroMapCompat.MAX_COMMITS_PER_PUMP, this.bridge.counterForTest("written"));
        assertEquals(2, this.bridge.queuedForTest(), "over-budget entries wait for the next pump");
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
    }

    @Test
    void theNanosBudgetStopsTheDrainImmediately() {
        offer(0, 0);
        offer(4, 0);
        this.bridge.pumpNanosBudget = 0; // every entry is over budget
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("written"),
                "a zero time budget must commit nothing");
        assertEquals(2, this.bridge.queuedForTest(), "over-budget entries are retained");
    }

    @Test
    void busyRegionDefersAndTheCapEventuallyDrops() {
        offer(8, 8);
        var region = new MapRegion();
        region.resting = false; // ladder-ready but the region is busy
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest());
        assertTrue(this.bridge.counterForTest("defer_events") >= 1);
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 2; i++) {
            this.bridge.pump();
        }
        assertEquals(0, this.bridge.queuedForTest(),
                "a permanently-busy LOADED region eventually drops the entry (bounded deferral)");
        assertTrue(this.bridge.counterForTest("dropped_expired") >= 1);
    }

    // ---- failure containment ----

    /** Queue THROW_LATCH+3 entries whose commits all throw, and pump until dead. */
    private void latchTheBridgeDead() {
        var region = new MapRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            offer(i * 4, 64);
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 16 & 7, tileChunk);
        }
        this.processor.regions.put(2L, region);
        for (int i = 0; i < 3 && !this.bridge.deadForTest(); i++) {
            this.bridge.pump();
        }
    }

    @Test
    void fiveConsecutiveCommitFailuresLatchTheBridgeDead() {
        // All eight entries land in region (0,2): pre-create it with an ARMED (throwing)
        // tile chunk at every entry's local slot, so each commit attempt fails.
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            offer(i * 4, 64);
        }
        var region = new MapRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 16 & 7, tileChunk);
        }
        this.processor.regions.put(2L, region); // key (regionX=0)<<32 | regionZ=2
        for (int i = 0; i < 3 && !this.bridge.deadForTest(); i++) {
            this.bridge.pump();
        }
        assertTrue(this.bridge.deadForTest(),
                "consecutive commit failures must latch the bridge dead");
        assertEquals(0, this.bridge.queuedForTest(), "death clears the queue");
        assertTrue(this.bridge.counterForTest("commit_failures") >= XaeroMapCompat.THROW_LATCH);
    }

    @Test
    void failuresSpreadAcrossPumpsStillLatch() {
        // One armed entry per pump, five pumps: a regression that resets the count on
        // a clean ladder pass (or at pump start) would never latch (review MAJOR —
        // the original latch test armed everything in one pump and could not tell).
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            offer(i * 4, 64);
            var region = this.processor.regions.computeIfAbsent(2L, k -> new MapRegion());
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 0, tileChunk);
            this.bridge.pump();
        }
        assertTrue(this.bridge.deadForTest(),
                "5 consecutive failures across 5 pumps must latch (no per-pump reset)");
    }

    @Test
    void aSuccessBetweenFailuresResetsTheLatchCount() {
        // Alternate failing and healthy entries: the latch must never fire because
        // every successful commit resets the consecutive count.
        var region = this.processor.regions.computeIfAbsent(2L, k -> new MapRegion());
        for (int round = 0; round < XaeroMapCompat.THROW_LATCH + 2; round++) {
            int failX = round * 8;       // tileChunk (2i, 16) armed
            int okX = round * 8 + 4;     // tileChunk (2i+1, 16) healthy
            offer(failX, 64);
            int tcX = failX >> 2;
            var armed = new MapTileChunk(region, tcX, 16);
            armed.loadState = 2;
            armed.setTileThrows = true;
            region.setChunk(tcX & 7, 0, armed);
            this.bridge.pump();
            offer(okX, 64);
            this.bridge.pump();
            assertFalse(this.bridge.deadForTest(),
                    "round " + round + ": a success between failures must reset the latch");
        }
    }

    @Test
    void repeatedExtractionFailuresLatchTheBridge() {
        var consumer = this.registered.get(0);
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            // Null column data NPEs inside extraction — swallowed, counted.
            assertDoesNotThrow(() -> consumer.onVoxelColumnReceived(null, OVERWORLD, 0, 0, null));
        }
        assertTrue(this.bridge.deadForTest(),
                "a permanently-throwing extractor must not burn the decode thread forever");
    }

    @Test
    void theConsumerDoesNotOverrideThePacingGauge() {
        assertEquals(-1, this.registered.get(0).pendingIngestBacklog(),
                "the map must drop instead of pacing the LOD stream (plan §2.8)");
    }

    // ---- diag ----

    @Test
    void describeRendersTheHouseStyle() {
        var line = this.bridge.describe();
        assertTrue(line.startsWith("XaeroMap: state=active, queued="), line);
        assertTrue(line.contains(", written=") && line.contains(", defer_events=")
                && line.contains(", dropped=") && line.contains(", commit_failures=")
                && line.contains(", regions_waiting="), line);
        this.enabled = false;
        assertTrue(this.bridge.describe().contains("state=disabled"));
    }
}
