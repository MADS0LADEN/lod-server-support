package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Stamped up_to_date (stamped-up-to-date-plan.md §3): S2C verification-stamp frame.
 * The BODY is an opaque {@code dev.vox.lss.common.region.ColumnStampsWire} byte[] —
 * one shared codec, two platform carriers (the region-summary/far-player pattern).
 * This wrapper adds NO framing beyond the raw body; decode is bounds-checked in
 * ColumnStampsWire (incl. the semantic stamp bounds), not here.
 */
public record ColumnStampsS2CPayload(byte[] body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ColumnStampsS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_COL_STAMPS));

    public static final StreamCodec<FriendlyByteBuf, ColumnStampsS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBytes(payload.body),
                    buf -> {
                        byte[] body = new byte[buf.readableBytes()];
                        buf.readBytes(body);
                        return new ColumnStampsS2CPayload(body);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
