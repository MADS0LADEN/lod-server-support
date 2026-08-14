package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S sidecar carrying the client's MC data version (protocol 20, XVER §2.2). Sent
 * alongside the handshake announce — NEVER inside it: the handshake byte shape is
 * frozen at two VarInts forever (a trailing byte is a decoder kick on every shipped
 * Fabric server), so new client facts get their own channel. Legacy servers silently
 * discard the unregistered channel; a v20 server treats absence as "legacy client".
 * Consumed as diagnostics + the Via mismatch guard's input (C5) — decoding v20 data
 * never needs it.
 */
public record ClientInfoC2SPayload(int dataVersion) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientInfoC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_CLIENT_INFO));

    public static final StreamCodec<FriendlyByteBuf, ClientInfoC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.dataVersion),
                    buf -> {
                        int dataVersion = buf.readVarInt();
                        // Forward-tolerant: a future client may append fields — drain so
                        // trailing bytes never kick (the same stance as SessionConfig's
                        // foreign-layout arm).
                        buf.skipBytes(buf.readableBytes());
                        return new ClientInfoC2SPayload(dataVersion);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
