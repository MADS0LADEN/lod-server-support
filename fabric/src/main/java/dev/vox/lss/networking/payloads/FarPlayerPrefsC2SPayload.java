package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Far players (v0.11.0 stage E1): C2S prefs frame (FarPlayerWire.encodePrefs bytes).
 * The BODY is an opaque {@code dev.vox.lss.common.farplayers.FarPlayerWire} byte[] —
 * one shared codec, two platform carriers, so Fabric/Paper wire parity holds by
 * construction. This wrapper adds NO framing beyond the raw body (the Paper plugin
 * message carries the identical bytes), and decode is bounds-checked in FarPlayerWire,
 * not here.
 */
public record FarPlayerPrefsC2SPayload(byte[] body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FarPlayerPrefsC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_FAR_PLAYER_PREFS));

    public static final StreamCodec<FriendlyByteBuf, FarPlayerPrefsC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBytes(payload.body),
                    buf -> {
                        byte[] body = new byte[buf.readableBytes()];
                        buf.readBytes(body);
                        return new FarPlayerPrefsC2SPayload(body);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
