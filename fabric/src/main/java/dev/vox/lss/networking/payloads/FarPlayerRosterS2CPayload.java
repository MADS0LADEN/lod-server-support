package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Far players (v0.11.0 stage E1): S2C roster frame (FarPlayerWire.encodeRoster bytes).
 * The BODY is an opaque {@code dev.vox.lss.common.farplayers.FarPlayerWire} byte[] —
 * one shared codec, two platform carriers, so Fabric/Paper wire parity holds by
 * construction. This wrapper adds NO framing beyond the raw body (the Paper plugin
 * message carries the identical bytes), and decode is bounds-checked in FarPlayerWire,
 * not here.
 */
public record FarPlayerRosterS2CPayload(byte[] body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FarPlayerRosterS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER));

    public static final StreamCodec<FriendlyByteBuf, FarPlayerRosterS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBytes(payload.body),
                    buf -> {
                        byte[] body = new byte[buf.readableBytes()];
                        buf.readBytes(body);
                        return new FarPlayerRosterS2CPayload(body);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
