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
 *       setTile, worldInterpretationVersion before setTile, NO setToUpdateBuffers
 *       flag ever (Xaero's sweep consumes it outside isResting — the
 *       cache-not-prepared crash, plan §15) with the texture rebuild coalesced
 *       into the pump's flush phase under the writer gates, the faithful
 *       prepare→overlays→write per-pixel order (the stub's prepareForWriting
 *       clears overlays like the real one, so a wrong order wipes them), and
 *       {@code setBeingWritten} set-and-NEVER-cleared;</li>
 *   <li>the region load dance: beingWritten TRUE at request time (STATE-recorded
 *       by the stub — event order was vacuous, the commit probe also sets it),
 *       the memoryless outstanding window (in-flight recognized from Xaero's own
 *       canRequestReload, the loader's dead ends self-heal, cache-parked regions
 *       revive 3→4), Xaero's shared pacing surface untouched in BOTH directions,
 *       largest cluster issued LAST for the LIFO drain, awaiting-load exempt
 *       from the deferral cap;</li>
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

    private ResourceKey<Level> clientDimension = OVERWORLD;

    private final XaeroMapCompat.LevelOps fakeLevelOps = new XaeroMapCompat.LevelOps() {
        @Override
        public Object dimension(Object world) {
            return clientDimension;
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
        this.bridge.updateNanosBudget = Long.MAX_VALUE;
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
        // Max-overlay tiles are ~87 KB by the gauge's estimate; the 48 MB budget
        // admits ~550 of them — far below the 8192 count cap.
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
        assertTrue(events.contains("saveLoad.requestLoad lss-xaero-bridge beingWritten"),
                "beingWritten must already be TRUE at request time — it stops the load"
                        + " drain demoting an empty fresh region (STATE-recorded by the"
                        + " stub; event order was vacuous, the commit probe also sets it): "
                        + events);
        assertFalse(events.contains("saveLoad.setNextToLoadByViewing"),
                "the native consumers' pacing token must never be repointed: " + events);
        assertEquals(1, this.bridge.queuedForTest(), "awaiting-load entries stay queued");

        // The memoryless window: once requested, Xaero's own canRequestReload
        // answers false (the stub flips it like the real reloadHasBeenRequested),
        // so every further pump reads IN-FLIGHT and issues NO re-request…
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 50; i++) {
            this.bridge.pump();
        }
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "one request per region until its load lands");
        // …and awaiting-load deferrals are EXEMPT from the deferral cap.
        assertEquals(1, this.bridge.queuedForTest(),
                "an entry awaiting a region load must never be dropped by the deferral cap");

        // The load lands: the next pump commits and nothing waits.
        region.loadState = 2;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(0, this.bridge.regionsWaitingForTest());
    }

    @Test
    void theSharedPacingSurfaceIsNeverTouched() {
        // BOTH directions of Xaero's load-pacing surface stay untouched (plan §14):
        // shouldAllowAnotherRegionToLoad synchronizes on its own (possibly BRANCH)
        // region — a lock-order inversion against Xaero's parent-then-leaf loader
        // thread (a real client deadlock) — and setNextToLoadByViewing is purely
        // the four native consumers' pacing token (the loader never reads it);
        // repointing it at a far bridge region vetoed writer/minimap/GUI/reloader
        // for multi-second stretches after each granted region's save.
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
        assertFalse(events.contains("saveLoad.setNextToLoadByViewing"),
                "the pacing token must never be repointed (native-consumer veto): " + events);
        assertTrue(this.processor.saveLoad.nextToLoadByViewing == gauge,
                "the pacing token must be left exactly as found");
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "the memoryless window still grants the load");
        // Structural: no reflective handle for the pacing surface may even exist.
        for (var f : XaeroMapCompat.Handles.class.getDeclaredFields()) {
            var n = f.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(n.contains("shouldallow") || n.contains("nexttoload"),
                    "no handle for the pacing surface may exist: " + f.getName());
        }
    }

    @Test
    void grantsGoToTheLargestPendingRegionsUpToTheWindow() {
        // 10 pending regions; region (2,2) holds THREE tiles, the rest one each —
        // and the big cluster is offered LAST, so insertion order cannot masquerade
        // as size order (3-Opus fold: the old arrangement made the sort vacuous).
        // The grant phase spends up to MAX_OUTSTANDING_LOADS requests per pump on
        // the largest clusters, ISSUED smallest-first: the loader drains
        // toLoad.get(0) against our priority front-inserts (LIFO), so the
        // LAST-issued (largest) region is the one drained FIRST.
        for (int i = 1; i <= 9; i++) {
            offer(64 + i * 32, 320); // regions (2+i, 10), one tile each
        }
        offer(64, 64);
        offer(68, 64);
        offer(72, 64); // three tiles in region (2,2) — offered LAST
        var bigRegion = unloadedRegion();
        this.processor.regions.put((2L << 32) | 2L, bigRegion);
        for (int i = 1; i <= 9; i++) {
            this.processor.regions.put(((long) (2 + i) << 32) | 10L, unloadedRegion());
        }
        this.bridge.pump();
        var requests = this.processor.saveLoad.loadRequests;
        assertEquals(XaeroMapCompat.MAX_OUTSTANDING_LOADS, requests.size(),
                "one pump must batch a full window of load requests");
        assertTrue(requests.get(requests.size() - 1) == bigRegion,
                "the region holding the most queued tiles must be issued LAST — the"
                        + " final front-insert is what the LIFO drain serves FIRST");
        assertEquals(10, this.bridge.regionsWaitingForTest());
        assertEquals(0, this.bridge.counterForTest("commit_failures"));
    }

    @Test
    void theWindowRefillsAsLoadsLand() {
        // 12 waiting regions, window 8: pump 1 requests 8 (the stub flips their
        // canRequestReload — honestly in flight). Landing 3 frees 3 slots: the
        // next pump commits the landed tiles AND requests exactly 3 more.
        for (int i = 0; i < 12; i++) {
            offer(64 + i * 32, 320); // regions (2..13, 10)
            this.processor.regions.put(((long) (2 + i) << 32) | 10L, unloadedRegion());
        }
        this.bridge.pump();
        assertEquals(8, this.processor.saveLoad.loadRequests.size());
        this.bridge.pump();
        assertEquals(8, this.processor.saveLoad.loadRequests.size(),
                "window full, no slot free — no new grants");
        for (int i = 0; i < 3; i++) {
            this.processor.saveLoad.loadRequests.get(i).loadState = 2; // lands
        }
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("written"), "landed tiles commit");
        assertEquals(11, this.processor.saveLoad.loadRequests.size(),
                "the 3 freed slots must refill with exactly 3 new grants");
    }

    @Test
    void aRemovedDeadEndRegionIsReRequestedOnAFreshObject() {
        // Two of the loader's three dead ends END IN removeMapRegion (failed read →
        // loadState 4 + remove; empty load → remove): the granted region OBJECT
        // disappears without ever reaching loadState 2. A slot-tracking window
        // would leak that slot forever (3-Opus fold MAJOR); the memoryless window
        // self-heals — the next probe's getLeafMapRegion(create=true) hands back a
        // fresh unloaded region that reads requestable again.
        offer(64, 64);
        long key = (2L << 32) | 2L;
        this.processor.regions.put(key, unloadedRegion());
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size());
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(), "in flight: no re-request");
        var deadEnded = this.processor.regions.remove(key); // Xaero's removeMapRegion
        this.processor.createdRegionLoadState = 0;          // detection creates UNLOADED
        this.bridge.pump();
        assertEquals(2, this.processor.saveLoad.loadRequests.size(),
                "the dead-ended region must be re-requested…");
        assertTrue(this.processor.saveLoad.loadRequests.get(1) != deadEnded
                        && this.processor.saveLoad.loadRequests.get(1)
                                == this.processor.regions.get(key),
                "…on the FRESH region object the probe re-created");
    }

    @Test
    void aCacheParkedRegionIsRevivedViaTheNativeThreeToFourTransition() {
        // The loader's third dead end (3-Opus fold MAJOR): a cache-only load parks
        // the region at loadState 3, where isResting AND canRequestReload are both
        // false FOREVER — without revival the bucket waits until session end.
        // Xaero's own clearRegion idiom (3→4) makes it requestable again.
        offer(64, 64);
        var region = new MapRegion();
        region.loadState = 3;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "a cache-parked region must be revived and requested");
        assertTrue(XaeroStubEvents.snapshot().contains("region.setLoadState 4"),
                "the revival must be Xaero's own 3→4 transition");
        region.loadState = 2; // the load lands
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.queuedForTest());
    }

    @Test
    void aParkedRegionWithPendingNativeWorkIsLeftAlone() {
        // The revival must RESTORE loadState 3 when the guards still refuse (a
        // pending native recache/refresh owns the region) — and never mark
        // beingWritten on the way out.
        offer(64, 64);
        var region = new MapRegion();
        region.loadState = 3;
        region.canRequestReload = false; // pending native work
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertTrue(this.processor.saveLoad.loadRequests.isEmpty());
        assertEquals(3, region.loadState, "the failed revival must restore loadState 3");
        org.junit.jupiter.api.Assertions.assertNull(region.beingWritten,
                "a refused request must not mark beingWritten");
        assertEquals(1, this.bridge.queuedForTest(), "the bucket keeps waiting");
    }

    @Test
    void aBucketOfManyEntriesCountsOneDeferEventPerPump() {
        // The bucketed drain probes each region ONCE per pump — at large radius a
        // ring crosses ~r/4 regions, and per-entry probing burned the whole budget
        // on identical awaiting-load answers (the throughput round's motivation).
        for (int i = 0; i < 20; i++) {
            offer(64 + i, 64); // 20 tiles, all in region (2,2) — chunkX 64..83
        }
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("defer_events"),
                "one defer event per BUCKET per pump, not per entry");
        assertEquals(20, this.bridge.queuedForTest());
        long lookups = XaeroStubEvents.snapshot().stream()
                .filter(e -> e.startsWith("processor.getLeafMapRegion")).count();
        assertEquals(2, lookups,
                "20 same-region entries must cost exactly TWO region lookups — one"
                        + " commit probe (bucket short-circuit) + one grant request");
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

        // Buffers: NO setToUpdateBuffers flag (Xaero's preUpload sweep consumes it with
        // no isResting check — the "cache not prepared" saver crash, plan §15) and no
        // rebuild at commit either: the change stays MARKED for the coalesced rebuild
        // phase, which consumes it (pinned in the rebuild-phase tests below).
        assertFalse(events.stream().anyMatch(e -> e.startsWith("tileChunk.setToUpdateBuffers")),
                "the flag must never be set: " + events);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")),
                "no rebuild inside the commit (coalesced per tile chunk): " + events);
        assertFalse(events.contains("tileChunk.setChanged false"),
                "the change is consumed by the rebuild, not the commit: " + events);
    }

    // ---- the rebuild phase (plan §15: the cache-not-prepared crash) ----

    private void pumpIdleWindow() {
        for (int i = 0; i <= this.bridge.updateIdlePumps; i++) {
            this.bridge.pump();
        }
    }

    private MapRegion theRegion() {
        assertEquals(1, this.processor.regions.size(), "one region in play");
        return this.processor.regions.values().iterator().next();
    }

    private static long count(List<String> events, String event) {
        return events.stream().filter(event::equals).count();
    }

    @Test
    void theRebuildRunsUnderTheWriterGatesAfterTheIdleWindowAndReArmsBeingWritten() {
        this.bridge.updateIdlePumps = 3;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        var region = theRegion();
        MapTileChunk tileChunk = region.getChunk(0, 0);
        assertTrue(tileChunk.wasChanged(), "the native transient: changed, unflagged");
        this.bridge.pump();
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "inside the coalescing window nothing rebuilds yet");

        // The saver reset beingWritten between the commit and the rebuild.
        region.beingWritten = false;
        pumpIdleWindow();
        var events = XaeroStubEvents.snapshot();
        int rebuilt = events.lastIndexOf("tileChunk.updateBuffers 16,16");
        int consumed = events.lastIndexOf("tileChunk.setChanged false");
        assertTrue(rebuilt >= 0 && consumed > rebuilt,
                "rebuild (under both region monitors — the stub enforces), then the change"
                        + " is consumed: " + events);
        assertEquals(Boolean.TRUE, region.beingWritten,
                "re-armed before the rebuild: the rebuilt texture must reach the cache, and"
                        + " the save path is what requests it");
        assertFalse(events.contains("region.setBeingWritten false"));
        assertFalse(tileChunk.wasChanged());
        assertFalse(region.allCachePrepared,
                "the rebuild un-prepares the region — the flip the saver races on");
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(1, tileChunk.bufferUpdates);
        assertTrue(events.contains("fastConfig.new"), "the native per-pass config snapshot");
    }

    @Test
    void rebuildsCoalescePerTileChunkAcrossPumps() {
        this.bridge.updateIdlePumps = 3;
        offer(64, 64);
        offer(65, 64);
        this.bridge.pump();
        offer(66, 65);
        offer(67, 67); // tile chunk (16,16) touched again — its window restarts
        this.bridge.pump();
        offer(68, 64); // tile chunk (17,16)
        this.bridge.pump();
        assertEquals(5, this.bridge.counterForTest("written"));
        assertEquals(2, this.bridge.counterForTest("pending_updates"));
        pumpIdleWindow();
        var events = XaeroStubEvents.snapshot();
        assertEquals(1, count(events, "tileChunk.updateBuffers 16,16"),
                "four tiles of one tile chunk → ONE rebuild: " + events);
        assertEquals(1, count(events, "tileChunk.updateBuffers 17,16"));
        assertEquals(2, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void aRebuildWaitsForARestingRegionAndAPermanentStallDrops() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.updateMaxStallPumps = 3;
        offer(64, 64);
        this.bridge.pump();
        var region = theRegion();
        region.resting = false; // recache requested / being saved — the crash window
        this.bridge.pump();
        this.bridge.pump();
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "never un-prepare a region that may be queued for caching");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        region.resting = true;
        this.bridge.pump();
        assertTrue(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(0, this.bridge.counterForTest("dropped_updates"));

        offer(68, 64);
        this.bridge.pump();
        region.resting = false;
        for (int i = 0; i < 6; i++) {
            this.bridge.pump();
        }
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 17,16"));
        assertEquals(1, this.bridge.counterForTest("dropped_updates"),
                "a region that never rests drops its owed rebuild, counted (its texture"
                        + " self-heals on reload)");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void theHardCapPausesCommitsUntilRebuildsDrain() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.pendingUpdatesHardCap = 2;
        offer(64, 64);
        offer(68, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"));
        var region = theRegion();
        region.resting = false;
        offer(72, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"),
                "at the hard cap commits pause — the owed set must never grow unbounded");
        assertEquals(1, this.bridge.queuedForTest(), "the entry stays queued, not dropped");
        assertEquals(0, this.bridge.counterForTest("dropped_expired"));
        region.resting = true;
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("written"),
                "the flush runs before the drain: drained rebuilds free the commit");
        assertEquals(2, this.bridge.counterForTest("buffer_updates"));
    }

    @Test
    void theSoftCapMakesTheOldestOwedRebuildDueAtOnce() {
        this.bridge.updateIdlePumps = 1000;
        this.bridge.pendingUpdatesSoftCap = 1;
        offer(64, 64);
        this.bridge.pump();
        this.bridge.pump();
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "at the cap nothing is over it");
        offer(68, 64);
        this.bridge.pump(); // commits (17,16) — the flush ran before, saw 1 pending
        this.bridge.pump(); // now 2 pending → the oldest is over the cap
        var events = XaeroStubEvents.snapshot();
        assertTrue(events.contains("tileChunk.updateBuffers 16,16"), events.toString());
        assertFalse(events.contains("tileChunk.updateBuffers 17,16"),
                "only the overflow is forced; the youngest keeps its window");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void anUnloadedOrReplacedTileChunkDropsItsOwedRebuild() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        var region = theRegion();
        region.setChunk(0, 0, new MapTileChunk(region, 16, 16)); // replaced
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("dropped_updates"));
        offer(68, 64);
        this.bridge.pump();
        region.loadState = 0; // unloaded
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("dropped_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")),
                "a reload rebuilds its own textures — never touch a foreign tile chunk");
    }

    @Test
    void aChangeTheNativeWriterAlreadyConsumedNeedsNoRebuild() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        theRegion().getChunk(0, 0).changed = false; // native bottom-neighbor consumption
        pumpIdleWindow();
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")));
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void owedRebuildsFlushEvenAfterTheBridgeIsDisabledOrTheQueueEmpties() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        offer(68, 64); // queued, never committed
        this.enabled = false;
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "the live toggle still drops the backlog");
        assertTrue(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "a rebuild owed to an already-written tile chunk still runs — dropping it"
                        + " would leave written tiles invisible until a reload");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void sessionEndClearsOwedRebuilds() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.bridge.onSessionEnd();
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        pumpIdleWindow();
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")),
                "the old world's tile chunks are never touched again");
    }

    @Test
    void aDimensionChangeDropsTheOtherDimensionsOwedRebuilds() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        this.processor.mapWorld.currentDimensionId = NETHER;
        this.clientDimension = NETHER;
        pumpIdleWindow();
        assertEquals(1, this.bridge.counterForTest("dropped_updates"),
                "the pixel recipe reads the CURRENT world — a cross-dimension rebuild is wrong");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")));
    }

    @Test
    void rebuildFailuresCountTowardTheDeathLatch() {
        this.bridge.updateIdlePumps = 1;
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            offer(64 + 4 * i, 64); // five tile chunks of region (2,2)
        }
        this.bridge.pump();
        assertEquals(XaeroMapCompat.THROW_LATCH, this.bridge.counterForTest("written"));
        var region = theRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            region.getChunk(i, 0).updateBuffersThrows = true;
        }
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(this.bridge.deadForTest(), "a throwing rebuild is a commit-side failure");
        assertTrue(this.bridge.counterForTest("commit_failures") >= XaeroMapCompat.THROW_LATCH);
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
    void aZeroBudgetPumpStillMakesProgressEveryPump() {
        // The live-lock MAJOR (3-Opus fold): the old pre-walk ran OUTSIDE the nanos
        // budget, so a broke pump could do literally nothing while retaining the
        // whole queue, forever. The budget check is now skipped until one unit of
        // progress (a drop or a commit attempt) — a zero budget commits exactly
        // ONE entry per pump, still grants probed waiting regions, and the queue
        // always drains.
        offer(0, 0);   // committable bucket (auto-created loaded region)
        offer(4, 0);   // same bucket
        offer(64, 64); // waiting bucket — region (2,2)
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.bridge.pumpNanosBudget = 0;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "a zero budget must still commit exactly one entry (progress guarantee)");
        for (int i = 0; i < 8 && this.bridge.queuedForTest() > 1; i++) {
            this.bridge.pump();
        }
        assertEquals(2, this.bridge.counterForTest("written"),
                "over pumps the zero-budget drain must empty the committable bucket");
        assertEquals(1, this.bridge.queuedForTest(), "only the awaiting entry remains");
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "the waiting region must be granted its load despite the broke budget");
    }

    @Test
    void aBusyTileChunkDefersOnlyItsOwnEntriesAndSiblingsCommit() {
        // Three entries in ONE region, distinct tile chunks; the first entry's tile
        // chunk is PBO-downloading. The old region-wide deferral starved (and after
        // DEFER_CAP pumps EXPIRED) whole buckets over one busy tile chunk (3-Opus
        // fold MAJOR); tile-chunk-scoped deferral lets the siblings commit now,
        // and the busy entry expires ALONE at its own cap.
        offer(0, 0);  // tileChunk (0,0) — armed busy
        offer(4, 0);  // tileChunk (1,0)
        offer(8, 0);  // tileChunk (2,0)
        var region = new MapRegion();
        var busy = new MapTileChunk(region, 0, 0);
        busy.loadState = 2;
        busy.leafTexture.downloadFromPBO = true;
        region.setChunk(0, 0, busy);
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"),
                "siblings in other tile chunks must commit past the busy one");
        assertEquals(1, this.bridge.queuedForTest());
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 2; i++) {
            this.bridge.pump();
        }
        assertEquals(0, this.bridge.queuedForTest(),
                "the busy entry expires at its own deferral cap");
        assertEquals(1, this.bridge.counterForTest("dropped_expired"),
                "…counted exactly once (removal-guarded counting)");
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
                && line.contains(", regions_waiting=") && line.contains(", buffer_updates=")
                && line.contains(", pending_updates=") && line.contains(", dropped_updates="), line);
        this.enabled = false;
        assertTrue(this.bridge.describe().contains("state=disabled"));
    }
}
