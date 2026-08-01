package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidationTest {

    // --- LSSServerConfig ---

    private LSSServerConfig serverConfig() {
        return new LSSServerConfig();
    }

    /** Fabric's resweep default is 0 by DESIGN (the save hook owns within-session
     *  freshness there) while Paper overrides to 300 (its unfired-event staleness
     *  bound, pinned in PaperConfigValidationTest) — this half keeps the pair from
     *  drifting together silently. */
    @Test
    void fabricDefaultsTheLodStoreResweepToZero() {
        assertEquals(0, serverConfig().lodStoreResweepSeconds);
    }

    @Test
    void lodDistanceChunksClamped() {
        var c = serverConfig();
        c.lodDistanceChunks = 0;
        c.validate();
        assertEquals(1, c.lodDistanceChunks);

        c.lodDistanceChunks = 99999;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);
    }

    @Test
    void bytesPerSecondLimitPerPlayerClamped() {
        var c = serverConfig();
        c.bytesPerSecondLimitPerPlayer = 100;
        c.validate();
        assertEquals(1024, c.bytesPerSecondLimitPerPlayer);

        c.bytesPerSecondLimitPerPlayer = 200_000_000;
        c.validate();
        assertEquals(104_857_600, c.bytesPerSecondLimitPerPlayer);
    }

    @Test
    void diskReaderThreadsClamped() {
        var c = serverConfig();
        c.diskReaderThreads = 0;
        c.validate();
        assertEquals(1, c.diskReaderThreads);

        c.diskReaderThreads = 100;
        c.validate();
        assertEquals(64, c.diskReaderThreads);
    }

    @Test
    void sendQueueLimitPerPlayerClamped() {
        var c = serverConfig();
        c.sendQueueLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.sendQueueLimitPerPlayer);

        c.sendQueueLimitPerPlayer = 999999;
        c.validate();
        assertEquals(100_000, c.sendQueueLimitPerPlayer);
    }

    @Test
    void bytesPerSecondLimitGlobalClamped() {
        var c = serverConfig();
        c.bytesPerSecondLimitGlobal = 100;
        c.validate();
        assertEquals(1024, c.bytesPerSecondLimitGlobal);

        c.bytesPerSecondLimitGlobal = 2_000_000_000;
        c.validate();
        assertEquals(1_073_741_824, c.bytesPerSecondLimitGlobal);
    }

    @Test
    void generationConcurrencyLimitGlobalClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitGlobal = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitGlobal);

        c.generationConcurrencyLimitGlobal = 999;
        c.validate();
        assertEquals(256, c.generationConcurrencyLimitGlobal);
    }

    @Test
    void generationTimeoutSecondsClamped() {
        var c = serverConfig();
        c.generationTimeoutSeconds = 0;
        c.validate();
        assertEquals(1, c.generationTimeoutSeconds);

        c.generationTimeoutSeconds = 9999;
        c.validate();
        assertEquals(600, c.generationTimeoutSeconds);
    }

    @Test
    void dirtyBroadcastIntervalSecondsClamped() {
        var c = serverConfig();
        c.dirtyBroadcastIntervalSeconds = 0;
        c.validate();
        assertEquals(1, c.dirtyBroadcastIntervalSeconds);

        c.dirtyBroadcastIntervalSeconds = 9999;
        c.validate();
        assertEquals(300, c.dirtyBroadcastIntervalSeconds);
    }

    @Test
    void generationConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitPerPlayer);

        // Plain per-field bound: the #28 cross-clamp is gone (no client budget derives
        // from this cap anymore; the successor invariant lives in WantSetBudgetInvariantTest).
        c.generationConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(LSSConstants.MAX_CONCURRENCY_LIMIT, c.generationConcurrencyLimitPerPlayer);
    }

    @Test
    void perDimensionTimestampCacheSizeMBClamped() {
        var c = serverConfig();
        c.perDimensionTimestampCacheSizeMB = 0;
        c.validate();
        assertEquals(1, c.perDimensionTimestampCacheSizeMB);

        c.perDimensionTimestampCacheSizeMB = 9999;
        c.validate();
        assertEquals(256, c.perDimensionTimestampCacheSizeMB);
    }

    /** The cap-behavior user decision (store-cap-behavior-plan.md §1): the store ships
     *  UNCAPPED — 0 means no size cap, and it is the default. A silent revert to a
     *  nonzero default would re-enter the backfill<->eviction treadmill. */
    @Test
    void lodStoreMaxMBDefaultsToUncappedZero() {
        assertEquals(0, serverConfig().lodStoreMaxMB);
    }

    /** The 0-or-64..32768 clamp: 0 (and negative nonsense) stays uncapped; a nonzero
     *  opt-in cap keeps the 64 floor (a tiny accidental cap would evict constantly). */
    @Test
    void lodStoreMaxMBZeroStaysUncappedAndNonzeroFloorsAt64() {
        var c = serverConfig();
        c.lodStoreMaxMB = 0;
        c.validate();
        assertEquals(0, c.lodStoreMaxMB);

        c.lodStoreMaxMB = -7;
        c.validate();
        assertEquals(0, c.lodStoreMaxMB, "negative nonsense must mean uncapped, not a 64 MB cap");

        c.lodStoreMaxMB = 1;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB);

        c.lodStoreMaxMB = 63;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB);

        c.lodStoreMaxMB = 999_999;
        c.validate();
        assertEquals(LSSConstants.MAX_LOD_STORE_MAX_MB, c.lodStoreMaxMB);
    }

    /** lodStoreMaxBytes(): the 0-semantics both platforms wire into the store env.
     *  Negatives map to uncapped too — the helper must be robust even on a config
     *  validate() never touched. */
    @Test
    void lodStoreMaxBytesMapsZeroAndNegativesToUncappedAndMBToBytes() {
        var c = serverConfig();
        c.lodStoreMaxMB = 0;
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        c.lodStoreMaxMB = -3;
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        c.lodStoreMaxMB = 100;
        assertEquals(100L * 1024 * 1024, c.lodStoreMaxBytes());
    }

    /** Drift guards for the backfill-tuning defaults (store-backfill-tuning-plan.md §3
     *  — the same shape as the resweep-default pins): 100 col/s pace, 45 ms MSPT gate. */
    @Test
    void backfillTuningDefaultsAre100ColumnsPerSecondAnd45MsCeiling() {
        assertEquals(100, serverConfig().lodStoreBackfillColumnsPerSecond);
        assertEquals(45, serverConfig().lodStoreBackfillTickCeilingMillis);
    }

    @Test
    void lodStoreBackfillColumnsPerSecondClamped() {
        var c = serverConfig();
        c.lodStoreBackfillColumnsPerSecond = 1;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_BACKFILL_CPS, c.lodStoreBackfillColumnsPerSecond);

        c.lodStoreBackfillColumnsPerSecond = 99999;
        c.validate();
        assertEquals(LSSConstants.MAX_LOD_STORE_BACKFILL_CPS, c.lodStoreBackfillColumnsPerSecond);
    }

    @Test
    void lodStoreBackfillTickCeilingMillisClamped() {
        var c = serverConfig();
        c.lodStoreBackfillTickCeilingMillis = 0;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_BACKFILL_TICK_CEILING_MS,
                c.lodStoreBackfillTickCeilingMillis);

        c.lodStoreBackfillTickCeilingMillis = 999;
        c.validate();
        assertEquals(LSSConstants.MAX_LOD_STORE_BACKFILL_TICK_CEILING_MS,
                c.lodStoreBackfillTickCeilingMillis);
    }

    // --- X-ray masking keys (docs/planning/antixray-compat-design.md §3) ---

    @Test
    void xrayObfuscationNormalizedToCanonicalTriState() {
        var c = serverConfig();
        c.xrayObfuscation = "ON";
        c.validate();
        assertEquals("on", c.xrayObfuscation);

        c.xrayObfuscation = " Off ";
        c.validate();
        assertEquals("off", c.xrayObfuscation);

        c.xrayObfuscation = "garbage";
        c.validate();
        assertEquals("auto", c.xrayObfuscation, "unknown values must normalize to auto");

        c.xrayObfuscation = null;
        c.validate();
        assertEquals("auto", c.xrayObfuscation);
    }

    @Test
    void xrayMaxBlockHeightClamped() {
        var c = serverConfig();
        c.xrayMaxBlockHeight = -99999;
        c.validate();
        assertEquals(LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT, c.xrayMaxBlockHeight);

        c.xrayMaxBlockHeight = 99999;
        c.validate();
        assertEquals(LSSConstants.MAX_XRAY_MAX_BLOCK_HEIGHT, c.xrayMaxBlockHeight);
    }

    @Test
    void xrayHiddenBlocksNullRestoresDefaultButEmptyIsRespected() {
        var c = serverConfig();
        c.xrayHiddenBlocks = null;
        c.validate();
        assertEquals(dev.vox.lss.common.config.ServerConfigBase.defaultXrayHiddenBlocks(),
                c.xrayHiddenBlocks, "malformed null must fail safe to the default list");

        c.xrayHiddenBlocks = List.of();
        c.validate();
        assertEquals(List.of(), c.xrayHiddenBlocks,
                "an explicit empty list means 'hide nothing' and must be respected");
    }

    // --- Reflective clamp sweep ---

    private static List<Field> numericServerConfigFields() {
        List<Field> fields = Arrays.stream(LSSServerConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType().isPrimitive() && f.getType() != boolean.class)
                .toList();
        // Guard against the sweep going vacuous if fields get refactored to non-public.
        // (10 since the syncOnLoadConcurrencyLimitPerPlayer knob became a constant.)
        assertTrue(fields.size() >= 10, "clamp sweep lost fields, found only: " + fields);
        assertTrue(fields.stream().anyMatch(f -> f.getName().equals("perDimensionTimestampCacheSizeMB")),
                "clamp sweep no longer sees perDimensionTimestampCacheSizeMB");
        return fields;
    }

    /**
     * Every numeric server-config field must be pulled back to a sane range by validate(),
     * even from int extremes. Auto-catches future fields added without a clamp — the named
     * tests above pin the exact bounds, this pins that bounds exist at all.
     */
    @Test
    void everyNumericServerFieldClampedAtIntExtremes() throws Exception {
        for (Field f : numericServerConfigFields()) {
            assertEquals(int.class, f.getType(),
                    f.getName() + ": extend the clamp sweep for non-int numeric fields");

            var c = serverConfig();
            f.setInt(c, Integer.MIN_VALUE);
            c.validate();
            // missMemoTtlSeconds and lodStoreResweepSeconds have a legal floor of 0
            // (each 0 is that feature's kill switch), as does lodStoreMaxMB (0 =
            // uncapped, the default); xrayMaxBlockHeight's floor is a world Y and
            // deliberately negative — every other numeric floor is >= 1.
            int floor = switch (f.getName()) {
                case "missMemoTtlSeconds", "lodStoreResweepSeconds", "lodStoreMaxMB" -> 0;
                case "xrayMaxBlockHeight" -> LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT;
                default -> 1;
            };
            assertTrue(f.getInt(c) >= floor,
                    f.getName() + " not clamped up from Integer.MIN_VALUE, still " + f.getInt(c));

            f.setInt(c, Integer.MAX_VALUE);
            c.validate();
            assertTrue(f.getInt(c) < Integer.MAX_VALUE,
                    f.getName() + " not clamped down from Integer.MAX_VALUE");
        }
    }

    /** Compiled defaults must already sit inside their clamp ranges: validate() may not move them. */
    @Test
    void defaultsSurviveValidateUnchanged() throws Exception {
        var validated = serverConfig();
        validated.validate();
        var pristine = serverConfig();
        for (Field f : LSSServerConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertEquals(f.get(pristine), f.get(validated),
                    "default for " + f.getName() + " is outside its clamp range");
        }
    }

    /** LOD reads yield to gameplay out of the box; false is the documented rollback. */
    @Test
    void backgroundReadPriorityDefaultsOn() {
        assertTrue(serverConfig().useBackgroundReadPriority,
                "background read priority must default on");
    }

    /** Disk serves transcode NBT straight to wire bytes out of the box; false is the
     *  documented rollback to the per-section object path (round 2, 2026-07-29). */
    @Test
    void nbtTranscodeDefaultsOn() {
        assertTrue(serverConfig().useNbtTranscode,
                "NBT transcode must default on");
    }

    // --- LSSClientConfig ---

    private LSSClientConfig clientConfig() {
        return new LSSClientConfig();
    }

    @Test
    void clientLodDistanceChunksClamped() {
        var c = clientConfig();
        c.lodDistanceChunks = -1;
        c.validate();
        assertEquals(0, c.lodDistanceChunks);

        c.lodDistanceChunks = 99999;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);
    }

    @Test
    void v16CompatFlagsDefaultOn() {
        // The whole point of the branch: a v0.7.0 client talks to a pre-v0.7.0 server out of the
        // box (compat), and drives generation on it (Tier B). A silent revert of either default
        // to false would otherwise pass CI green — this pins the shipped behavior.
        var c = clientConfig();
        assertTrue(c.enableV16ServerCompat, "v16 server backward-compat must default ON");
        assertTrue(c.enableV16Generation, "Tier B generation-drive must default ON");
    }

    @Test
    void ingestBackpressureDefaultsOn() {
        // Issue #71: the ingest-pressure pacing is the shipped protection for weak clients —
        // a silent default-off revert would pass CI green (no consumer reports in any tier).
        var c = clientConfig();
        assertTrue(c.enableIngestBackpressure, "ingest-pressure request pacing must default ON");
        c.validate();
        assertTrue(c.enableIngestBackpressure, "validate() must not touch the boolean");
    }

    @Test
    void adaptiveScanCadenceDefaultsOn() {
        // The adaptive cadence (docs/planning/adaptive-scan-cadence-design.md) ships ON —
        // a silent default-off revert would pass CI green (unit rigs set the seam
        // explicitly) while quietly restoring the 1 Hz spurt fill on every client.
        var c = clientConfig();
        assertTrue(c.enableAdaptiveScanCadence, "adaptive scan cadence must default ON");
        c.validate();
        assertTrue(c.enableAdaptiveScanCadence, "validate() must not touch the boolean");
    }

    @Test
    void adaptiveScanCadenceRoundTripsThroughJson() {
        // The GSON leg: the field serializes under its exact key (a rename would silently
        // orphan every saved kill-switch choice) and a saved false binds back as false.
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableAdaptiveScanCadence\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableAdaptiveScanCadence\":true", "\"enableAdaptiveScanCadence\":false"),
                LSSClientConfig.class);
        assertFalse(loaded.enableAdaptiveScanCadence, "a saved false must bind back as false");
    }

    @Test
    void ingestBackpressureRoundTripsThroughJson() {
        // The GSON leg of the save/load contract: the field serializes under its exact key
        // (a rename would silently orphan every saved kill-switch choice) and a saved false
        // binds back as false.
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableIngestBackpressure\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableIngestBackpressure\":true", "\"enableIngestBackpressure\":false"),
                LSSClientConfig.class);
        assertFalse(loaded.enableIngestBackpressure, "a saved false must bind back as false");
    }

}
