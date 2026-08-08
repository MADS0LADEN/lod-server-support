package dev.vox.lss.networking.server;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.HandshakeGate.Outcome;
import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared handshake decision ladder consumed by {@code LSSServerNetworking}
 * (and, on Paper, {@code LSSPaperPlugin} — see the twin test in the paper module).
 *
 * <p>The critical invariant: a version-skewed client must receive NO reply. The
 * mismatched client's SessionConfig codec has a different field layout on the same
 * channel id, so any frame the server sends decodes as a DecoderException and kicks
 * the player — replying here would kick every outdated client on join.
 */
class HandshakeGateTest {

    private static final int V = LSSConstants.PROTOCOL_VERSION;
    private static final int VOXEL_CAPS = LSSConstants.CAPABILITY_VOXEL_COLUMNS;

    @Test
    void happyPathRepliesAndRegisters() {
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS, true, true);
        assertEquals(Outcome.REGISTER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertTrue(d.effectiveEnabled());
        assertTrue(d.registerPlayer());
    }

    @Test
    void newerClientVersionSendsNothing() {
        var d = HandshakeGate.evaluate(V + 1, VOXEL_CAPS, true, true);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void olderClientVersionSendsNothing() {
        // V-1 is protocol 18 since the v0.9.0 bump: on the STRICT (4-arg, both compat
        // flags off) ladder it must still be the silent mismatch — the v18 rung exists
        // only behind enableV18Compat (the 6-arg form; see the v18 section below).
        var d = HandshakeGate.evaluate(V - 1, VOXEL_CAPS, true, true);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        assertFalse(d.registerPlayer());
    }

    @Test
    void versionCheckOutranksCapabilityAndEnabledChecks() {
        // If the caps or enabled checks ever ran first, this would classify as
        // NO_CONSUMER/DISABLED and reply — kicking the skewed client.
        var d = HandshakeGate.evaluate(V + 1, 0, false, false);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
    }

    @Test
    void zeroCapabilitiesRepliesButNeverRegisters() {
        var d = HandshakeGate.evaluate(V, 0, true, true);
        assertEquals(Outcome.NO_CONSUMER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertTrue(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void zeroCapabilitiesOnDisabledServerStillClassifiesNoConsumer() {
        // Capability check outranks the enabled check: a consumer-less client is
        // logged as NO_CONSUMER whether or not LSS is enabled.
        var d = HandshakeGate.evaluate(V, 0, false, true);
        assertEquals(Outcome.NO_CONSUMER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void disabledConfigAdvertisesDisabledWithoutRegistering() {
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS, false, true);
        assertEquals(Outcome.DISABLED, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void absentServiceAdvertisesDisabledWithoutRegistering() {
        // registerPlayer()==false is what keeps call sites from dereferencing the
        // null service even though the config says enabled.
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS, true, false);
        assertEquals(Outcome.DISABLED, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void futureCapabilityBitsAlongsideVoxelColumnsStillRegister() {
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS | 0x7E, true, true);
        assertEquals(Outcome.REGISTER, d.outcome());
        assertTrue(d.registerPlayer());
    }

    @Test
    void foreignCapabilityBitsWithoutVoxelColumnsDoNotRegister() {
        var d = HandshakeGate.evaluate(V, 0x7E & ~VOXEL_CAPS, true, true);
        assertEquals(Outcome.NO_CONSUMER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.registerPlayer());
    }

    @Test
    void negativeProtocolVersionSendsNothing() {
        // Guards a relational rewrite of the version gate (e.g. `version > PROTOCOL_VERSION`
        // as an only-newer check): hostile or corrupt negative versions must classify
        // exactly like any other skew — no reply, no registration.
        for (int version : new int[]{-1, Integer.MIN_VALUE}) {
            var d = HandshakeGate.evaluate(version, VOXEL_CAPS, true, true);
            assertEquals(Outcome.VERSION_MISMATCH, d.outcome(), "version " + version);
            assertFalse(d.sendSessionConfig());
            assertFalse(d.registerPlayer());
        }
    }

    @Test
    void fullBitmaskCapabilitiesStillRegister() {
        // 0xFFFFFFFF is -1 as an int: a sign-sensitive capability check (`caps > 0 && ...`)
        // would refuse a client advertising every bit. Only bit 0 may matter.
        var d = HandshakeGate.evaluate(V, 0xFFFFFFFF, true, true);
        assertEquals(Outcome.REGISTER, d.outcome());
        assertTrue(d.registerPlayer());
    }

    // ---- v16 compat dialect rung (docs/planning/v16-compat-design.md §4.1) ----

    @Test
    void v16WithCompatEnabledTakesTheNormalLadderWithTheV16Dialect() {
        // The dialect is decided by the version rung ONLY; the rest of the ladder is
        // dialect-agnostic, so reply/registration policy cannot drift between dialects.
        var register = HandshakeGate.evaluate(16, VOXEL_CAPS, true, true, true);
        assertEquals(Outcome.REGISTER, register.outcome());
        assertEquals(HandshakeGate.WireDialect.V16, register.dialect());
        assertTrue(register.registerPlayer());

        var noConsumer = HandshakeGate.evaluate(16, 0, true, true, true);
        assertEquals(Outcome.NO_CONSUMER, noConsumer.outcome());
        assertEquals(HandshakeGate.WireDialect.V16, noConsumer.dialect());
        assertTrue(noConsumer.sendSessionConfig());
        assertFalse(noConsumer.registerPlayer());

        var disabled = HandshakeGate.evaluate(16, VOXEL_CAPS, false, true, true);
        assertEquals(Outcome.DISABLED, disabled.outcome());
        assertEquals(HandshakeGate.WireDialect.V16, disabled.dialect());
        assertTrue(disabled.sendSessionConfig());
        assertFalse(disabled.effectiveEnabled());
    }

    @Test
    void v16WithCompatDisabledStaysTheSilentVersionMismatch() {
        var d = HandshakeGate.evaluate(16, VOXEL_CAPS, true, true, false);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        // The 4-arg overload (pre-compat call shape) is the compat-disabled ladder.
        assertEquals(Outcome.VERSION_MISMATCH,
                HandshakeGate.evaluate(16, VOXEL_CAPS, true, true).outcome());
    }

    @Test
    void onlyExactly16GetsTheV16RungAndCurrentKeepsItsDialect() {
        // (Renamed from ...AndV18KeepsItsDialect — "V18" was the CURRENT dialect's old
        // name; a real V18 compat dialect exists now.) 15 and 17 are NOT compat
        // candidates even with the v16 flag on — the shim speaks exactly the v0.6.x
        // wire, nothing else. 18 on this (5-arg, v18-flag-off) overload is pinned by
        // v18WithCompatDisabledStaysTheSilentVersionMismatch below.
        for (int version : new int[]{15, 17}) {
            var d = HandshakeGate.evaluate(version, VOXEL_CAPS, true, true, true);
            assertEquals(Outcome.VERSION_MISMATCH, d.outcome(), "version " + version);
            assertFalse(d.sendSessionConfig());
        }
        var current = HandshakeGate.evaluate(V, VOXEL_CAPS, true, true, true);
        assertEquals(Outcome.REGISTER, current.outcome());
        assertEquals(HandshakeGate.WireDialect.CURRENT, current.dialect());
    }

    // ---- v18 compat dialect rung (docs/planning/v18-compat-design.md §2.1) ----

    @Test
    void v18WithCompatEnabledTakesTheNormalLadderWithTheV18Dialect() {
        // Same dialect-decided-by-the-version-rung-only contract as v16: the rest of the
        // ladder is dialect-agnostic, so reply/registration policy cannot drift.
        var register = HandshakeGate.evaluate(18, VOXEL_CAPS, true, true, true, true);
        assertEquals(Outcome.REGISTER, register.outcome());
        assertEquals(HandshakeGate.WireDialect.V18, register.dialect());
        assertTrue(register.registerPlayer());

        var noConsumer = HandshakeGate.evaluate(18, 0, true, true, true, true);
        assertEquals(Outcome.NO_CONSUMER, noConsumer.outcome());
        assertEquals(HandshakeGate.WireDialect.V18, noConsumer.dialect());
        assertTrue(noConsumer.sendSessionConfig());
        assertFalse(noConsumer.registerPlayer());

        var disabled = HandshakeGate.evaluate(18, VOXEL_CAPS, false, true, true, true);
        assertEquals(Outcome.DISABLED, disabled.outcome());
        assertEquals(HandshakeGate.WireDialect.V18, disabled.dialect());
        assertTrue(disabled.sendSessionConfig());
        assertFalse(disabled.effectiveEnabled());
    }

    @Test
    void v18WithCompatDisabledStaysTheSilentVersionMismatch() {
        var d = HandshakeGate.evaluate(18, VOXEL_CAPS, true, true, true, false);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        // The 5-arg overload (pre-v18-compat call shape) is the v18-disabled ladder.
        assertEquals(Outcome.VERSION_MISMATCH,
                HandshakeGate.evaluate(18, VOXEL_CAPS, true, true, true).outcome());
    }

    @Test
    void v19WithCompatEnabledTakesTheNormalLadderWithTheV19Dialect() {
        // The protocol-20 bump's third rung (XVER §4.2): same
        // dialect-decided-by-the-version-rung-only contract as v16/v18.
        var register = HandshakeGate.evaluate(19, VOXEL_CAPS, true, true, true, true, true);
        assertEquals(Outcome.REGISTER, register.outcome());
        assertEquals(HandshakeGate.WireDialect.V19, register.dialect());
        assertTrue(register.registerPlayer());

        var noConsumer = HandshakeGate.evaluate(19, 0, true, true, true, true, true);
        assertEquals(Outcome.NO_CONSUMER, noConsumer.outcome());
        assertEquals(HandshakeGate.WireDialect.V19, noConsumer.dialect());
        assertTrue(noConsumer.sendSessionConfig());
        assertFalse(noConsumer.registerPlayer());

        var disabled = HandshakeGate.evaluate(19, VOXEL_CAPS, false, true, true, true, true);
        assertEquals(Outcome.DISABLED, disabled.outcome());
        assertEquals(HandshakeGate.WireDialect.V19, disabled.dialect());
    }

    @Test
    void v19WithCompatDisabledStaysTheSilentVersionMismatch() {
        var d = HandshakeGate.evaluate(19, VOXEL_CAPS, true, true, true, true, false);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        // The 6-arg overload (pre-v19-compat call shape) is the v19-disabled ladder —
        // a production call site left on it silently mismatches every v0.9.x client,
        // which is why both platforms pin their handshake paths with a v19 frame.
        assertEquals(Outcome.VERSION_MISMATCH,
                HandshakeGate.evaluate(19, VOXEL_CAPS, true, true, true, true).outcome());
    }

    @Test
    void theThirdCompatFlagIsIndependentToo() {
        // v19 answers ONLY to its own flag, exactly like the other two rungs.
        assertEquals(Outcome.VERSION_MISMATCH,
                HandshakeGate.evaluate(19, VOXEL_CAPS, true, true, true, true, false).outcome());
        assertEquals(HandshakeGate.WireDialect.V19,
                HandshakeGate.evaluate(19, VOXEL_CAPS, true, true, false, false, true).dialect());
        assertEquals(HandshakeGate.WireDialect.V18,
                HandshakeGate.evaluate(18, VOXEL_CAPS, true, true, false, true, false).dialect());
        assertEquals(HandshakeGate.WireDialect.V16,
                HandshakeGate.evaluate(16, VOXEL_CAPS, true, true, true, false, false).dialect());
    }

    @Test
    void theTwoCompatFlagsAreIndependent() {
        // Each rung answers ONLY to its own flag: 18 with only the v16 flag mismatches,
        // 16 with only the v18 flag mismatches, and each version takes its own dialect
        // with only its own flag on.
        assertEquals(Outcome.VERSION_MISMATCH,
                HandshakeGate.evaluate(18, VOXEL_CAPS, true, true, true, false).outcome());
        assertEquals(Outcome.VERSION_MISMATCH,
                HandshakeGate.evaluate(16, VOXEL_CAPS, true, true, false, true).outcome());
        assertEquals(HandshakeGate.WireDialect.V18,
                HandshakeGate.evaluate(18, VOXEL_CAPS, true, true, false, true).dialect());
        assertEquals(HandshakeGate.WireDialect.V16,
                HandshakeGate.evaluate(16, VOXEL_CAPS, true, true, true, false).dialect());
    }

    @Test
    void v17StillMismatchesWithBothCompatFlagsOn() {
        // Protocol 17 never shipped in a tagged release — there is deliberately no rung.
        var d = HandshakeGate.evaluate(17, VOXEL_CAPS, true, true, true, true);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
    }

    @Test
    void zstdCapabilityBitNeverChangesTheLadder() {
        // The gate is capability-agnostic beyond bit 0 (plan §2): 0x2 carries session
        // ABILITY consumed at registration, never reply/registration policy. Identical
        // Decision with and without it, on every rung that reads capabilities.
        int with = LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS;
        int without = LSSConstants.CAPABILITY_VOXEL_COLUMNS;
        assertEquals(
                HandshakeGate.evaluate(LSSConstants.PROTOCOL_VERSION, without, true, true),
                HandshakeGate.evaluate(LSSConstants.PROTOCOL_VERSION, with, true, true));
        assertEquals(
                HandshakeGate.evaluate(LSSConstants.PROTOCOL_VERSION, without, false, true),
                HandshakeGate.evaluate(LSSConstants.PROTOCOL_VERSION, with, false, true));
        // A consumer-less client with ONLY the zstd bit stays NO_CONSUMER.
        assertEquals(HandshakeGate.Outcome.NO_CONSUMER,
                HandshakeGate.evaluate(LSSConstants.PROTOCOL_VERSION,
                        LSSConstants.CAPABILITY_ZSTD_COLUMNS, true, true).outcome());
    }
}
