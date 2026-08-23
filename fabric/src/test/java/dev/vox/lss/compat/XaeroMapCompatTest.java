package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnConsumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The Xaero map bridge against real-package-name stubs (xaero-map-bridge-plan.md §3;
 * the {@code MoonriseReadCompatTest} stub discipline — the stubs under
 * {@code fabric/src/test/java/xaero/map} mirror exactly the public surface
 * {@code XaeroMapCompat.Handles} resolves, so a resolve regression fails HERE, not
 * on a live client). Pins:
 * <ul>
 *   <li>resolve is all-or-nothing and fail-soft;</li>
 *   <li>the pump mirrors the native writer's gate ladder — every not-ready gate
 *       DEFERS (entries retained), a stale-dimension entry DROPS, a loaded chunk
 *       SKIPS;</li>
 *   <li>the decompiled commit sequence order, incl. worldInterpretationVersion
 *       before setTile, the flag-then-consume buffers pattern (no updateBuffers
 *       handle exists at all), and {@code setBeingWritten} set-and-NEVER-cleared
 *       (the save path owns the reset — clearing it silently loses tiles);</li>
 *   <li>the mandatory paced requestLoad dance for unloaded regions, with
 *       setBeingWritten BEFORE requestLoad and awaiting-load deferrals exempt
 *       from the deferral cap;</li>
 *   <li>queue policy (latest-wins, bounded, config-off clear) and the
 *       consecutive-failure death latch;</li>
 *   <li>the consumer contract: a throwing extraction NEVER escapes (an escape
 *       would trigger reportIngestFailure and re-serve columns for a map
 *       problem).</li>
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
    private final List<VoxelColumnConsumer> registered = new ArrayList<>();
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
        this.registered.clear();
        this.bridge = new XaeroMapCompat(
                XaeroMapCompat.Handles.resolve(Class::forName),
                this.fakeLevelOps,
                () -> this.enabled,
                this.registered::add,
                this.registered::remove);
        this.bridge.pumpNanosBudget = Long.MAX_VALUE; // neutralize MethodHandle warmup
        this.bridge.reconcileRegistration();
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

    // ---- resolve ----

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

    // ---- registration / the live toggle ----

    @Test
    void registrationFollowsTheToggleAndDeath() {
        assertEquals(1, this.registered.size(), "enabled at init registers the consumer");
        this.enabled = false;
        this.bridge.pump();
        assertTrue(this.registered.isEmpty(),
                "disabling deregisters (the capability bit must not be held for a dead map)");
        this.enabled = true;
        this.bridge.pump();
        assertEquals(1, this.registered.size(), "re-enabling re-registers");
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
    void latestWinsPerPositionAndBoundedOverflowDropsOldest() {
        offer(5, 5);
        offer(5, 5);
        assertEquals(1, this.bridge.queuedForTest(), "same position coalesces latest-wins");
        for (int i = 0; i < XaeroMapCompat.MAX_QUEUE + 10; i++) {
            offer(1000 + i, 0);
        }
        assertEquals(XaeroMapCompat.MAX_QUEUE, this.bridge.queuedForTest());
        assertTrue(this.bridge.counterForTest("dropped") >= 10, "overflow drops are counted");
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
            assertTrue(XaeroStubEvents.snapshot().isEmpty(),
                    c.name() + ": a not-ready ladder must not touch region/tile state");
            c.disarm().run();
        }
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "ladder ready again: the entry commits");
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void staleDimensionEntriesDropAtThePump() {
        this.bridge.offerPrepared(NETHER, tile(3, 3));
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.bridge.counterForTest("dropped"));
        assertEquals(0, this.bridge.counterForTest("written"));
    }

    @Test
    void loadedChunksAreSkippedNotWritten() {
        offer(7, 9);
        this.loadedChunks.add(((long) 7 << 32) | 9L);
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.bridge.counterForTest("skipped_loaded"));
        assertEquals(0, this.bridge.counterForTest("written"));
    }

    // ---- the region load dance ----

    @Test
    void unloadedRegionGetsExactlyOnePacedLoadRequestWithBeingWrittenFirst() {
        offer(64, 64); // region (2,2), fresh
        var region = new MapRegion();
        region.loadState = 0;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "an unloaded region must be load-REQUESTED (fresh regions never self-promote)");
        assertEquals(1, this.bridge.counterForTest("load_requests"));
        var events = XaeroStubEvents.snapshot();
        int setBeingWritten = events.indexOf("region.setBeingWritten true");
        int requestLoad = events.indexOf("saveLoad.requestLoad lss-xaero-bridge");
        assertTrue(setBeingWritten >= 0 && requestLoad > setBeingWritten,
                "setBeingWritten(true) must precede requestLoad (it stops the load drain"
                        + " demoting an empty fresh region): " + events);
        assertTrue(events.contains("saveLoad.setNextToLoadByViewing"),
                "the request must register with the pacing gauge");
        assertEquals(1, this.bridge.queuedForTest(), "awaiting-load entries stay queued");

        // Pacing: while the gauge refuses, further pumps issue NO second request…
        this.processor.saveLoad.nextToLoadByViewing.allowAnotherRegionToLoad = false;
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 50; i++) {
            this.bridge.pump();
        }
        assertEquals(1, this.processor.saveLoad.loadRequests.size(), "paced: one request total");
        // …and awaiting-load deferrals are EXEMPT from the deferral cap.
        assertEquals(1, this.bridge.queuedForTest(),
                "an entry awaiting a region load must never be dropped by the deferral cap");

        // The load lands: the next pump commits.
        region.loadState = 2;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.queuedForTest());
    }

    @Test
    void twoUnloadedRegionsShareTheOneRequestPerPumpPacing() {
        offer(64, 64);   // region (2,2)
        offer(320, 320); // region (10,10)
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.processor.regions.put((10L << 32) | 10L, unloadedRegion());
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "at most ONE requestLoad per pump pass (the native ~1/pass pacing)");
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

        // setBeingWritten is set and NEVER cleared by the bridge — the save path owns
        // the reset; a false here means tiles silently never persist.
        assertTrue(events.contains("region.setBeingWritten true"));
        assertFalse(events.contains("region.setBeingWritten false"),
                "the bridge must NEVER clear setBeingWritten: " + events);

        // Created tile chunk: loadState 2 + region cache invalidated + terrain marked
        // + highlights prepared (the native createdTileChunk block).
        assertTrue(events.contains("tileChunk.setLoadState 2"));
        assertTrue(events.contains("region.setAllCachePrepared false"));
        assertTrue(events.contains("tileChunk.setHasHadTerrain"));
        assertTrue(events.contains("highlights.prepare"));

        // Order: worldInterpretationVersion → writtenCave → setTile → writtenOnce → loaded
        // (setTile's tileWasLoadedWithTopHeightValues branch reads the version).
        int version = events.indexOf("tile.setWorldInterpretationVersion 1");
        int cave = events.indexOf("tile.setWrittenCave");
        int setTile = events.indexOf("tileChunk.setTile 0,1");
        int writtenOnce = events.indexOf("tile.setWrittenOnce true");
        int loaded = events.indexOf("tile.setLoaded true");
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
        prepared.floorState()[0] = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        prepared.floorY()[0] = 63;
        prepared.topY()[0] = 66;
        prepared.light()[0] = 7;
        prepared.overlays()[0] = new XaeroTileExtractor.OverlayRun[]{
                new XaeroTileExtractor.OverlayRun(
                        net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),
                        (byte) 3, false, 3)};
        this.bridge.offerPrepared(OVERWORLD, prepared);
        this.bridge.pump();
        var region = this.processor.regions.values().iterator().next();
        MapTileChunk tileChunk = region.getChunk(1, 1); // tileChunk (1,1) for chunk (4,4)
        assertNotNull(tileChunk);
        var mapTile = tileChunk.getTile(0, 0);
        assertNotNull(mapTile);
        var block = mapTile.blocks[0][0];
        assertEquals(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), block.state);
        assertEquals(63, block.height);
        assertEquals(66, block.topHeight);
        assertEquals(7, block.light);
        assertEquals(-64, block.preparedBottomY, "prepareForWriting must run before write");
        assertFalse(block.cave, "surface layer writes cave=false");
        assertEquals(1, block.overlays.size());
        assertEquals(3, block.overlays.get(0).opacity);
        assertTrue(this.processor.overlayManager.internCalls >= 1,
                "overlays are interned through OverlayManager.getOriginal");
        assertEquals(Integer.MAX_VALUE, mapTile.writtenCaveStart, "surface cave sentinel");
        // Pixel (0,1) had no data: the void erase shape (AIR at world bottom).
        var voidBlock = mapTile.blocks[0][1];
        assertNull(voidBlock.state, "unset prepared pixels write null state via write()");
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
    void busyRegionDefersAndTheCapEventuallyDrops() {
        offer(8, 8);
        var region = new MapRegion();
        region.resting = false; // ladder-ready but the region is busy
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest());
        assertTrue(this.bridge.counterForTest("deferred") >= 1);
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 2; i++) {
            this.bridge.pump();
        }
        assertEquals(0, this.bridge.queuedForTest(),
                "a permanently-busy region eventually drops the entry (bounded deferral)");
        assertTrue(this.bridge.counterForTest("dropped") >= 1);
    }

    // ---- failure containment ----

    @Test
    void fiveConsecutiveCommitFailuresLatchTheBridgeDead() {
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            offer(i * 4, 64);
        }
        // All eight entries land in region (0,2): pre-create it with an ARMED (throwing)
        // tile chunk at every entry's local slot, so each commit attempt fails.
        var region = new MapRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 16 & 7, tileChunk);
        }
        this.processor.regions.put(2L, region); // key (regionX=0)<<32 | regionZ=2
        for (int i = 0; i < 3; i++) {
            this.bridge.pump();
            if (this.bridge.deadForTest()) break;
        }
        assertTrue(this.bridge.deadForTest(),
                "consecutive commit failures must latch the bridge dead");
        assertEquals(0, this.bridge.queuedForTest(), "death clears the queue");
        this.bridge.pump();
        assertTrue(this.registered.isEmpty(), "a dead bridge deregisters its consumer");
    }

    @Test
    void aThrowingExtractionNeverEscapesTheConsumer() {
        var consumer = this.registered.get(0);
        // Null column data NPEs inside extraction — the consumer must swallow it
        // (an escape would be treated as an ingest failure and re-serve the column).
        assertDoesNotThrow(() -> consumer.onVoxelColumnReceived(null, OVERWORLD, 0, 0, null));
    }
}
