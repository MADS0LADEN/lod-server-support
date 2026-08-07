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

    /** Both compat rungs ship ON: a fleet of released v0.6.x (protocol 16) and
     *  v0.7.x–v0.8.x (protocol 18) clients must keep working across a server upgrade
     *  without any operator action. Neither default existed as a pin before the v18
     *  rung (plan-review finding); flipping either is a player-facing compat break and
     *  must be a deliberate release decision, not drift. */
    @Test
    void compatRungsDefaultOn() {
        assertTrue(serverConfig().enableV16Compat);
        assertTrue(serverConfig().enableV18Compat);
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

        // Ceiling raised 100 MB -> 1 GiB 2026-08-02 (config review §5): the live server hit
        // the old one exactly. The DEFAULT is untouched — this bounds only what an admin types.
        c.bytesPerSecondLimitPerPlayer = 200_000_000;
        c.validate();
        assertEquals(200_000_000, c.bytesPerSecondLimitPerPlayer, "200 MB is now inside the band");
        c.bytesPerSecondLimitPerPlayer = Integer.MAX_VALUE;
        c.validate();
        assertEquals(LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER, c.bytesPerSecondLimitPerPlayer);
    }

    @Test
    void diskReaderThreadsClamped() {
        var c = serverConfig();
        c.diskReaderThreads = 1;
        c.validate();
        assertEquals(1, c.diskReaderThreads);

        c.diskReaderThreads = 100;
        c.validate();
        assertEquals(64, c.diskReaderThreads);
    }

    /** 0 = AUTO survives validate() (it must not be clamped up to the MIN of 1, which would
     *  silently destroy the derive-it semantics), and the derived value depends on whether the
     *  resolved read path carries real priority. */
    @Test
    void diskReaderThreadsZeroMeansAutoAndSurvivesValidation() {
        var c = serverConfig();
        c.diskReaderThreads = 0;
        c.validate();
        assertEquals(0, c.diskReaderThreads, "0 = AUTO must survive validate()");
        c.diskReaderThreads = -5;
        c.validate();
        assertEquals(0, c.diskReaderThreads, "negative nonsense normalizes to AUTO, not to 1");

        assertEquals(LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER,
                c.effectiveDiskReaderThreads(false),
                "vanilla's single-threaded IOWorker: concurrency IS the vanilla-delay tradeoff");
        int prioritized = c.effectiveDiskReaderThreads(true);
        assertTrue(prioritized >= LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER
                        && prioritized <= LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX,
                "the Moonrise Priority.LOW tier scales with cores inside its band, got " + prioritized);

        c.diskReaderThreads = 12;
        c.validate();
        assertEquals(12, c.effectiveDiskReaderThreads(true), "an explicit value always wins");
        assertEquals(12, c.effectiveDiskReaderThreads(false));
    }

    /** The config review proposed retiring this ("unreachable at its default"); implementation
     *  reversed the call — lowering it is the only lever that exercises service.queue_full, the
     *  send-queue breaker, and the bandwidth-throttle soak scenario gates on it firing. */
    @Test
    void sendQueueLimitPerPlayerClamped() {
        var c = serverConfig();
        assertEquals(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, c.sendQueueLimitPerPlayer,
                "the default must stay AT the wire batch cap");
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

        // Ceiling raised 256 -> 512 2026-08-02: WantSetBudgetInvariantTest permits up to 536
        // (slot cap 200 + reserve 64 against the 800 budget), so the headroom was unused.
        c.generationConcurrencyLimitGlobal = 999;
        c.validate();
        assertEquals(LSSConstants.MAX_CONCURRENT_GENERATIONS, c.generationConcurrencyLimitGlobal);
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
        // Config review §9.1: the per-player ceiling is now the CONFIGURED global, not the
        // unrelated MAX_CONCURRENCY_LIMIT (1000) — a per-player value above the fleet-wide one
        // is unreachable by construction, so it used to validate to silent nonsense.
        c.generationConcurrencyLimitGlobal = 64;
        c.generationConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(64, c.generationConcurrencyLimitPerPlayer,
                "per-player must clamp to the configured global, not above it");
    }

    @Test
    void perDimensionTimestampCacheSizeMBClamped() {
        var c = serverConfig();
        // 0 = AUTO now (derived from lodDistanceChunks) and must survive validate().
        c.perDimensionTimestampCacheSizeMB = 0;
        c.validate();
        assertEquals(0, c.perDimensionTimestampCacheSizeMB);

        c.perDimensionTimestampCacheSizeMB = 9999;
        c.validate();
        assertEquals(LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB, c.perDimensionTimestampCacheSizeMB);
    }

    /** The AUTO sizing must track lodDistanceChunks — its whole reason for existing is that a
     *  fixed value silently under-provisions exactly when an admin raises the distance —
     *  which is the whole point, since the default went 256 -> 512 -> 256 in one day. AUTO must
     *  reproduce the historic hand-tuned 32 MB at the 256 default, and a 512 disc (~4x the
     *  area) must buy materially more. */
    @Test
    void timestampCacheAutoSizeTracksLodDistance() {
        var c = serverConfig();
        c.perDimensionTimestampCacheSizeMB = 0;

        c.lodDistanceChunks = 256;
        c.validate();
        int at256 = c.effectiveTimestampCacheMB();
        assertTrue(at256 >= 28 && at256 <= 36,
                "AUTO at the 256 default must land near the historic hand-tuned 32 MB, got " + at256);

        c.lodDistanceChunks = 512;
        c.validate();
        int at512 = c.effectiveTimestampCacheMB();
        assertTrue(at512 > at256 * 2,
                "4x the disc area must buy materially more cache: " + at256 + " -> " + at512);
        assertTrue(at512 <= LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB, "AUTO must respect the ceiling");

        c.perDimensionTimestampCacheSizeMB = 77;
        assertEquals(77, c.effectiveTimestampCacheMB(), "an explicit value always wins");
    }

    /** The LOD store is OPT-IN on every platform (user decision, 2026-08-03). It briefly
     *  defaulted to "full" during v0.9.0 development; that was reverted before release
     *  because an upgrade must never silently double the size of an operator's world
     *  folder — that is not a cost someone can consent to without reading the notes, and
     *  a full disk is not cheap to undo. The docs recommend enabling it instead.
     *
     *  <p>lodStoreBackfill stays ON deliberately: it is inert while the store is off, so
     *  the only thing it decides is what an operator gets when they DO opt in, and they
     *  should get the whole feature from the one key rather than find a second switch
     *  later. Pinning both together is what keeps that one-switch property true. */
    @Test
    void lodStoreIsOptInWhileBackfillStaysArmedForWhenItIsEnabled() {
        assertEquals("off", serverConfig().lodStore,
                "the store must be opt-in — never a silent 2x world folder on upgrade");
        assertTrue(serverConfig().lodStoreBackfill,
                "backfill stays on so ONE key enables the whole feature; it is inert while off");
    }

    /** The cap-behavior user decision (store-cap-behavior-plan.md §1): the store ships
     *  UNCAPPED — 0 means no size cap, and it is the default. A silent revert to a
     *  nonzero default would re-enter the backfill<->eviction treadmill. */
    @Test
    void lodStoreMaxMBDefaultsToUncappedZero() {
        assertEquals(0, serverConfig().lodStoreMaxMB);
    }

    /** The 0-or-64..1048576 clamp: 0 (and negative nonsense) stays uncapped; a nonzero
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

        // Ceiling raised 32 GB -> 1 TB 2026-08-02: this bounds the ADMIN'S OWN disk, and a
        // Chunky-pregenerated world's store can exceed 32 GB, where an artificial ceiling turns
        // an intentional cap into a silent eviction treadmill.
        c.lodStoreMaxMB = 999_999;
        c.validate();
        assertEquals(999_999, c.lodStoreMaxMB, "999999 MB is now inside the band");
        c.lodStoreMaxMB = Integer.MAX_VALUE;
        c.validate();
        assertEquals(LSSConstants.MAX_LOD_STORE_MAX_MB, c.lodStoreMaxMB);

        c.lodStoreMaxMB = 63;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB);
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

    /** Drift guards for the backfill-tuning defaults. The pace was raised 100 -> 500 on
     *  2026-08-02 (config review §2.3) — it is what makes a default-ON backfill tolerable, by
     *  turning a ~2 h background walk into a ~23 min one. The MSPT gate is a constant now. */
    @Test
    void backfillTuningDefaultsAre500ColumnsPerSecondAnd45MsCeiling() {
        assertEquals(500, serverConfig().lodStoreBackfillColumnsPerSecond);
        assertEquals(45, LSSConstants.LOD_STORE_BACKFILL_TICK_CEILING_MS);
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
            // uncapped, the default) and outboundBufferCeilingKB (0 = transport
            // deference off, the default); xrayMaxBlockHeight's floor is a world Y and
            // deliberately negative — every other numeric floor is >= 1.
            int floor = switch (f.getName()) {
                case "missMemoTtlSeconds", "lodStoreResweepSeconds", "lodStoreMaxMB",
                        "outboundBufferCeilingKB",
                        // 0 = AUTO (derived), the default for both since 2026-08-02.
                        "diskReaderThreads", "perDimensionTimestampCacheSizeMB" -> 0;
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

    /** LOD reads yield to gameplay out of the box; false is the documented rollback and the
     *  arm selector for benchmark_compare.sh's foreground-vs-background comparison. */
    @Test
    void backgroundReadPriorityDefaultsOn() {
        assertTrue(serverConfig().useBackgroundReadPriority,
                "background read priority must default on");
    }

    @Test
    void autoDiskReaderThreadConstantsAreCoherent() {
        // The retired key no longer exists as a field, so there is nothing to default-check;
        // what must hold is that the constants the auto-sizing leans on are still coherent
        // (both tiers inside the band, floor <= ceiling). JsonConfigLoadTest covers the
        // "an old file still carrying the key parses fine" half.
        assertTrue(LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER
                        <= LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX,
                "the AUTO floor must not exceed the prioritized ceiling");
        assertTrue(LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER
                        >= LSSConstants.MIN_DISK_READER_THREADS,
                "the AUTO value must itself be a legal explicit value");
        assertTrue(LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX
                        <= LSSConstants.MAX_DISK_READER_THREADS);
    }

    /** The yield plan's recorded evidence discipline (§4): the mechanism ships UNARMED;
     *  the default flips only in a later release citing the live E3 A/B. */
    @Test
    void transportYieldDefaultsOff() {
        assertFalse(serverConfig().lodYieldsToVanillaTransport,
                "lodYieldsToVanillaTransport must default FALSE");
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

    @Test
    void lodColumnsPerSecondLimitDefaultsOffAndClamps() {
        // The manual column-rate cap (docs/planning/client-column-rate-cap-design.md):
        // 0 = off is the shipped default and must survive validate() bit-identically.
        var c = clientConfig();
        assertEquals(0, c.lodColumnsPerSecondLimit, "the cap must ship OFF");
        c.validate();
        assertEquals(0, c.lodColumnsPerSecondLimit, "0 must stay 0 (never clamped up to the floor)");

        // A negative hand-edit means "off" — clamping it to the floor would silently ARM a
        // cap the user meant to disable.
        c.lodColumnsPerSecondLimit = -5;
        c.validate();
        assertEquals(0, c.lodColumnsPerSecondLimit, "negatives normalize to off, never to 50");

        c.lodColumnsPerSecondLimit = 10;
        c.validate();
        assertEquals(50, c.lodColumnsPerSecondLimit, "positive values clamp to the 50 floor");

        c.lodColumnsPerSecondLimit = 1_000_000;
        c.validate();
        assertEquals(100_000, c.lodColumnsPerSecondLimit, "...and to the 100k ceiling");

        // Every nonzero Sodium slider stop (step 50, range 50..3200) round-trips unchanged.
        c.lodColumnsPerSecondLimit = 3200;
        c.validate();
        assertEquals(3200, c.lodColumnsPerSecondLimit);
    }

    @Test
    void lodColumnsPerSecondLimitRoundTripsThroughJson() {
        // Same GSON contract as the toggles: exact key, and a saved nonzero binds back.
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"lodColumnsPerSecondLimit\":0"),
                "a fresh config must persist the off default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"lodColumnsPerSecondLimit\":0", "\"lodColumnsPerSecondLimit\":400"),
                LSSClientConfig.class);
        assertEquals(400, loaded.lodColumnsPerSecondLimit, "a saved cap must bind back");
    }

    /**
     * The effective-config echo is a SCRIPT-CONSUMED CONTRACT (PERF Phase 0 item 1):
     * profile_disk_read.sh / compress_gate.sh / backfill_profile.sh grep
     * "Effective config: " from server.log and match per-key substrings into each
     * run's arm_valid. Exact-string pin — a format drift here silently invalidates
     * every measurement arm, so this must red before any script does.
     */
    @Test
    void effectiveConfigEchoIsAScriptConsumedContract() {
        var c = serverConfig();
        c.useNbtTranscode = false;
        c.useCompressedColumns = true;
        assertEquals("Effective config: useNbtTranscode=false, diskReaderThreads=7,"
                        + " useCompressedColumns=true",
                c.effectiveConfigEcho(7),
                "key order and key=value spelling are what the harnesses grep");
        // The thread count echoed is the RESOLVED one the caller passes (0=AUTO already
        // applied) — the scripts assert the staged explicit value appears verbatim.
        c.useNbtTranscode = true;
        c.useCompressedColumns = false;
        assertEquals("Effective config: useNbtTranscode=true, diskReaderThreads=5,"
                        + " useCompressedColumns=false",
                c.effectiveConfigEcho(5));
    }

}
