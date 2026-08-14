package dev.vox.lss.common.store;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Factory for the LOD store (both platform services call here). {@code lodStore} has
 * exactly two config values: {@code off} — the caller never calls this — and
 * {@code full}, the SQLite store. Every failure degrades with one warning, never a
 * crash: null (store-off) when the codec native can't load OR when SQLite can't init
 * (the in-memory degrade tier is deleted — see below).
 *
 * <p><b>The memory tier was DELETED from {@code full} mode</b> (the Phase 2
 * memory-vs-SQLite A/B the plan pre-authorized, 2026-07-31 — evidence in
 * docs/planning/lod-store-progress.md): a front tier bought 5 µs vs ~100 µs on warm
 * hits — both invisible next to the ~2.4 ms NBT path it replaces and the client's
 * network RTT — while costing a SECOND zstd compression of every deposited column
 * (the cold-path gate measured the tiered composition at +14.6% whole-JVM CPU/col,
 * ceiling +10%), a 64 MB RAM budget, eviction machinery, and real composition
 * complexity (the resweep→memory staleness fan-out). SQLite-alone keeps the entire
 * cross-restart value at half the deposit cost.
 *
 * <p><b>{@code lodStore=memory} was then retired as a config value too</b> (2026-08-02,
 * user decision): the same arithmetic that killed the front tier condemns the mode —
 * at 64 MB and ~5.3 KB/col it resides ~12.5k columns, ~6% of one player's disc at
 * lodDistanceChunks=256, under random eviction, while paying a zstd compression on
 * every single deposit. The class itself was DELETED 2026-08-13 (user decision, the
 * deletion review's #1): its last reachable role was the SQLite-init degrade, and on a
 * box whose disk store just failed to open, degrading one rung further to store-off
 * loses only warm-join serving — disk reads still serve everything.
 */
public final class LodStores {

    /**
     * Brand-preferred LOD-store directory under the world root ({@code <brand>-lod}),
     * adopting the OTHER brand's existing directory when the preferred one is absent —
     * a jar swap keeps its (multi-GB, rebuildable-but-expensive) store the same way the
     * configs adopt their cross-brand file. (XANTHA's v0.10.0 VSS release patch used a
     * bare brand resolve; the adoption arm is the swap-continuity addition.)
     */
    public static Path brandedStoreDir(Path worldRoot) {
        boolean vss = "VSS".equalsIgnoreCase(Brand.shortName());
        Path preferred = worldRoot.resolve((vss ? "vss" : "lss") + "-lod");
        Path other = worldRoot.resolve((vss ? "lss" : "vss") + "-lod");
        if (!Files.isDirectory(preferred) && Files.isDirectory(other)) return other;
        return preferred;
    }

    private LodStores() {}

    /** Null = run without a store (the codec-probe AND SQLite-init degrades; caller
     *  each cause logs its OWN warn here — final-review A-M1: the caller used to warn
     *  "codec native cannot load" for BOTH causes, sending an admin whose SQLite merely
     *  failed to open chasing a phantom zstd problem). */
    public static LodStoreService createOrNull(SqliteLodStore.Environment env) {
        StoreCodec codec = StoreCodec.zstdOrNull();
        if (codec == null) {
            LSSLogger.warn("lodStore requested but the " + StoreCodec.NAME + " codec native"
                    + " cannot load on this platform — running WITHOUT the LOD store");
            return null;
        }
        var diag = new LodStoreDiagnostics();
        SqliteLodStore sqlite = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, diag);
        if (sqlite == null) {
            // The in-memory degrade tier was DELETED 2026-08-13: running store-less on a
            // box whose SQLite just failed is the honest state (the diag token reads
            // store=unavailable — what is actually running), and disk reads serve
            // everything.
            LSSLogger.warn("lodStore=on requested but the SQLite store is unavailable —"
                    + " running WITHOUT a store (disk reads serve everything; warm-join"
                    + " acceleration is off until the store can open)");
            return null;
        }
        // The store defaults ON (2026-08-08 rework), so its disk cost now lands on servers
        // that never asked for it. Say so once, at the point it becomes true, rather than
        // leaving admins to discover a doubled world folder — a changelog line does not
        // reach someone who upgraded through a host panel.
        String storeDirName = Brand.lowerShortName() + "-lod";
        LSSLogger.info("LOD store active (lodStore=on). It stores served LOD bytes under"
                + " <world>/" + storeDirName + "/ and, once fully warmed, occupies roughly"
                + " as much space as the region files themselves. It is DERIVED data —"
                + " deleting " + storeDirName + "/ is always safe. Set lodStore=off to"
                + " disable, lodStoreMaxMB to bound it.");
        return sqlite;
    }

    /**
     * One-line startup recommendation, emitted by both platforms when the store is OFF and
     * LSS itself is enabled. Under the 2026-08-08 split default (fresh installs generate
     * "on"; a key absent from an existing file means "off") this line reaches explicit
     * opt-outs AND every upgraded install whose file lacks the key — for the latter it is
     * the one place the feature and its disk tradeoff reach the admin at all. Returns the
     * message rather than logging so the decision is pinnable; callers log INFO. Null when
     * LSS is disabled (nothing to recommend into) and on Folia — the store is unvalidated
     * there and {@code PaperConfig.validate()} WARNS on an armed store; recommending what
     * we warn about is incoherent.
     */
    public static String offRecommendationOrNull(boolean lssEnabled, boolean isFolia) {
        if (!lssEnabled || isFolia) return null;
        return "LOD store is off. Recommended: set \"lodStore\": \"on\" in"
                + " " + Brand.lowerShortName() + "-server-config.json for much faster LOD"
                + " serving; the tradeoff is it"
                + " roughly doubles the size of your world directory.";
    }
}
