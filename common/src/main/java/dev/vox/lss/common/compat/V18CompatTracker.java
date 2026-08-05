package dev.vox.lss.common.compat;

import dev.vox.lss.common.LSSLogger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The v18 compat rung's per-service registry (docs/planning/v18-compat-design.md):
 * MEMBERSHIP ONLY, keyed by UUID. Unlike {@link V16CompatManager} there is no per-session
 * state — a v18 session is an ordinary current-dialect session whose SessionConfig echoed
 * 18, whose {@code wantsCompressedColumns} derivation was forced false, and whose column
 * frames are stripped of the codec byte at the per-player egress seam. C2S is
 * byte-identical to the current protocol, so ingress never consults this class.
 *
 * <p>Lifecycle contract (the v16 identity's, §2.3 of the design): membership is created at
 * the HANDSHAKE (Fabric: inline before {@code registerPlayer} on the main thread; Paper:
 * ONLY via the {@code dialectFlip} runnable the lifecycle mailbox runs on the PUMP — a
 * region-thread mark can flip the dialect mid-flush and ship codec-less frames to a
 * decoder still armed CURRENT) and dropped at the network DISCONNECT hooks.
 * {@code service.removePlayer} — the dimension-change remove+register cycle — must NOT
 * drop it: the re-registration re-derives {@code wantsCompressedColumns} through this
 * membership, and losing it would re-grow the codec byte mid-session (hard-kick class).
 * Paper's quit path ADDITIONALLY drops membership when the lifecycle mailbox drains the
 * quit-originated Remove: the quit's direct {@code onDisconnect} can run before a deferred
 * Register's mark has applied, which would leak the entry forever (review F2 — the
 * mailbox Remove is quit-originated only, so this cannot break dim-change survival).
 */
public final class V18CompatTracker {

    private final Set<UUID> sessions = ConcurrentHashMap.newKeySet();
    /** Cumulative sessions started this service lifetime — keeps the diag line visible
     *  after the last v18 client leaves (the v16 manager's counters behave the same). */
    private final AtomicLong sessionsStarted = new AtomicLong();

    /** Whether this player is a protocol-18 compat session (any thread). */
    public boolean isV18(UUID uuid) {
        return this.sessions.contains(uuid);
    }

    /** REGISTER outcome with the V18 dialect (see the class doc for which thread on which
     *  platform). Idempotent — a duplicate handshake keeps membership and is not
     *  re-counted. */
    public void onHandshake(UUID uuid) {
        if (this.sessions.add(uuid)) {
            this.sessionsStarted.incrementAndGet();
            LSSLogger.info("v18-compat: protocol-18 session started for " + uuid);
        }
    }

    /** Network disconnect (Fabric DISCONNECT event / Paper PlayerQuit), plus Paper's
     *  mailbox-Remove drain (the quit-race leak guard — class doc). */
    public void onDisconnect(UUID uuid) {
        this.sessions.remove(uuid);
    }

    /** A non-V18 reply got past the version rung for this player: shed any stale v18
     *  membership (a same-connection cross-dialect re-handshake — hostile or buggy
     *  clients only). Without this, every column keeps shipping codec-less to a decoder
     *  that now expects the codec byte. No-op normally. */
    public void onNonV18Handshake(UUID uuid) {
        if (this.sessions.remove(uuid)) {
            LSSLogger.info("v18-compat: session for " + uuid
                    + " shed by a cross-dialect re-handshake");
        }
    }

    public int sessionCount() {
        return this.sessions.size();
    }

    public long totalSessionsStarted() {
        return this.sessionsStarted.get();
    }

    /** One /lsslod diag line, or null when the rung has never been touched this run. */
    public String diagLineOrNull() {
        int clients = sessionCount();
        long started = totalSessionsStarted();
        if (clients == 0 && started == 0) return null;
        return String.format("V18Compat: clients=%d, started=%d", clients, started);
    }
}
