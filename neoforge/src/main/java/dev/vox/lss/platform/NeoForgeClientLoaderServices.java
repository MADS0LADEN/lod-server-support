package dev.vox.lss.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The physical-client NeoForge impl: the common impl plus the client send.
 * Installed by {@code LSSNeoClientBootstrap} (dist-gated reflective load —
 * this class references client-only types and must never load on a dedicated
 * server).
 *
 * <p>Client-side sendIfListening (plan §1.2 — the unannounced-channel crash class): the C2S
 * handshake is an unprompted FIRST send, and NeoForge THROWS on a channel the
 * server has not announced (a vanilla server, an LSS-less Fabric server, or
 * the upstream #1913 one-way-announcement case). No connection or an
 * un-negotiated channel is a SILENT no-op — Fabric parity; the client simply
 * stays LOD-inert on that server.
 */
public final class NeoForgeClientLoaderServices extends NeoForgeLoaderServices {

    public static void installProductionClient() {
        LoaderServices.install(new NeoForgeClientLoaderServices());
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            // Connection-level failure (round-3 review): Fabric's send throws here,
            // and the manager's send-failure ladder (count-at-send, fast-cadence
            // disarm) depends on the throw — a silent success kept the tracker armed
            // through the disconnect race.
            throw new IllegalStateException("no connection: client not in game");
        }
        if (!connection.hasChannel(payload.type())) {
            return; // silent no-op: the server would ignore (or refuse) the channel
        }
        try {
            ClientPacketDistributor.sendToServer(payload);
        } catch (UnsupportedOperationException e) {
            // The negotiation race between the pre-check and the send — same silent
            // no-op (the pre-check makes this rare).
        }
    }
}
