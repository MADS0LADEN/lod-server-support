package dev.vox.lss.networking.client;

import com.mojang.brigadier.Command;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.config.LSSClientConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class LSSClientCommands {
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Command literal is branded (lss / vss); it is a LOCAL client command and
            // never crosses the wire, so it does not affect LSS<->VSS compatibility.
            dispatcher.register(ClientCommands.literal(Brand.clientCommand())
                    .then(ClientCommands.literal("clearcache")
                            .executes(context -> {
                                var manager = LSSClientNetworking.getRequestManager();
                                if (manager != null) {
                                    manager.flushCache();
                                    context.getSource().sendFeedback(Component.literal(
                                            Brand.shortName() + " column cache cleared for current server. Chunks will be re-requested."));
                                } else {
                                    ColumnCacheStore.clearAll();
                                    context.getSource().sendFeedback(Component.literal(
                                            Brand.shortName() + " column cache cleared for all servers."));
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .then(ClientCommands.literal("reset")
                            .executes(context -> runReset(context.getSource(), false))
                            .then(ClientCommands.literal("confirm")
                                    .executes(context -> runReset(context.getSource(), true)))
                    )
                    .then(ClientCommands.literal("diag")
                            .executes(context -> {
                                showDiagnostics(context.getSource());
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .then(ClientCommands.literal("trace")
                            .executes(context -> {
                                var result = ClientTraceLog.toggle();
                                if (result.path() != null) {
                                    context.getSource().sendFeedback(Component.literal(
                                            Brand.shortName() + " trace STARTED: " + result.path()).withStyle(ChatFormatting.GOLD));
                                } else if (result.failed()) {
                                    context.getSource().sendFeedback(Component.literal(
                                            Brand.shortName() + " trace FAILED to start — see the log.").withStyle(ChatFormatting.RED));
                                } else {
                                    context.getSource().sendFeedback(Component.literal(
                                            Brand.shortName() + " trace stopped.").withStyle(ChatFormatting.GOLD));
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        });
    }

    /**
     * The /lss reset production wiring (v0.11.0 stage D): the sequence itself lives in
     * {@link ResetCoordinator} (seam-injected, JUnit-pinned ordering); this method only
     * binds the live collaborators. Main client thread (client commands dispatch there —
     * the same thread Voxy's own command and login/disconnect mixins run on, so no
     * concurrent-lifecycle race).
     */
    private static int runReset(FabricClientCommandSource source, boolean confirmed) {
        var manager = LSSClientNetworking.getRequestManager();
        ResetCoordinator.run(new ResetCoordinator.Deps(
                manager != null,
                () -> {
                    if (manager != null) {
                        LSSClientNetworking.reportUndispatchedColumns(manager);
                    }
                    // Await even without a manager (review n4): a reset typed right
                    // after a disconnect can race the disconnect drain's final dispatch
                    // into the wipe window.
                    if (!LSSClientNetworking.awaitDecodeIdle(2_000)) {
                        dev.vox.lss.common.LSSLogger.warn(
                                "Reset: decode drain still busy after 2s — proceeding "
                                        + "(the wipe is IO-contained regardless)");
                    }
                },
                dev.vox.lss.compat.ModCompat::resetVoxyLods,
                () -> {
                    if (manager != null) manager.flushCache();
                },
                ColumnCacheStore::clearAll,
                FarPlayerClientSupport::resetAndResubscribe,
                line -> source.sendFeedback(Component.literal(line).withStyle(ChatFormatting.GOLD))),
                confirmed);
        return Command.SINGLE_SUCCESS;
    }

    private static void showDiagnostics(FabricClientCommandSource source) {
        var manager = LSSClientNetworking.getRequestManager();
        if (manager == null || !LSSClientNetworking.isServerEnabled()) {
            source.sendFeedback(Component.literal(Brand.shortName() + " is not active on this server").withStyle(ChatFormatting.RED));
            return;
        }

        source.sendFeedback(Component.literal("=== " + Brand.shortName() + " Client Diagnostics ===").withStyle(ChatFormatting.GOLD));

        // Connection line
        int serverDist = LSSClientNetworking.getServerLodDistance();
        int effectiveDist = manager.getEffectiveLodDistanceChunks();
        source.sendFeedback(Component.literal(String.format(
                "Connection: server_lod_dist=%d, effective_dist=%d",
                serverDist, effectiveDist
        )).withStyle(ChatFormatting.GRAY));

        // Throughput line
        long received = LSSClientNetworking.getColumnsReceived();
        long bytes = LSSClientNetworking.getBytesReceived();
        long dropped = LSSClientNetworking.getColumnsDropped();
        long startMs = LSSClientNetworking.getConnectionStartMs();
        long uptimeSec = startMs > 0 ? (System.currentTimeMillis() - startMs) / 1000 : 0;
        source.sendFeedback(Component.literal(String.format(
                "Throughput: received=%d (%s), dropped=%d, recv_rate=%s/s, req_rate=%s/s, uptime=%s",
                received, DiagnosticsFormatter.formatBytes(bytes), dropped,
                DiagnosticsFormatter.formatRate(manager.getReceiveRate()), DiagnosticsFormatter.formatRate(manager.getRequestRate()),
                DiagnosticsFormatter.formatUptime(uptimeSec)
        )).withStyle(ChatFormatting.GRAY));

        // Queue line
        int queued = LSSClientNetworking.getQueuedColumnCount();
        source.sendFeedback(Component.literal(String.format(
                "Queue: queued=%d/%d",
                queued, ClientColumnProcessor.MAX_QUEUED_COLUMNS
        )).withStyle(ChatFormatting.GRAY));

        // Columns line
        int receivedCols = manager.getReceivedColumnCount();
        int empty = manager.getEmptyColumnCount();
        int dirty = manager.getDirtyColumnCount();
        source.sendFeedback(Component.literal(String.format(
                "Columns: received=%d, empty=%d, dirty=%d, ingest_failed=%d",
                receivedCols, empty, dirty, manager.getTotalIngestFailures()
        )).withStyle(ChatFormatting.GRAY));

        // Responses line
        source.sendFeedback(Component.literal(String.format(
                "Responses: columns=%d, up_to_date=%d, not_generated=%d",
                manager.getTotalColumnsReceived(), manager.getTotalUpToDate(),
                manager.getTotalNotGenerated()
        )).withStyle(ChatFormatting.GRAY));

        // Requests line
        source.sendFeedback(Component.literal(String.format(
                "Requests: send_cycles=%d, total_requested=%d",
                manager.getTotalSendCycles(), manager.getTotalPositionsRequested()
        )).withStyle(ChatFormatting.GRAY));

        // Scan line
        int confirmedRing = manager.getConfirmedRing();
        int scanRing = manager.getScanRing();
        int maxRing = manager.getEffectiveLodDistanceChunks();
        source.sendFeedback(Component.literal(String.format(
                "Scan: confirmed=%d, scanning=%d/%d, missing_vanilla=%d, fast=%d",
                confirmedRing, scanRing, maxRing, manager.getMissingVanillaChunks(),
                manager.getFastScans()
        )).withStyle(ChatFormatting.GRAY));

        // Budget line (ingest_backlog: the consumer-reported pending sections driving the
        // #71 taper/halt; -1 = no consumer reports. rate_cap: the manual column-rate cap,
        // 0=off; rate_gated: TICKS the cap's spacing gate held a would-be fast fire back
        // (one delayed fire can count several) — nonzero means the knob is binding, the
        // discriminator for weak-client reports)
        int budget = manager.getLastBudget();
        int lastQueued = manager.getLastQueued();
        // Far players (E1, conditional slot — rendered once any far-player state
        // exists; inert sessions never see it).
        var farTracker = FarPlayerClientSupport.tracker();
        if (farTracker.rostersApplied() > 0 || farTracker.trackedCount() > 0) {
            source.sendFeedback(Component.literal(farTracker.diagLine())
                    .withStyle(ChatFormatting.GRAY));
        }

        source.sendFeedback(Component.literal(String.format(
                "Budget: used=%d/%d, ingest_backlog=%d, rate_cap=%d, governed=%s, rate_gated=%d",
                lastQueued, budget, manager.getLastIngestBacklog(),
                LSSClientConfig.CONFIG.lodColumnsPerSecondLimit,
                manager.getGovernedRateLabel(), manager.getRateGated()
        )).withStyle(ChatFormatting.GRAY));
    }
}
