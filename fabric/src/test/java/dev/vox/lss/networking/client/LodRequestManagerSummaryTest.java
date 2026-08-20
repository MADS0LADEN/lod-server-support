package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.region.RegionSummaryWire;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The manager half of region-summary sync (region-summary-sync-plan.md §6): the
 * at-entry request (both dimension-entry sites, correct center/radius, fire-and-forget),
 * its three gates (client kill switch, harness property gate, CURRENT dialect), and the
 * S2C frame ladder — apply-side kill switch, dimension binding, malformed containment,
 * the buffered apply behind an in-flight cache load (latest wins, failure re-applies
 * FIRST), and the attributability counters. The per-column validation semantics live in
 * {@link ColumnStateMapTest}'s tile-validation section.
 */
class LodRequestManagerSummaryTest {

    private static final long POS = PositionUtil.packPosition(10, -3);
    private static final int POS_TILE_X = 10 >> 5;   // 0
    private static final int POS_TILE_Z = -3 >> 5;   // -1

    private LodRequestManager manager;
    private final List<byte[]> requests = new ArrayList<>();

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        manager = new LodRequestManager();
        manager.joinSlowStartEnabled = () -> false;
        manager.onSessionConfig(new SessionConfigS2CPayload(
                LSSConstants.PROTOCOL_VERSION, true, 2, true),
                "lss-summary-test-" + System.nanoTime());
        requests.clear();
        manager.setSummarySenderForTest(requests::add);
        manager.summaryHarnessGate = () -> false;
        manager.summarySessionVersion = () -> LSSConstants.PROTOCOL_VERSION;
        manager.setBatchSenderForTest(p -> { });
    }

    @AfterEach
    void restoreConfig() {
        LSSClientConfig.CONFIG.enableRegionSummarySync = true;
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    private void tick(int cx, int cz, ResourceKey<Level> d) {
        manager.tickWithContext(cx, cz, d, 0, 0, 0L, -1, () -> 0);
    }

    /** A radius-0 frame for POS's tile carrying one stamp. */
    private static byte[] frame(String dimension, long stamp) {
        return RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                dimension, POS_TILE_X, POS_TILE_Z, 0, new long[]{stamp}));
    }

    // ---- the at-entry request ----

    @Test
    void initialEntryAndDimensionChangeBothFireTheRequest() {
        var overworld = dim("overworld");
        tick(100, -200, overworld); // first tick = the initial-load site
        assertEquals(1, requests.size(), "the initial dimension entry requests a summary");
        var req = RegionSummaryWire.decodeRequest(requests.get(0));
        assertEquals("lss_test:overworld", req.dimension());
        assertEquals(100 >> 5, req.centerTileX(), "center = the player's own tile");
        assertEquals(-200 >> 5, req.centerTileZ());
        assertEquals((2 + 31) / 32 + 1, req.tileRadius(),
                "radius = ceil(effective lod distance / 32) + 1");

        tick(100, -200, overworld);
        assertEquals(1, requests.size(), "same dimension = no re-request");

        tick(8, 8, dim("the_end")); // the dimension-change site
        assertEquals(2, requests.size(), "a dimension change re-requests");
        assertEquals("lss_test:the_end",
                RegionSummaryWire.decodeRequest(requests.get(1)).dimension());
    }

    @Test
    void allThreeRequestGatesHold() {
        LSSClientConfig.CONFIG.enableRegionSummarySync = false;
        tick(0, 0, dim("overworld"));
        assertTrue(requests.isEmpty(), "the client kill switch stops the request");

        setUp(); // fresh manager
        manager.summaryHarnessGate = () -> true;
        tick(0, 0, dim("overworld"));
        assertTrue(requests.isEmpty(),
                "harness clients never request — no soak baseline can shift");

        setUp();
        manager.summarySessionVersion = () -> 18;
        tick(0, 0, dim("overworld"));
        assertTrue(requests.isEmpty(), "legacy dialects never request (CURRENT only)");
    }

    @Test
    void aThrowingSenderIsContained() {
        manager.setSummarySenderForTest(body -> {
            throw new IllegalStateException("channel gone");
        });
        assertDoesNotThrow(() -> tick(0, 0, dim("overworld")),
                "fire-and-forget: a send failure is today's behavior, never a tick error");
    }

    // ---- the S2C frame ladder ----

    private void seedStamped(ResourceKey<Level> d, long stamp) {
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(d);
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, stamp);
        manager.columnsForTest().loadFrom(loaded);
    }

    @Test
    void aMatchingFrameValidatesAndCounts() {
        var overworld = dim("overworld");
        seedStamped(overworld, 7000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(1, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesClean());
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(POS));
    }

    @Test
    void staleResidueCountsTheTileStale() {
        seedStamped(dim("overworld"), 5000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesStale());
        assertEquals(5000L, manager.columnsForTest().classify(POS), "residue re-declares");
    }

    @Test
    void neverCleanTilesCountUnknownAndValidateNothing() {
        seedStamped(dim("overworld"), 5000L);
        manager.onRegionSummaryFrame(frame("lss_test:overworld",
                RegionSummaryWire.STAMP_NEVER_CLEAN));
        assertEquals(0, manager.getSummaryColumnsValidated());
        assertEquals(1, manager.getSummaryTilesUnknown());
        assertEquals(5000L, manager.columnsForTest().classify(POS));
    }

    @Test
    void frameForAnotherDimensionDrops() {
        seedStamped(dim("overworld"), 7000L);
        manager.onRegionSummaryFrame(frame("lss_test:the_end", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "the dimension echo is the entire anti-stale binding");
        assertEquals(7000L, manager.columnsForTest().classify(POS));
    }

    @Test
    void malformedFrameAndApplyKillSwitchDropContained() {
        seedStamped(dim("overworld"), 7000L);
        assertDoesNotThrow(() -> manager.onRegionSummaryFrame(new byte[]{7, 7, 7}));
        assertEquals(0, manager.getSummaryColumnsValidated());

        LSSClientConfig.CONFIG.enableRegionSummarySync = false;
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "never request, never apply — a mid-session flip stops both halves");
    }

    // ---- buffered apply behind the cache load ----

    @Test
    void frameRacingTheCacheLoadBuffersAndAppliesAfterTheLoad() {
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "applied against the empty pre-load map it would validate nothing —"
                        + " must buffer");

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(1, manager.getSummaryColumnsValidated(),
                "the buffered frame applies right after adoptLoaded");
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(POS));
    }

    @Test
    void bufferedFramesAreLatestWins() {
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onRegionSummaryFrame(frame("lss_test:overworld", 9999L)); // would validate 0
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L)); // validates POS

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(1, manager.getSummaryColumnsValidated(), "only the LATEST frame applied");
        assertEquals(1, manager.getSummaryTilesClean() + manager.getSummaryTilesStale()
                + manager.getSummaryTilesUnknown(), "exactly one frame's tiles counted");
    }

    @Test
    void anIngestFailureDuringTheLoadReappliesBeforeTheBufferedFrame() {
        // The sealed-failure hazard: the rejection unstamps AFTER the load lands and
        // BEFORE the frame applies, so the frame finds no candidate bit — the failed
        // column re-declares instead of being validated off its stale loaded stamp.
        var overworld = dim("overworld");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));
        manager.onIngestFailure(overworld, POS);

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "the frame must not seal a rejected column validated");
        assertEquals(-1L, manager.columnsForTest().classify(POS),
                "the rejected column re-declares as a first serve");
    }

    @Test
    void aDimensionChangeInvalidatesTheBufferedFrame() {
        var overworld = dim("overworld");
        var end = dim("the_end");
        manager.markCacheLoadedForTest();
        manager.setLastDimensionForTest(overworld);
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);
        manager.onRegionSummaryFrame(frame("lss_test:overworld", 6000L));

        tick(0, 0, end); // dimension change — the stale buffered frame must die

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        manager.setPendingCacheLoadForTest(CompletableFuture.completedFuture(loaded));
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(0, manager.getSummaryColumnsValidated(),
                "a frame buffered for the OLD dimension must never validate the new one");
    }
}
