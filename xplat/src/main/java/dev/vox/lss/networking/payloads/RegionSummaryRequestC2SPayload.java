package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Region summaries (region-summary-sync-plan.md §4): C2S request frame. The BODY is an
 * opaque {@code dev.vox.lss.common.region.RegionSummaryWire} byte[] — one shared codec,
 * two platform carriers (the far-player pattern), so Fabric/Paper wire parity holds by
 * construction. This wrapper adds NO framing beyond the raw body; decode is
 * bounds-checked in RegionSummaryWire, not here.
 */
public record RegionSummaryRequestC2SPayload(byte[] body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RegionSummaryRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_REGION_SUMMARY_REQ));

    public static final StreamCodec<FriendlyByteBuf, RegionSummaryRequestC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBytes(payload.body),
                    buf -> {
                        byte[] body = new byte[buf.readableBytes()];
                        buf.readBytes(body);
                        return new RegionSummaryRequestC2SPayload(body);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
