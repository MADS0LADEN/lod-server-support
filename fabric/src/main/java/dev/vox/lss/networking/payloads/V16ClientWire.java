package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;

/**
 * Client-side v16 backward-compat decode state (a v0.7.0 client talking to a v0.4.x–v0.6.2
 * protocol-16 server). See {@code docs/planning/v16-client-compat-design.md}.
 *
 * <p><b>Why a shared flag exists at all.</b> Two S2C frames differ on the wire between v16 and
 * v18: the SessionConfig (6 fields vs 4, but <em>self-describing</em> via its leading version
 * VarInt) and the VoxelColumn (no {@code source} byte, and <em>NOT</em> self-describing — the
 * frame carries no version marker). Payload decode runs on the netty thread <em>before</em>
 * any handler with connection context, so a stateless codec cannot know whether to expect the
 * source byte. This holds the one bit that resolves it.
 *
 * <p><b>Why arming is gated on the client's own announce.</b> A v16 SessionConfig arms the
 * source-less flag only when the LAST protocol version this client announced was 16 (the
 * discovery fallback) — because that is the only flow in which the config is the prelude to a
 * genuine source-less column stream. An UNSOLICITED v16 config on a v18 session (the Paper
 * {@code /reload} re-attach prompt deliberately speaks the v16 dialect; see the downgrade
 * guard in {@code ClientSessionGate}) must heal at the session layer WITHOUT poisoning the
 * decode layer: on Folia the prompt can race the pump's re-registration, landing mid-stream
 * between live v18 columns, and an armed flag there misreads every subsequent source byte
 * until the v18 config reply lands — a decoder kick of a healthy client. With the gate, the
 * prompt changes nothing on the netty thread and the guard's re-announce does the healing.
 *
 * <p><b>Why it is race-free.</b> {@link #markAnnouncedVersion} is written on the main thread
 * BEFORE the handshake C2S send; the server's reply can only follow that send (wire
 * causality), so by the time {@link #observeSessionConfigVersion} runs on the netty thread the
 * {@code volatile} announce bit is visible. Observe and {@link #isColumnSourceless} both run
 * on the same single netty decode thread, in frame order, and the server always sends the
 * SessionConfig before any column — so the flag is established before the first column
 * decodes. {@code volatile} carries the main-thread reset ({@link #reset} on JOIN/DISCONNECT)
 * across to the netty thread; a stale flag from a prior connection can never leak into a new
 * one.
 *
 * <p><b>Why v18 is unaffected.</b> The default is {@code false}; a v18 SessionConfig sets it
 * {@code false}; with the flag {@code false} the VoxelColumn decoder reads the source byte
 * exactly as it always has. If the client never announces version 16 (compat disabled, or a
 * v18 server), no v16 config — solicited or not — can ever arm it. This class is
 * client-decode-only — a dedicated server never invokes it (it encodes S2C, never decodes).
 */
public final class V16ClientWire {

    private static volatile boolean announced16 = false;
    private static volatile boolean columnSourceless = false;

    private V16ClientWire() {}

    /** Main thread, called by {@code ClientSessionGate} immediately BEFORE each handshake
     *  send with the version being announced. Arming ({@link #observeSessionConfigVersion})
     *  requires the last announce to have been 16 — the discovery fallback — so an
     *  unsolicited v16 frame (a re-attach prompt, or a hostile server) can never flip
     *  column decode out from under an established v18 stream. */
    public static void markAnnouncedVersion(int protocolVersion) {
        announced16 = (protocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION);
    }

    /** Netty decode thread: called from {@code SessionConfigS2CPayload}'s decoder with the
     *  frame's protocol version, establishing (before any column decodes on the same thread)
     *  whether subsequent VoxelColumn frames omit the source byte. Arms only when this client
     *  itself last announced version 16 (see {@link #markAnnouncedVersion}); any non-16 frame
     *  disarms unconditionally. */
    public static void observeSessionConfigVersion(int protocolVersion) {
        columnSourceless = announced16
                && protocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION;
    }

    /** Netty decode thread: true when the current session is a v16 server, whose VoxelColumn
     *  frames carry no {@code source} byte (added in protocol 18). */
    public static boolean isColumnSourceless() {
        return columnSourceless;
    }

    /** Main thread: clear all state at a connection boundary (JOIN before the handshake, and
     *  DISCONNECT) so no v16 state survives into a subsequent connection. */
    public static void reset() {
        announced16 = false;
        columnSourceless = false;
    }
}
