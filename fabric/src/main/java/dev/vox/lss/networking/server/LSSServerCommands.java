package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
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
                                    .then(Commands.literal("status")
                                            .executes(ctx -> storeStatus(ctx.getSource())))
                                    .then(Commands.literal("invalidate")
                                            .then(Commands.literal("all")
                                                    .executes(ctx -> storeInvalidateAll(ctx.getSource()))))
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

    /** Phase 5 ops: one-line store health for "are LODs stale?" triage. */
    private static int storeStatus(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }
        var store = service.getLodStore();
        if (store == null) {
            source.sendSuccess(() -> Component.literal("LOD store: off/unavailable"), false);
            return 1;
        }
        String line = "LOD store: " + store.diagnostics().formatToken(store.mode())
                // Review B1: a latched store must LOOK dead in the triage tool —
                // "latched" / "sweeping" / "ok", never a healthy token with frozen
                // counters.
                + " state=" + store.stateToken()
                + " db=" + (store.diagnostics().getDbBytes() >> 20) + "MB wal="
                + (store.diagnostics().getWalBytes() >> 20) + "MB sweep_drops="
                + store.diagnostics().getSweepDrops()
                // The one-shot cap log (§2) points here — the ongoing capped
                // steady-state must stay diagnosable without any log line.
                + " evicted=" + store.diagnostics().getSqlEvictions()
                // C4: background-migration progress (empty once every row is v20).
                + store.migrationStatusToken()
                // Memory-tier visibility (review B1): db/wal/evicted are SQL-only and
                // rendered a thrashing memory store as all-zero.
                + (store.diagnostics().getMemBytes() > 0
                        ? " mem=" + (store.diagnostics().getMemBytes() >> 20) + "MB"
                                + " mem_evicted=" + store.diagnostics().getMemEvictions()
                        : "");
        var backfill = service.getStoreBackfill();
        String bf = backfill == null ? "" : " | backfill: " + backfill.statusLine();
        source.sendSuccess(() -> Component.literal(line + bf), false);
        return 1;
    }

    /** Phase 5 ops: the admin remediation lever for "LODs look stale" — tombstones and
     *  deletes every stored row in every dimension the service knows (backfill
     *  done-marks reset with them); the store re-warms from serves (and the backfill,
     *  if enabled). The timestamp cache is deliberately NOT touched — its stamps
     *  describe REGION truth, not store contents (see
     *  RequestProcessingService.invalidateStoreAllDimensions). */
    private static int storeInvalidateAll(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null || service.getLodStore() == null) {
            source.sendFailure(Component.literal("LOD store not active"));
            return 0;
        }
        if (!service.invalidateStoreAllDimensions()) {
            source.sendFailure(Component.literal(
                    "Invalidate-all requires the persistent store — this session degraded to the in-memory tier at boot (SQLite could not open; see the startup warning)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "LOD store: dropping all rows (background) — re-warms from serves"), true);
        return 1;
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
                config.bytesPerSecondPerPlayer(), config.bytesPerSecondGlobal(),
                config.sendQueueLimitPerPlayer,
                service.getUptimeSeconds(), service.getTickDiagnostics(), service.getWindowBandwidthRate(),
                service.getTickDiag().getTotalSectionsSent(), service.getTickDiag().getTotalBytesSent(),
                service.getTickDiag().getTotalWireBytesSent(),
                service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                service.getBandwidthLimiter(),
                genService != null ? genService.getDiagnostics() : null,
                // LIVE store mode, not the config's ask (review MINOR-3): a codec-probe
                // degrade renders store=unavailable, never a lying store=memory h=0.
                // enabled=false is an OFF store, not a degraded one — without that term
                // a disabled server rendered store=unavailable, which formatToken
                // documents as "requested but the codec native failed", sending admins
                // after a zstd problem that does not exist (v0.9.0 final review).
                !config.enabled
                        || dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore)
                                == dev.vox.lss.common.store.LodStoreMode.OFF
                        ? dev.vox.lss.common.store.LodStoreMode.OFF
                        : (service.getLodStore() != null ? service.getLodStore().mode() : null),
                service.getOffThreadProcessor().getStoreDiagnostics(),
                service.getPlayers().values()
        ).withV16Line(service.getV16CompatManager().diagLineOrNull())
                .withV18Line(service.getDialectTracker().diagLine())
                .withXrayLine(xrayDiagLine())
                .withMoveTraceLine(moveTraceDiagLineOrNull())
                .withYieldLine(DiagnosticsFormatter.yieldDiagLineOrNull(
                        config.lodYieldsToVanillaTransport, service.getTickDiag()));

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static String xrayDiagLine() {
        var manager = XrayMaskManager.current();
        return manager != null ? manager.diagLine() : "Xray: active=off, masked_sections=0";
    }

    /** Present ONLY while the tracer is active (move-desync-tracer-plan.md §2) — it is the
     *  post-deploy rung verification and the silent-rejection rate in one RCON call. */
    private static String moveTraceDiagLineOrNull() {
        var tracer = dev.vox.lss.trace.MoveDesyncTracer.active();
        return tracer != null ? tracer.diagLine() : null;
    }
}
