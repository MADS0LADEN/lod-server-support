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
 *
 * <p><b>C3 generalization (XVER §6).</b> The discovery fallback became a LADDER (announce
 * 20 → silence → 19 → silence → 16), so the one announce bit became {@code announcedVersion}
 * and the session decode dialect gained a second flag: a 19-established session keeps the
 * CURRENT frame layout (source + codec bytes — {@link #isColumnSourceless} stays false) but
 * its column BODIES arrive in the native section layout, so the decode drain consults
 * {@link #isNativeBodySession} to skip the v20→native translation. Every argument above
 * (announce gating, wire causality, reset discipline) applies per rung unchanged.
 */
public final class V16ClientWire {

    private static final int BIT_V16 = 1;
    private static final int BIT_V19 = 1 << 1;

    /** The set of legacy rungs this CONNECTION has announced (C3 review CRITICAL-1):
     *  the ladder's own next rung must not disarm a pending echo's decode — a slow v19
     *  reply landing after the 16 rung fired used to fall to the foreign arm (fabricated
     *  {@code enabled=false}, silent LOD-off) because the single last-announce was
     *  clobbered. Bits are set sticky by {@link #markAnnouncedVersion} on the way DOWN
     *  the ladder and RETIRED (with their flags) when a HIGHER version is re-asserted
     *  (the downgrade guard's heal) or by {@link #retractAnnounce} (a thrown send). */
    private static volatile int announcedMask = 0;
    private static volatile int announcedVersion = 0;
    private static volatile boolean columnSourceless = false;
    /** Session established at protocol 19 (C3 ladder rung): column BODIES arrive in the
     *  native section layout — the drain skips the v20→native translation — while the
     *  frame layout (source + codec bytes) stays CURRENT. Armed exactly like the v16
     *  flag: announced-19 (sticky this connection) × observed-19, so an unsolicited 19
     *  frame is inert. */
    private static volatile boolean session19 = false;

    private V16ClientWire() {}

    /** Main thread, called by {@code ClientSessionGate} immediately BEFORE each handshake
     *  send with the version being announced. A LEGACY announce joins the sticky
     *  per-connection set; announcing a HIGHER version retires every lower rung's bit
     *  AND its armed flag — the downgrade guard's re-assert is exactly that retirement,
     *  so an unsolicited legacy frame after the heal can never flip column decode out
     *  from under the re-established stream (the original R2-3 prompt-hazard defense,
     *  per rung). */
    public static void markAnnouncedVersion(int protocolVersion) {
        announcedVersion = protocolVersion;
        if (protocolVersion > LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
            announcedMask &= ~BIT_V16;
            columnSourceless = false;
        }
        if (protocolVersion > LSSConstants.V19_COMPAT_PROTOCOL_VERSION) {
            announcedMask &= ~BIT_V19;
            session19 = false;
        }
        if (protocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
            announcedMask |= BIT_V16;
        } else if (protocolVersion == LSSConstants.V19_COMPAT_PROTOCOL_VERSION) {
            announcedMask |= BIT_V19;
        }
    }

    /** Main thread: roll back an announce whose SEND THREW (C3 review m4) — the mark
     *  preceded the send for wire causality, but a never-sent announce must not widen
     *  the acceptance/arming surface for the rest of the connection. */
    public static void retractAnnounce(int failedVersion, int previousVersion) {
        if (failedVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
            announcedMask &= ~BIT_V16;
            columnSourceless = false;
        } else if (failedVersion == LSSConstants.V19_COMPAT_PROTOCOL_VERSION) {
            announcedMask &= ~BIT_V19;
            session19 = false;
        }
        announcedVersion = previousVersion;
    }

    /** Netty decode thread: has this connection announced protocol 19 (sticky — survives
     *  the ladder's own later rungs)? The SessionConfig codec's gate for reading a 19
     *  echo as the CURRENT 4-field layout (visible here by the same mark-before-send
     *  wire causality the class doc argues for the v16 flag). */
    public static boolean hasAnnounced19() {
        return (announcedMask & BIT_V19) != 0;
    }

    /** Netty decode thread: called from {@code SessionConfigS2CPayload}'s decoder with the
     *  frame's protocol version, establishing (before any column decodes on the same thread)
     *  the session's decode dialect: v16 = source-less columns; 19 = native bodies at the
     *  CURRENT frame layout. Arms only when this connection itself announced the matching
     *  version and that announce has not been retired (see {@link #markAnnouncedVersion});
     *  any other frame disarms both. */
    public static void observeSessionConfigVersion(int protocolVersion) {
        columnSourceless = (announcedMask & BIT_V16) != 0
                && protocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION;
        session19 = (announcedMask & BIT_V19) != 0
                && protocolVersion == LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
    }

    /** Netty decode thread: true when the current session is a v16 server, whose VoxelColumn
     *  frames carry no {@code source} byte (added in protocol 18). */
    public static boolean isColumnSourceless() {
        return columnSourceless;
    }

    /** Column BODIES arrive native (a v16 or v19 session) — the decode drain's gate for
     *  skipping the v20→native translation. Volatile-read-safe from the drain thread. */
    public static boolean isNativeBodySession() {
        return columnSourceless || session19;
    }

    /** Main thread: clear all state at a connection boundary (JOIN before the handshake, and
     *  DISCONNECT) so no legacy-dialect state survives into a subsequent connection. */
    public static void reset() {
        announcedMask = 0;
        announcedVersion = 0;
        columnSourceless = false;
        session19 = false;
    }
}
