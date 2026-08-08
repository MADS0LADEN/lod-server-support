package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SessionConfig codec's C3 ladder arms driven through the REAL decode
 * ({@code SessionConfigS2CPayload.CODEC}) against {@code V16ClientWire}'s announce
 * state — the netty-side half the gate-level suite cannot reach (it constructs
 * payloads directly). The headline is the review-CRITICAL regression: the ladder's
 * own NEXT rung must never disarm a still-in-flight echo's decode — the single
 * last-announce memory turned a slow v19 reply into a foreign-arm fabrication
 * {@code (19, enabled=false, lod=0)}, silent LOD-off for the session.
 */
class V16ClientWireDecodeTest {

    private static final int V19 = LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
    private static final int V16 = LSSConstants.V16_COMPAT_PROTOCOL_VERSION;

    @BeforeEach
    @AfterEach
    void cleanState() {
        V16ClientWire.reset();
    }

    private static SessionConfigS2CPayload decode(SessionConfigS2CPayload frame) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SessionConfigS2CPayload.CODEC.encode(buf, frame);
            return SessionConfigS2CPayload.CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static SessionConfigS2CPayload nineteenEcho() {
        return new SessionConfigS2CPayload(V19, true, 64, true);
    }

    @Test
    void announced19DecodesTheNineteenEchoAsFourFieldAndArmsNativeBody() {
        V16ClientWire.markAnnouncedVersion(V19);
        var decoded = decode(nineteenEcho());
        assertEquals(V19, decoded.protocolVersion());
        assertTrue(decoded.enabled(), "the 4-field arm must read the real enabled flag");
        assertEquals(64, decoded.lodDistanceChunks());
        assertTrue(V16ClientWire.isNativeBodySession(),
                "a solicited 19 echo arms native-body decode");
        assertFalse(V16ClientWire.isColumnSourceless(),
                "a 19 session keeps the CURRENT frame layout");
    }

    @Test
    void theLaddersOwnSixteenRungMustNotDisarmThePendingNineteenEcho() {
        // The review-CRITICAL: announce 19, then (5 s later, echo still in flight) the
        // ladder announces 16. The 19 echo arriving NOW must still decode as the real
        // 4-field config and arm native-body decode — with single last-announce memory
        // it fell to the foreign arm as (19, enabled=false, lod=0): accepted by the
        // gate (announced19ThisConnection), sessionConfigReceived latched, LOD silently
        // OFF, and no heal at all on a server running enableV16Compat=false.
        V16ClientWire.markAnnouncedVersion(V19);
        V16ClientWire.markAnnouncedVersion(V16); // the ladder's own next rung
        var decoded = decode(nineteenEcho());
        assertTrue(decoded.enabled(),
                "the sticky announce memory must keep the 19 arm open past the 16 rung");
        assertEquals(64, decoded.lodDistanceChunks());
        assertTrue(V16ClientWire.isNativeBodySession(),
                "the slow 19 echo must still arm native-body decode");
    }

    @Test
    void unsolicitedNineteenFrameFallsToTheForeignArm() {
        var decoded = decode(nineteenEcho()); // never announced 19
        assertEquals(V19, decoded.protocolVersion());
        assertFalse(decoded.enabled(),
                "an unsolicited 19 frame is foreign — drained and read as disabled");
        assertEquals(0, decoded.lodDistanceChunks());
        assertFalse(V16ClientWire.isNativeBodySession());
    }

    @Test
    void reassertingTheCurrentVersionRetiresBothLegacyAnnouncesAndTheirFlags() {
        // The downgrade guard's heal: after a raced ladder, re-announcing the current
        // version must retire the legacy announce SET (not just the last one), disarm
        // the flags immediately, and leave later unsolicited legacy frames foreign.
        V16ClientWire.markAnnouncedVersion(V19);
        decode(nineteenEcho());
        assertTrue(V16ClientWire.isNativeBodySession(), "premise: the raced echo armed");

        V16ClientWire.markAnnouncedVersion(LSSConstants.PROTOCOL_VERSION);
        assertFalse(V16ClientWire.isNativeBodySession(),
                "the re-assert disarms immediately — before its own reply lands");

        var late = decode(nineteenEcho());
        assertFalse(late.enabled(), "a later unsolicited 19 frame is foreign again");
        assertFalse(V16ClientWire.isNativeBodySession(),
                "…and can never re-arm against the re-established stream");
    }

    @Test
    void sixteenArmingIsStickyThroughItsOwnRungAndRetiredByTheReassert() {
        V16ClientWire.markAnnouncedVersion(V19);
        V16ClientWire.markAnnouncedVersion(V16);
        var legacy = decode(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true));
        assertEquals(V16, legacy.protocolVersion());
        assertTrue(legacy.enabled(), "the 6-field arm is per-frame self-describing");
        assertTrue(V16ClientWire.isColumnSourceless(),
                "a solicited v16 reply arms sourceless decode");
        assertTrue(V16ClientWire.isNativeBodySession(),
                "sourceless implies native-body (the drain gate covers both rungs)");

        V16ClientWire.markAnnouncedVersion(LSSConstants.PROTOCOL_VERSION);
        assertFalse(V16ClientWire.isColumnSourceless(), "the re-assert retires v16 arming");
        decode(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true));
        assertFalse(V16ClientWire.isColumnSourceless(),
                "a later unsolicited v16 frame cannot re-arm");
    }

    @Test
    void aRetractedAnnounceLeavesTheNineteenFrameForeign() {
        // The send-throw rollback (review m4): a marked-but-never-sent announce must not
        // widen the decode surface for the rest of the connection.
        V16ClientWire.markAnnouncedVersion(V19);
        V16ClientWire.retractAnnounce(V19, LSSConstants.PROTOCOL_VERSION);
        var decoded = decode(nineteenEcho());
        assertFalse(decoded.enabled(), "a retracted 19 announce reads 19 frames as foreign");
        assertFalse(V16ClientWire.isNativeBodySession());
    }
}
