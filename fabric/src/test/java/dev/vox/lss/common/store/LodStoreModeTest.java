package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code lodStore} switch semantics (plan §1): unknown values normalize to OFF —
 * the SAFE value (a typo must never enable a storage engine) — and the diag token is a
 * token on the DiskReader line, with the bare {@code store=off} shape in off mode.
 */
class LodStoreModeTest {

    @Test
    void normalizeMapsKnownValuesAndDefaultsUnknownToOff() {
        assertEquals(LodStoreMode.OFF, LodStoreMode.normalize("off"));
        assertEquals(LodStoreMode.MEMORY, LodStoreMode.normalize("memory"));
        assertEquals(LodStoreMode.FULL, LodStoreMode.normalize("full"));
        assertEquals(LodStoreMode.MEMORY, LodStoreMode.normalize("  Memory "));
        assertEquals(LodStoreMode.OFF, LodStoreMode.normalize("FULL_SPEED"));
        assertEquals(LodStoreMode.OFF, LodStoreMode.normalize("auto"));
        assertEquals(LodStoreMode.OFF, LodStoreMode.normalize(""));
        assertEquals(LodStoreMode.OFF, LodStoreMode.normalize(null));
    }

    @Test
    void configValueRoundTrips() {
        for (var mode : LodStoreMode.values()) {
            assertEquals(mode, LodStoreMode.normalize(mode.configValue()));
        }
    }

    @Test
    void diagTokenIsBareInOffModeAndCarriesCountersWhenActive() {
        var diag = new LodStoreDiagnostics();
        assertEquals("store=off", diag.formatToken(LodStoreMode.OFF));

        diag.recordHit(30_000);       // 30 µs
        diag.recordHit(50_000);       // 50 µs -> avg 40 µs
        diag.recordMiss();
        diag.recordDeposit();
        diag.recordDeposit();
        diag.recordDepositDrop();
        diag.recordError();
        diag.setQueueDepth(3);
        assertEquals("store=full h=2 m=1 dep=2 drop=1 err=1 q=3 avg_read=40us",
                diag.formatToken(LodStoreMode.FULL));
    }

    @Test
    void gaugesSetNotSumAndCheckpointKeepsMax() {
        var diag = new LodStoreDiagnostics();
        diag.setDbBytes(100);
        diag.setDbBytes(50);
        assertEquals(50, diag.getDbBytes(), "db_bytes is a gauge — set, not summed");
        diag.recordCheckpointMs(12);
        diag.recordCheckpointMs(5);
        assertEquals(12, diag.getCheckpointMsMax(), "checkpoint keeps the max");
        assertEquals(0, diag.getReadAvgMicros(), "no hits -> avg 0, never divide-by-zero");
    }
}
