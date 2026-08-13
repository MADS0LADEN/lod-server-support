package dev.vox.lss.networking;

import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class LSSNetworking {

    public static void registerPayloads() {
        // Client -> Server
        PayloadTypeRegistry.serverboundPlay().register(
                HandshakeC2SPayload.TYPE,
                HandshakeC2SPayload.CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
                BatchChunkRequestC2SPayload.TYPE,
                BatchChunkRequestC2SPayload.CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
                ClientInfoC2SPayload.TYPE,
                ClientInfoC2SPayload.CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.TYPE,
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.CODEC
        );

        // Server -> Client
        PayloadTypeRegistry.clientboundPlay().register(
                SessionConfigS2CPayload.TYPE,
                SessionConfigS2CPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                BatchResponseS2CPayload.TYPE,
                BatchResponseS2CPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                DirtyColumnsS2CPayload.TYPE,
                DirtyColumnsS2CPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                VoxelColumnS2CPayload.TYPE,
                VoxelColumnS2CPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.TYPE,
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.TYPE,
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.CODEC
        );
    }
}
