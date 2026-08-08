package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.config.JsonConfig;
import net.fabricmc.loader.api.FabricLoader;

public class LSSClientConfig extends JsonConfig {
    // Brand-primary first, other brand as fallback: the VSS jar (Brand.shortName()=="VSS") prefers
    // vss-client-config.json but adopts an existing lss-client-config.json; the LSS jar is the
    // mirror. So a fresh install gets the brand's own file, and an LSS<->VSS jar swap keeps its
    // config. Brand is loaded by the entrypoint before this class is first touched, so it reads
    // the correct brand for the running jar. See JsonConfig.brandedConfigCandidates.
    private static final String[] CANDIDATES = brandedConfigCandidates("client");

    public static LSSClientConfig CONFIG =
            load(LSSClientConfig.class, CANDIDATES, FabricLoader.getInstance().getConfigDir());

    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;
    // The §3 unknown-identity fallback ladder's TERMINAL default (protocol 20,
    // cross-version-identity-encoding-plan.md): a v20 identity this client's registries
    // don't know renders as this block. Never air — air punches holes in distant
    // terrain, so an air-resolving value is coerced to stone at resolve time.
    public String unknownBlockFallback = "minecraft:stone";
    // The ladder's CURATED rung: removed/renamed block NAMES mapped to visually-similar
    // local ones (ViaBackwards-diff style, e.g. "minecraft:sulfur" -> "minecraft:sandstone").
    // Ships EMPTY; user-extendable as real cross-version gaps are reported.
    public java.util.Map<String, String> crossVersionBlockFallbacks = new java.util.HashMap<>();
    // Backward compat with pre-v0.7.0 (protocol 16, v0.4.x–v0.6.2) SERVERS: if a server does
    // not answer the v18 handshake, re-handshake announcing version 16 and decode the legacy
    // wire. Default true (mirrors the server's enableV16Compat). Post-C3 this gates only the LADDER'S
    // 16 RUNG — a strict current-version client (no fallback at all) needs this AND
    // enableV19ServerCompat false. See docs/planning/v16-client-compat-design.md.
    public boolean enableV16ServerCompat = true;
    // Backward compat with protocol-19 (v0.9.x) SERVERS — the C3 discovery ladder's middle
    // rung (XVER §6): if a server does not answer the version-20 handshake within 5 s,
    // re-announce 19 before falling further to 16. A 19 session is today's decode retained
    // (source + codec bytes, native-id palettes against this client's own registry — same MC
    // version by construction). Default true; false skips straight to the v16 rung.
    public boolean enableV19ServerCompat = true;
    // Tier B of the same compat: on a v16 SERVER, drive on-demand GENERATION of cold columns
    // instead of load-only (Tier A). The egress rewrites a first-serve request to v16's generate
    // trigger, so the old server generates terrain the player has not visited. Default TRUE — this
    // is how a native protocol-16 client behaved (the old servers were built to have LOD clients
    // drive generation), so it is the faithful default and gives the full LOD experience. Verified
    // safe across the compat range: both v0.4.0 and v0.6.2 read disk-FIRST for the generate path
    // (already-generated terrain is served, not regenerated), and the transient-NOT_GENERATED heal
    // that keeps cold backfill filling is client-side (version-independent). Set false for
    // strict load-only (Tier A). No effect unless enableV16ServerCompat is also on, and never on a
    // v18 session. See docs/planning/v16-client-compat-design.md §4 (Tier B).
    public boolean enableV16Generation = true;
    // Adaptive scan cadence (docs/planning/adaptive-scan-cadence-design.md): when the
    // current want-set declaration is ≥95% answered and the pipeline is at most mildly
    // loaded, declare again after 250 ms instead of waiting out the full second (max 4 Hz;
    // the 1 Hz cadence stays as the fallback and remains the sole self-heal for silent
    // server-side drops). Removes the "map fills in one-second spurts" pattern against
    // fast/warm servers. Kill switch: false restores the fixed 1 Hz cadence everywhere.
    // No effect on legacy (protocol-16) server sessions — those always keep 1 Hz.
    public boolean enableAdaptiveScanCadence = true;
    // Ingest-pressure request pacing (issue #71, docs/planning/ingest-backpressure-design.md):
    // scale the want-set budget down — and halt declarations entirely at a threshold — when a
    // registered LOD consumer reports a pending ingest backlog (Voxy's ingest queue depth via
    // the reflective bridge). Keeps a weak client from being fed faster than it can absorb.
    // Kill switch: false restores pre-#71 pacing (decode-queue signal only). No effect when no
    // consumer reports a backlog.
    public boolean enableIngestBackpressure = true;
    // Manual column-rate cap (docs/planning/client-column-rate-cap-design.md): bounds how many
    // LOD columns per second the server streams to this client — the MANUAL override for the
    // cases the automatic #71 taper cannot see (pressure showing up as frame time/GC rather
    // than queue depth, or a consumer that never reports an ingest backlog). Denominated in
    // columns/sec, NOT want-set size: a raw budget clamp is compensated away by the adaptive
    // cadence (smaller batches complete sooner and fire faster — budget x cadence is the
    // sustained rate). Nonzero R clamps the want-set budget to min(WANT_SET_BUDGET, R) (bounds
    // the burst) AND spaces fast re-scans so each declared batch pre-pays its interval (bounds
    // the sustained rate at R/sec); the 1 Hz fallback — the want-set's only self-heal — is
    // never gated. Composes with the #71 taper by MIN. Below 776 the want-set no longer
    // dominates the server's worst-case in-flight set: the frontier advances behind its own
    // awaited positions across more scans — slower, never wedged; that is the dial doing its
    // job. On v16 server sessions only the budget clamp applies (no fast path there). Also a
    // Sodium settings slider ("Max LOD Download Rate"). 0 (and any non-positive value) = OFF,
    // bit-identical to pre-cap behavior.
    public int lodColumnsPerSecondLimit = 0;

    @Override
    protected String getFileName() {
        return CANDIDATES[0]; // brand-primary (creation target / default when not loaded via load())
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
        // GSON can null these from a malformed/hand-edited file — restore the defaults
        // (the resolver validates the fallback's RESOLUTION itself, config only carries it).
        if (unknownBlockFallback == null || unknownBlockFallback.isBlank()) {
            unknownBlockFallback = "minecraft:stone";
        }
        if (crossVersionBlockFallbacks == null) {
            crossVersionBlockFallbacks = new java.util.HashMap<>();
        }
        // <= 0 normalizes to 0 (off) — a negative hand-edit means "off", and clamping it to the
        // floor would silently ARM a cap the user meant to disable. Positive values clamp to
        // [50, 100000]: below 50 the scanner still functions but starves the frontier to a
        // near-wedge cadence for no plausible use; the ceiling is inert (the mechanism no-ops
        // above ~3200) but bounds what a typo can store.
        if (lodColumnsPerSecondLimit <= 0) {
            lodColumnsPerSecondLimit = 0;
        } else {
            lodColumnsPerSecondLimit = Math.clamp(lodColumnsPerSecondLimit, 50, 100_000);
        }
    }
}
