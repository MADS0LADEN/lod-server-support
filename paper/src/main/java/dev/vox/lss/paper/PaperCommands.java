package dev.vox.lss.paper;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bukkit command handler for /lsslod stats and /lsslod diag.
 *
 * <p>On Folia, command dispatch is region-threaded (player senders) or global-threaded
 * (console), so these handlers read pump-owned state cross-thread. Every read on this path
 * is a concurrent structure (the players CHM) or a stale-tolerable primitive (volatile
 * counters, plain int/long gauges like the generation active-count and the
 * TickDiagnostics/SharedBandwidthLimiter fields) — audited 2026-07-02; nothing iterates a
 * non-concurrent collection off the pump.
 */
public class PaperCommands implements CommandExecutor, TabCompleter {
    private final Supplier<PaperRequestProcessingService> serviceSupplier;
    private final Supplier<PaperConfig> configSupplier;

    public PaperCommands(LSSPaperPlugin plugin) {
        this(plugin::getRequestService, plugin::getLssConfig);
    }

    // Package-visible seam: lets T1 tests drive the command paths without a JavaPlugin
    // instance. Suppliers preserve the late binding of plugin.getRequestService().
    PaperCommands(Supplier<PaperRequestProcessingService> serviceSupplier,
                  Supplier<PaperConfig> configSupplier) {
        this.serviceSupplier = serviceSupplier;
        this.configSupplier = configSupplier;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /" + label + " <stats|diag|store>");
            return true;
        }

        var service = this.serviceSupplier.get();
        if (service == null) {
            sender.sendMessage(Brand.shortName() + " LOD request processing is not active");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> showStats(sender, service);
            case "diag" -> showDiagnostics(sender, service);
            case "store" -> storeCommand(sender, label, service, args);
            default -> sender.sendMessage("Usage: /" + label + " <stats|diag|store>");
        }

        return true;
    }

    /** The store ops verbs (4-agent round R3: Paper shipped the store with no ops
     *  surface at all — and Paper is the platform whose staleness bound is the
     *  periodic resweep, so it needs the remediation lever MOST). Backfill verbs stay
     *  Fabric-only for now (recorded deferral: no Paper backfill wiring). Thread-safe
     *  from Folia's region-threaded dispatch: diagnostics reads are volatile gauges,
     *  invalidate-all is tombstones + a control-queue offer. */
    private void storeCommand(CommandSender sender, String label,
                              PaperRequestProcessingService service, String[] args) {
        var store = service.getLodStore();
        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            if (store == null) {
                sender.sendMessage("LOD store: off/unavailable");
                return;
            }
            sender.sendMessage("LOD store: " + store.diagnostics().formatToken(store.mode())
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
                    // Memory-tier visibility (review B1): db/wal/evicted are SQL-only
                    // and rendered a thrashing memory store as all-zero.
                    + (store.diagnostics().getMemBytes() > 0
                            ? " mem=" + (store.diagnostics().getMemBytes() >> 20) + "MB"
                                    + " mem_evicted=" + store.diagnostics().getMemEvictions()
                            : ""));
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("invalidate")
                && args[2].equalsIgnoreCase("all")) {
            if (store == null) {
                sender.sendMessage("LOD store not active");
                return;
            }
            if (!service.invalidateStoreAllDimensions()) {
                sender.sendMessage("Invalidate-all requires the persistent store — this session degraded to the in-memory tier at boot (SQLite could not open; see the startup warning)");
                return;
            }
            sender.sendMessage("LOD store: dropping all rows (background) — re-warms from serves");
        } else {
            sender.sendMessage("Usage: /" + label + " store <status|invalidate all>");
        }
    }

    private void showStats(CommandSender sender, PaperRequestProcessingService service) {
        var players = service.getPlayers();
        if (players.isEmpty()) {
            sender.sendMessage("No players connected with " + Brand.shortName());
            return;
        }

        sender.sendMessage("=== " + Brand.shortName() + " LOD Request Stats ===");
        for (var state : players.values()) {
            sender.sendMessage(DiagnosticsFormatter.formatStatsLine(state));
        }
    }

    private void showDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var config = this.configSupplier.get();
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
                .withYieldLine(DiagnosticsFormatter.yieldDiagLineOrNull(
                        config.lodYieldsToVanillaTransport, service.getTickDiag()))
                .withXrayLine(xrayDiagLine());

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            sender.sendMessage(line);
        }
    }

    private static String xrayDiagLine() {
        var manager = PaperXrayMaskManager.current();
        return manager != null ? manager.diagLine() : "Xray: active=off, masked_sections=0";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("stats", "diag", "store").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("store")) {
            return List.of("status", "invalidate").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("store")
                && args[1].equalsIgnoreCase("invalidate")) {
            return List.of("all");
        }
        return Collections.emptyList();
    }
}
