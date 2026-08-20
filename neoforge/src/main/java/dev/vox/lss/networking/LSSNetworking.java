package dev.vox.lss.networking;

import dev.vox.lss.networking.client.LSSClientNetworking;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload;
import dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload;
import dev.vox.lss.networking.payloads.RegionSummaryS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge payload registration — the fabric {@code LSSNetworking} twin, all 10
 * {@code lss:*} channels. Two loader-bound invariants (plan §1.2, both fatal if
 * violated): every registration rides an {@code .optional()} registrar — a
 * MANDATORY payload refuses vanilla/Fabric clients at login (the community
 * fork's mistake) — and {@code executesOn(MAIN)} matches Fabric's receiver
 * thread contract on both sides.
 *
 * <p>The registrar version is a constant "1", NOT the LSS protocol version:
 * NeoForge's channel negotiation must never fight our own handshake-driven
 * versioning (the compat rungs v16/v18/v19 live BEHIND the channel).
 */
public final class LSSNetworking {

    private LSSNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1")
                .optional()
                .executesOn(HandlerThread.MAIN);

        // Client -> Server
        registrar.playToServer(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.CODEC,
                LSSServerNetworking::handleHandshakePayload);
        registrar.playToServer(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.CODEC,
                LSSServerNetworking::handleBatchRequestPayload);
        registrar.playToServer(ClientInfoC2SPayload.TYPE, ClientInfoC2SPayload.CODEC,
                LSSServerNetworking::handleClientInfoPayload);
        registrar.playToServer(FarPlayerPrefsC2SPayload.TYPE, FarPlayerPrefsC2SPayload.CODEC,
                LSSServerNetworking::handleFarPlayerPrefsPayload);
        registrar.playToServer(RegionSummaryRequestC2SPayload.TYPE, RegionSummaryRequestC2SPayload.CODEC,
                LSSServerNetworking::handleRegionSummaryRequestPayload);

        // Server -> Client (handlers run on physical clients only; the client half
        // wires real behavior at N-3 — see LSSClientNetworking).
        registrar.playToClient(SessionConfigS2CPayload.TYPE, SessionConfigS2CPayload.CODEC,
                LSSClientNetworking::handleSessionConfigPayload);
        registrar.playToClient(BatchResponseS2CPayload.TYPE, BatchResponseS2CPayload.CODEC,
                LSSClientNetworking::handleBatchResponsePayload);
        registrar.playToClient(DirtyColumnsS2CPayload.TYPE, DirtyColumnsS2CPayload.CODEC,
                LSSClientNetworking::handleDirtyColumnsPayload);
        registrar.playToClient(VoxelColumnS2CPayload.TYPE, VoxelColumnS2CPayload.CODEC,
                LSSClientNetworking::handleVoxelColumnPayload);
        registrar.playToClient(FarPlayerRosterS2CPayload.TYPE, FarPlayerRosterS2CPayload.CODEC,
                LSSClientNetworking::handleFarPlayerRosterPayload);
        registrar.playToClient(FarPlayerUpdatesS2CPayload.TYPE, FarPlayerUpdatesS2CPayload.CODEC,
                LSSClientNetworking::handleFarPlayerUpdatesPayload);
        registrar.playToClient(RegionSummaryS2CPayload.TYPE, RegionSummaryS2CPayload.CODEC,
                LSSClientNetworking::handleRegionSummaryPayload);
    }
}
