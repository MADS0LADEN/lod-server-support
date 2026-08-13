package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The capability-composition pins (mega plan E2 row): the compiled arm is ON, and the
 * capability bit composes from the ARM + the soak/benchmark property gate ONLY
 * (FARP §3.3, E2 review M2 — deliberately config-independent, the prefs-carrier
 * rule): soak/benchmark clients are full Loom clients distinguished ONLY by the
 * system properties, and an armed bit there would subscribe them and shift every
 * soak baseline.
 */
class FarPlayerClientSupportTest {

    @Test
    void e2ShipsWithTheClientArmCompiledOn() {
        assertTrue(FarPlayerClientSupport.CLIENT_ARMED,
                "E2's defaults decision (user 2026-08-12) arms the bit — flipping this "
                        + "back off is a release decision, not drift");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(false, false, false),
                "the unarmed composition stays pinned (support-line backports may ship it)");
    }

    @Test
    void propertyGateKeepsSoakAndBenchmarkClientsUnsubscribedOnceArmed() {
        assertEquals(LSSConstants.CAPABILITY_FAR_PLAYERS,
                FarPlayerClientSupport.capabilityBitFor(true, false, false),
                "armed + no harness properties -> the bit");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(true, true, false),
                "a soak JVM never subscribes (baseline neutrality)");
        assertEquals(0, FarPlayerClientSupport.capabilityBitFor(true, false, true),
                "a benchmark JVM never subscribes");
    }

    @Test
    void coexistTruthTableSuppressesOnlyWithSeeUAndWithoutTheOverride() {
        // E3: the SeeU gate composes the EFFECTIVE enabled term (renderer + the
        // prefs `enabled` field), never the capability bit. The four live rows:
        assertTrue(FarPlayerClientSupport.effectiveEnabledFor(true, false, false),
                "no SeeU: config rules");
        assertFalse(FarPlayerClientSupport.effectiveEnabledFor(true, true, false),
                "SeeU without the override suppresses (the double-proxy guard)");
        assertTrue(FarPlayerClientSupport.effectiveEnabledFor(true, true, true),
                "farPlayersWithSeeU explicitly prefers LSS");
        assertFalse(FarPlayerClientSupport.effectiveEnabledFor(false, true, true),
                "the override must NEVER resurrect a user-disabled master toggle");
        assertFalse(FarPlayerClientSupport.effectiveEnabledFor(false, false, false),
                "config off stays off");
    }

    @Test
    void capabilityBitIsDeliberatelyIndependentOfTheEnabledToggle() {
        // E2 review M2 (both reviewers): the subscription is the PREFS CARRIER. A
        // client with the master toggle OFF but shareSelf=false set must still
        // deliver that opt-out — coupling the bit to `enabled` made "turn everything
        // off" strand the opt-out server-side (more visible, not less). The server
        // skips serving disabled subscribers before any frame work, so the carrier
        // session costs a map entry; the RENDERER checks farPlayersEnabled itself.
        // capabilityBitFor deliberately has NO config parameter — this test exists
        // so a future "optimization" re-adding one trips a pin, not just a review.
        assertEquals(LSSConstants.CAPABILITY_FAR_PLAYERS,
                FarPlayerClientSupport.capabilityBitFor(true, false, false),
                "armed + no harness properties -> the bit, regardless of any config");
    }
}
