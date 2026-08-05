package dev.vox.lss.common.compat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the v18 compat rung's membership lifecycle (docs/planning/v18-compat-design.md
 * §2.3). The tracker is deliberately membership-only: a v18 session is an ordinary
 * CURRENT-dialect session, so the only state worth tracking is WHO is one. The
 * load-bearing property — membership survives {@code service.removePlayer} (the
 * dimension-change cycle) — cannot be pinned here (the tracker has no such hook by
 * design); it is pinned at the service level by driving the production
 * remove+register cycle and asserting the re-derived {@code wantsCompressedColumns}
 * stays false (see {@code PaperRequestProcessingServiceTest}).
 */
class V18CompatTrackerTest {

    private final V18CompatTracker tracker = new V18CompatTracker();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void handshakeMarksAndDisconnectDrops() {
        assertFalse(tracker.isV18(a));
        tracker.onHandshake(a);
        assertTrue(tracker.isV18(a));
        assertFalse(tracker.isV18(b));
        assertEquals(1, tracker.sessionCount());

        tracker.onDisconnect(a);
        assertFalse(tracker.isV18(a));
        assertEquals(0, tracker.sessionCount());
    }

    @Test
    void duplicateHandshakeKeepsMembershipAndIsNotRecounted() {
        tracker.onHandshake(a);
        tracker.onHandshake(a);
        assertTrue(tracker.isV18(a));
        assertEquals(1, tracker.sessionCount());
        assertEquals(1, tracker.totalSessionsStarted());
    }

    @Test
    void crossDialectShedDropsMembership() {
        // A CURRENT (or v16) re-handshake on a live connection must shed the stale v18
        // identity — otherwise columns keep shipping codec-less to a decoder that now
        // expects the codec byte (hard-kick class).
        tracker.onHandshake(a);
        tracker.onNonV18Handshake(a);
        assertFalse(tracker.isV18(a));
        // And it is a safe no-op for players who never were v18.
        tracker.onNonV18Handshake(b);
        assertFalse(tracker.isV18(b));
    }

    @Test
    void disconnectIsIdempotentAndPerPlayer() {
        tracker.onHandshake(a);
        tracker.onHandshake(b);
        tracker.onDisconnect(a);
        tracker.onDisconnect(a); // the quit-race double-drop (direct + mailbox Remove)
        assertFalse(tracker.isV18(a));
        assertTrue(tracker.isV18(b));
        assertEquals(1, tracker.sessionCount());
    }

    @Test
    void diagLineIsNullUntilTouchedThenPersistsAfterDisconnect() {
        // Mirrors the v16 line's stance: an admin who saw a v18 client this run should
        // still see the evidence after that client left.
        assertNull(tracker.diagLineOrNull());
        tracker.onHandshake(a);
        assertEquals("V18Compat: clients=1, started=1", tracker.diagLineOrNull());
        tracker.onDisconnect(a);
        assertEquals("V18Compat: clients=0, started=1", tracker.diagLineOrNull());
    }

    @Test
    void startedCountsDistinctSessionsNotPlayers() {
        // A rejoin is a new session: mark, drop, mark again -> started=2.
        tracker.onHandshake(a);
        tracker.onDisconnect(a);
        tracker.onHandshake(a);
        assertNotNull(tracker.diagLineOrNull());
        assertEquals(2, tracker.totalSessionsStarted());
        assertEquals(1, tracker.sessionCount());
    }
}
