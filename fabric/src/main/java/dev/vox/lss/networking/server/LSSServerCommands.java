package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.config.LSSServerConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;


class LSSServerCommands {
    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal(Brand.serverCommand())
                            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                            .then(Commands.literal("stats")
                                    .executes(ctx -> showStats(ctx.getSource()))
                            )
                            .then(Commands.literal("diag")
                                    .executes(ctx -> showDiagnostics(ctx.getSource()))
                            )
                            .then(Commands.literal("store")
                                    .then(Commands.literal("backfill")
                                            .then(Commands.literal("start")
                                                    .executes(ctx -> backfill(ctx.getSource(), "start")))
                                            .then(Commands.literal("stop")
                                                    .executes(ctx -> backfill(ctx.getSource(), "stop")))
                                            .then(Commands.literal("status")
                                                    .executes(ctx -> backfill(ctx.getSource(), "status")))
                                    )
                            )
            );
        });
    }

    /** The Phase 4 backfill verbs. Requires lodStore=full with a live SQLite store
     *  (the backfill's only target — a memory store's work would evaporate at restart). */
    private static int backfill(CommandSourceStack source, String verb) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }
        var backfill = service.getStoreBackfill();
        if (backfill == null) {
            source.sendFailure(Component.literal(
                    "Store backfill unavailable — requires lodStore=full with a running SQLite store"));
            return 0;
        }
        switch (verb) {
            case "start" -> {
                boolean started = backfill.start();
                source.sendSuccess(() -> Component.literal(started
                        ? "Store backfill started (background, yields to players)"
                        : "Store backfill already running"), true);
            }
            case "stop" -> {
                boolean stopped = backfill.stop();
                source.sendSuccess(() -> Component.literal(stopped
                        ? "Store backfill stop requested (finishes the current column)"
                        : "Store backfill is not running"), true);
            }
            default -> source.sendSuccess(() -> Component.literal(
                    "Store backfill: " + backfill.statusLine()), false);
        }
        return 1;
    }

    private static int showStats(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }

        var players = service.getPlayers();
        if (players.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No players connected with " + Brand.shortName()), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("=== " + Brand.shortName() + " LOD Request Stats ==="), false);
        for (var state : players.values()) {
            String line = DiagnosticsFormatter.formatStatsLine(state);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int showDiagnostics(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }

        var config = LSSServerConfig.CONFIG;
        var genService = service.getGenerationService();
        var data = DiagnosticsFormatter.collectDiagData(
                config.enabled, config.lodDistanceChunks,
                config.bytesPerSecondLimitPerPlayer, config.bytesPerSecondLimitGlobal,
                config.sendQueueLimitPerPlayer,
                service.getUptimeSeconds(), service.getTickDiagnostics(), service.getWindowBandwidthRate(),
                service.getTickDiag().getTotalSectionsSent(), service.getTickDiag().getTotalBytesSent(),
                service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                service.getBandwidthLimiter(),
                genService != null ? genService.getDiagnostics() : null,
                // LIVE store mode, not the config's ask (review MINOR-3): a codec-probe
                // degrade renders store=unavailable, never a lying store=memory h=0.
                dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore)
                        == dev.vox.lss.common.store.LodStoreMode.OFF
                        ? dev.vox.lss.common.store.LodStoreMode.OFF
                        : (service.getLodStore() != null ? service.getLodStore().mode() : null),
                service.getOffThreadProcessor().getStoreDiagnostics(),
                service.getPlayers().values()
        ).withV16Line(service.getV16CompatManager().diagLineOrNull())
                .withXrayLine(xrayDiagLine());

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static String xrayDiagLine() {
        var manager = XrayMaskManager.current();
        return manager != null ? manager.diagLine() : "Xray: active=off, masked_sections=0";
    }
}
