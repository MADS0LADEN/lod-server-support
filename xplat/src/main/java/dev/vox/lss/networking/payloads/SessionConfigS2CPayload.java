package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Four fields since the server-owned-generation fold into v17: both per-player concurrency
 * caps left the wire — they are server-internal admission limiters, and no client budget
 * derives from them (the scanner's want-set budget is the WANT_SET_BUDGET constant).
 *
 * <p><b>v16 compat:</b> a legacy protocol-16 session ({@code enableV16Compat}) is replied
 * to with {@link #v16Legacy}, which encodes the OLD 6-field layout — version echoing 16
 * (the v0.6.2 client's codec hard-gates on that leading VarInt and disables itself
 * otherwise) with the two concurrency-cap VarInts restored between lodDistance and
 * generationEnabled. The legacy components are server-encode-only: the read side decodes
 * only the current 4-field layout and always produces {@code v16Wire=false}, and when the
 * flag is off the encode is byte-identical to the pre-compat 4-field layout (pinned by
 * the wire tests). The v18 compat rung needs no shape of its own here — it is the SAME
 * 4-field encode with the version value 18 (the encoder never branches on the value).
 */
public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        boolean generationEnabled,
        int legacySyncCap,
        int legacyGenCap,
        boolean v16Wire,
        int serverDataVersion
) implements CustomPacketPayload {

    /** Canonical current-protocol shape (the only one the client ever decodes). The
     *  data-version append rides ONLY version-20 frames (XVER §2.2): the v19/v18 echo
     *  reuses this ctor with their version value and the encoder omits the append —
     *  their strict 4-field clients would hard-kick on a trailing byte. */
    public SessionConfigS2CPayload(int protocolVersion, boolean enabled,
                                   int lodDistanceChunks, boolean generationEnabled,
                                   int serverDataVersion) {
        this(protocolVersion, enabled, lodDistanceChunks, generationEnabled, 0, 0, false,
                serverDataVersion);
    }

    /** Test/legacy convenience: no data version (encodes without the append for any
     *  non-20 version anyway; a version-20 frame built this way appends 0). */
    public SessionConfigS2CPayload(int protocolVersion, boolean enabled,
                                   int lodDistanceChunks, boolean generationEnabled) {
        this(protocolVersion, enabled, lodDistanceChunks, generationEnabled, 0);
    }

    /** The v16 compat reply: 6-field legacy layout echoing protocol version 16. The caps are
     *  the client's PACING (in-flight ceilings and scan budget = syncCap x 4), so callers pass
     *  the server's real admission values. */
    public static SessionConfigS2CPayload v16Legacy(boolean enabled, int lodDistanceChunks,
                                                    int syncCap, int genCap,
                                                    boolean generationEnabled) {
        return new SessionConfigS2CPayload(LSSConstants.V16_COMPAT_PROTOCOL_VERSION, enabled,
                lodDistanceChunks, generationEnabled, syncCap, genCap, true, 0);
    }

    public static final CustomPacketPayload.Type<SessionConfigS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_SESSION_CONFIG));

    public static final StreamCodec<FriendlyByteBuf, SessionConfigS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.protocolVersion);
                        buf.writeBoolean(payload.enabled);
                        buf.writeVarInt(payload.lodDistanceChunks);
                        if (payload.v16Wire) {
                            // Legacy 6-field layout: the two caps sit between lodDistance
                            // and generationEnabled (v0.6.2 SessionConfigS2CPayload order).
                            buf.writeVarInt(payload.legacySyncCap);
                            buf.writeVarInt(payload.legacyGenCap);
                        }
                        buf.writeBoolean(payload.generationEnabled);
                        if (payload.protocolVersion == LSSConstants.PROTOCOL_VERSION
                                && !payload.v16Wire) {
                            // v20-only append (XVER §2.2): the client codec branches
                            // per-frame on the leading version, so ONLY the version-20
                            // arm reads this — the v19/v18 echoes must stay 4-field.
                            buf.writeVarInt(payload.serverDataVersion);
                        }
                    },
                    buf -> {
                        int version = buf.readVarInt();
                        // Establish (on the netty decode thread, before any column decodes)
                        // whether the SUBSEQUENT VoxelColumn frames omit the source byte —
                        // true only for a v16 server. See V16ClientWire.
                        V16ClientWire.observeSessionConfigVersion(version);
                        // The C3 ladder's 19 rung: a client whose CONNECTION announced 19
                        // (the ladder fallback, or the soak lever's initial rung) decodes
                        // the server's 19 echo exactly as a real v0.9.x client would — the
                        // same 4-field layout this arm reads (the data-version append is
                        // version-20-only, so isReadable() is false and it decodes 0).
                        // STICKY announce memory (review CRITICAL-1): the ladder's own 16
                        // rung firing 10 s later must not push a still-in-flight 19 echo
                        // to the foreign arm — that fabricated (19, enabled=false) and
                        // silently disabled LOD for the session. Gated on the client's own
                        // announce (mark-before-send causality, see V16ClientWire), so an
                        // unsolicited 19 frame still falls to the foreign arm below.
                        boolean announced19 = V16ClientWire.hasAnnounced19()
                                && version == LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
                        if (version == LSSConstants.PROTOCOL_VERSION || announced19) {
                            boolean enabled = buf.readBoolean();
                            int lodDist = buf.readVarInt();
                            boolean genEnabled = buf.readBoolean();
                            // Tolerate absence (a same-version peer built pre-append —
                            // dev-window frames): absent decodes as 0 = unknown.
                            int dataVersion = buf.isReadable() ? buf.readVarInt() : 0;
                            return new SessionConfigS2CPayload(version, enabled, lodDist,
                                    genEnabled, dataVersion);
                        }
                        if (version == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
                            // Client v16 backward compat: an old server's 6-field layout — the
                            // two concurrency-cap VarInts sit between lodDistance and
                            // generationEnabled (the exact mirror of the v16Legacy ENCODE
                            // above). A v18 client CAN receive this without having announced
                            // 16: a Paper /reload re-attach prompt speaks the v16 dialect
                            // (this branch is per-frame self-describing, so the decode is
                            // safe); the session gate's downgrade guard answers it, and the
                            // sourceless-column arming above stays inert unless this client
                            // itself last announced 16 (V16ClientWire.markAnnouncedVersion).
                            boolean enabled = buf.readBoolean();
                            int lodDist = buf.readVarInt();
                            int syncCap = buf.readVarInt();
                            int genCap = buf.readVarInt();
                            boolean genEnabled = buf.readBoolean();
                            return SessionConfigS2CPayload.v16Legacy(enabled, lodDist, syncCap,
                                    genCap, genEnabled);
                        }
                        // Foreign layout (e.g. a v15 server's 10-field config). The version
                        // VarInt is the first field in every layout, so drain the rest to
                        // avoid a trailing-bytes decoder kick and let the handler's version
                        // gate log the mismatch and disable LSS.
                        buf.skipBytes(buf.readableBytes());
                        return new SessionConfigS2CPayload(version, false, 0, false);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
