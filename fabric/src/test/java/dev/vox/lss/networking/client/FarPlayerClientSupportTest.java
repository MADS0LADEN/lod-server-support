package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The E1 inert-shipping pins (mega plan E1 row): the compiled arm is OFF — the
 * capability bit is never composed regardless of config — and the soak/benchmark
 * property gate (FARP §3.3) holds once E2 arms it: soak/benchmark clients are full
 * Loom clients distinguished ONLY by the system properties, and an armed bit there
 * would subscribe them and shift every soak baseline.
 */
class FarPlayerClientSupportTest {

    @Test
    void e1ShipsWithTheClientArmCompiledOff() {
        assertFalse(FarPlayerClientSupport.CLIENT_ARMED,
                "E1 is INERT by decision (all five review lenses converged) — flipping "
                        + "this constant is E2's defaults decision, not drift");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(false, true, false, false),
                "unarmed -> no bit, even with the config enabled");
    }

    @Test
    void propertyGateKeepsSoakAndBenchmarkClientsUnsubscribedOnceArmed() {
        assertEquals(LSSConstants.CAPABILITY_FAR_PLAYERS,
                FarPlayerClientSupport.capabilityBitFor(true, true, false, false),
                "armed + enabled + no harness properties -> the bit");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(true, true, true, false),
                "a soak JVM never subscribes (baseline neutrality)");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(true, true, false, true),
                "a benchmark JVM never subscribes");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(true, false, false, false),
                "the config toggle is honored");
    }
}
