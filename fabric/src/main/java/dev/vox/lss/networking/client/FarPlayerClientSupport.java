package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlayerClientTracker;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSClientConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The client half of far players, phase A (E1, FARP §3.3 — tracker + prefs, no
 * rendering). SHIPS INERT: {@link #CLIENT_ARMED} is compiled {@code false}, so the
 * capability bit is never sent and every path below is dead until E2 flips it (the
 * v0.10.0 transport-yield default-FALSE pattern — E1's inert shipping deliberately
 * overrides FARP §3.3's "active from Phase A" wording, per the mega plan's E1 row).
 *
 * <p>R-5 (decided at E1): the tracker lives HERE, outside the request-manager
 * lifecycle — a mid-session SessionConfig re-push (stage C's {@code /lsslod set})
 * rebuilds the manager while the server's subscription state survives untouched, so
 * rebuild-and-resubscribe would turn one {@code set lodDistanceChunks} on an N-player
 * server into N roster floods. Death sites: disconnect + the R-3 reset re-subscribe.
 */
public final class FarPlayerClientSupport {

    /** E2 flips this to true (with the defaults decision); until then the bit is never
     *  composed and the prefs sender never fires. Package-visible for the pin test. */
    static final boolean CLIENT_ARMED = false;

    private static final FarPlayerClientTracker TRACKER = new FarPlayerClientTracker();
    private static volatile FarPlayerWire.Prefs lastSentPrefs;

    /** The handshake-composition term. The soak/benchmark property gate (FARP §3.3):
     *  those clients are full Loom clients distinguished only by the system
     *  properties — without the explicit check they would subscribe and shift soak
     *  baselines. */
    public static int capabilityBit() {
        return capabilityBitFor(CLIENT_ARMED, LSSClientConfig.CONFIG.farPlayersEnabled,
                Boolean.getBoolean("lss.soak"), Boolean.getBoolean("lss.benchmark"));
    }

    /** Pure form for the Tier 1 property-gate pin. */
    static int capabilityBitFor(boolean armed, boolean enabled, boolean soakJvm,
                                boolean benchmarkJvm) {
        if (!armed || !enabled || soakJvm || benchmarkJvm) return 0;
        return LSSConstants.CAPABILITY_FAR_PLAYERS;
    }

    /**
     * Prefs send, once per session unless changed (the {@code lss:client_info} sidecar
     * guard doctrine per R-7: a v0.11.0 client reaches PRE-v0.11.0 servers that never
     * registered the channel — containment, and the send must never take the session
     * down). Call sites: session-config receipt (post-handshake) and the R-3 reset
     * re-subscribe. No-op while the capability bit is not composed.
     */
    static void maybeSendPrefs() {
        if (capabilityBit() == 0) return;
        var config = LSSClientConfig.CONFIG;
        var prefs = new FarPlayerWire.Prefs(config.farPlayersEnabled,
                config.farPlayersMaxDistanceBlocks, config.farPlayersMinDistanceBlocks,
                config.farPlayersShareSelf, config.farPlayersShareDistanceBlocks);
        if (prefs.equals(lastSentPrefs)) return;
        try {
            ClientPlayNetworking.send(new dev.vox.lss.networking.payloads
                    .FarPlayerPrefsC2SPayload(FarPlayerWire.encodePrefs(prefs)));
            lastSentPrefs = prefs;
        } catch (Exception e) {
            LSSLogger.debug("Far-player prefs send failed (legacy server?): " + e.getMessage());
        }
    }

    /** Roster frame, main client thread (the receiver hops before calling). */
    static void onRosterFrame(byte[] body) {
        try {
            TRACKER.onRoster(FarPlayerWire.decodeRoster(body));
        } catch (Exception e) {
            LSSLogger.warn("Malformed far-player roster frame — ignored (" + e + ")");
        }
    }

    /** Updates frame, main client thread. */
    static void onUpdatesFrame(byte[] body) {
        try {
            TRACKER.onUpdates(FarPlayerWire.decodeUpdates(body), System.currentTimeMillis());
        } catch (Exception e) {
            LSSLogger.warn("Malformed far-player updates frame — ignored (" + e + ")");
        }
    }

    /**
     * Called at every handshake SEND (review M3): a re-handshake — the v16 discovery
     * re-announce, a /reload re-attach, a LAN promote — starts a fresh server-side
     * session whose subscription state is new, so a surviving latch would suppress the
     * prefs send that triggers the first roster. Clearing here keeps maybeSendPrefs's
     * once-unless-changed guard scoped to ONE server session, which is its contract.
     */
    static void onHandshakeSent() {
        lastSentPrefs = null;
    }

    /** Disconnect: the tracker + the prefs-sent latch die with the connection. */
    static void onSessionEnd() {
        TRACKER.clear();
        lastSentPrefs = null;
    }

    /**
     * The R-3 reset re-subscribe (fills stage D's marked seam in ResetCoordinator):
     * clear the tracker + seen-epoch state AND re-send prefs — the server answers ANY
     * prefs receipt with a bumped-epoch full roster, which repopulates. A bare tracker
     * clear without the prefs re-send would strand the client (the reason R-3 rejected
     * it). Inert while unsubscribed (the send no-ops).
     */
    public static void resetAndResubscribe() {
        TRACKER.clear();
        lastSentPrefs = null;
        maybeSendPrefs();
    }

    static FarPlayerClientTracker tracker() {
        return TRACKER;
    }

    private FarPlayerClientSupport() {}
}
