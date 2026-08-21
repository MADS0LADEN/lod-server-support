package dev.vox.lss.paper;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The v0.12.0 B.0 wiring pins (v0.12.0-release-plan.md Phase B.0): the P1 header
 * freshness rung's PAPER wiring had no gate on any support line while living in the
 * single most-drifted file of the backport — a conflict resolution that drops the
 * {@code attachRegionStamps} call or mis-roots the region-dir resolver ships a line
 * where the headline warm-rejoin feature is silently dead (every doubt shape degrades
 * fail-safe to NEVER_CLEAN — no crash, no log storm). The 1.21.1 line has ALREADY
 * shipped the resolver half of that bug once (world/DIM-1 does not exist under the
 * unified layout — its own port comment records it).
 *
 * <p>PORTS: the resolver pin's EXPECTATIONS are per-line (surfaces row 17): this
 * line (26.x unified layout) expects {@code getStorageFolder} under the server
 * worldRoot; the 1.21.x lines expect the per-level {@code getWorldFolder()} split
 * roots. Adapt the expected paths WITH the resolver — a port that leaves either
 * behind reds here.
 */
class PaperRegionFreshnessWiringTest {

    @org.junit.jupiter.api.BeforeAll
    static void setup() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static ServerLevel level(ResourceKey<Level> key) {
        var l = mock(ServerLevel.class);
        when(l.dimension()).thenReturn(key);
        return l;
    }

    @Test
    void resolverMapsAllThreeVanillaDimensionsToTheirRegionDirs() throws Exception {
        Path root = Files.createTempDirectory("lss-resolver-pin");
        var server = mock(MinecraftServer.class);
        var overworld = level(Level.OVERWORLD);
        var nether = level(Level.NETHER);
        var end = level(Level.END);
        when(server.getAllLevels()).thenReturn(List.of(overworld, nether, end));

        var dirs = PaperRequestProcessingService.resolveRegionDirs(server, root);

        assertEquals(3, dirs.size(), "all three vanilla dimensions must resolve");
        Path ow = dirs.get("minecraft:overworld");
        Path ne = dirs.get("minecraft:the_nether");
        Path en = dirs.get("minecraft:the_end");
        assertNotNull(ow, "overworld resolved");
        assertNotNull(ne, "nether resolved — the shipped-once failure shape was a"
                + " nether/end dir that does not exist, silently degrading every"
                + " non-overworld dim to NEVER_CLEAN");
        assertNotNull(en, "end resolved");
        // 26.x unified layout (row 17): EVERY dimension, overworld included, lives
        // under dimensions/minecraft/<dim>/region (verified against the real
        // getStorageFolder at pin-writing time — the classic root/region overworld is
        // a PRE-26 shape). PORTS: on the 1.21.x split-dir lines these expectations
        // change WITH the resolver form — see the class javadoc.
        assertEquals(root.resolve("dimensions/minecraft/overworld/region").normalize(), ow,
                "overworld = worldRoot/dimensions/minecraft/overworld/region on 26.x");
        assertTrue(ne.toString().endsWith("region"), "nether path targets a region dir: " + ne);
        assertTrue(en.toString().endsWith("region"), "end path targets a region dir: " + en);
        assertEquals(3, java.util.Set.of(ow, ne, en).size(),
                "the three dimensions' region dirs must be DISTINCT — a resolver that"
                + " collapses them serves cross-dimension freshness claims");
        // The vanilla storage-folder derivation must keep nether/end under the root
        // (the unified layout's invariant — a resolver re-rooted per-level on this
        // line would escape the worldRoot, the R2-9 probe's failure shape inverted).
        assertTrue(ne.startsWith(root), "nether dir under the world root: " + ne);
        assertTrue(en.startsWith(root), "end dir under the world root: " + en);
    }

    @Test
    void exoticDimensionDegradesThatDimensionOnlyNeverServiceStart() {
        var server = mock(MinecraftServer.class);
        var overworld = level(Level.OVERWORLD);
        var exotic = mock(ServerLevel.class);
        when(exotic.dimension()).thenThrow(new IllegalStateException("exotic dimension"));
        when(server.getAllLevels()).thenReturn(List.of(overworld, exotic));

        var dirs = assertDoesNotThrow(() -> PaperRequestProcessingService
                        .resolveRegionDirs(server, Path.of("root")),
                "the per-level belt: one exotic dimension must never take down start");
        assertNotNull(dirs.get("minecraft:overworld"), "the healthy dimension still resolves");
    }

    /** The ATTACH half (source-scan — the contract-test idiom; the production wiring
     *  builder needs a full NMS server, and the test Wiring ctor deliberately
     *  bypasses it): the stamp table must be constructed from the resolver and
     *  attached to the disk reader UNCONDITIONALLY (outside any store branch — the
     *  rung is load-bearing store-LESS), and the dirty tracker's mark listener must
     *  bump the region latch. A port that drops any of the three lines ships the
     *  silent-dead-feature shape this test exists for. */
    @Test
    void wiringSourceCarriesTheAttachAndTheMarkListenerBump() throws Exception {
        Path src = Path.of("src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        if (!Files.exists(src)) {
            src = Path.of("paper").resolve(src);
        }
        String body = Files.readString(src);

        int resolve = body.indexOf("resolveRegionDirs(server, worldRoot)");
        int attach = body.indexOf("diskReader.attachRegionStamps(regionStamps)");
        int bump = body.indexOf(".bumpLiveSaveMark(");
        int storeBranch = body.indexOf("if (storeMode != dev.vox.lss.common.store.LodStoreMode.OFF)");
        assertTrue(resolve > 0, "the wiring builder must construct the region dirs via"
                + " resolveRegionDirs (the extracted, per-line-swappable site)");
        assertTrue(attach > 0, "the disk reader must get attachRegionStamps — without it"
                + " the header rung is silently dead (disk.header_hits frozen at 0)");
        assertTrue(bump > 0, "the mark listener must bump the region live-save latch");
        assertTrue(storeBranch > 0, "census anchor: the store-mode branch exists");
        assertTrue(attach < storeBranch, "attachRegionStamps must sit BEFORE (outside)"
                + " the store branch — the rung is load-bearing on store-LESS servers"
                + " (the compiled store default is off)");
        assertTrue(bump < storeBranch, "the latch bump wiring must also be store-independent");
    }
}
