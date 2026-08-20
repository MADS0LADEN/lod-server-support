package dev.vox.lss.paper;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Paper plugin entry point for LOD Server Support.
 * Registers Plugin Messaging channels, handles handshake/request lifecycle,
 * and ticks the request processing service on the server main thread.
 *
 * <p>The environment-free glue — enable-plan ordering ({@link #runEnablePlan}),
 * plugin-message dispatch containment ({@link #dispatchPluginMessage}), and the
 * handshake reply/registration wiring ({@link #handleHandshake(byte[], String,
 * PaperConfig, boolean, SessionConfigSender, HandshakeRegistrar)}) — is static and
 * package-private so it is testable without a Bukkit server; the instance methods
 * only bind the production environment.
 */
public class LSSPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    private PaperConfig lssConfig;
    private volatile PaperRequestProcessingService requestService;

    // lss:client_info sidecar facts (XVER §2.2): the client's MC data version, keyed by
    // UUID, swept at quit. Absence = legacy client (no sidecar channel). Consumed as
    // diagnostics + the C5 Via-guard input.
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer>
            CLIENT_DATA_VERSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return CLIENT_DATA_VERSIONS.get(uuid);
    }

    /**
     * The onEnable step set, in the order {@link #runEnablePlan} drives them. The
     * production implementation lives in {@link #onEnable}; the interface is a test
     * seam (the plan's step order and enabled gate are pinned without a Bukkit server,
     * and /reload re-runs the identical sequence).
     */
    interface EnableSteps {
        void loadBranding();

        PaperConfig loadConfig();

        void registerChannels();

        void registerQuitListener();

        PaperRequestProcessingService startService(PaperConfig config);

        void registerWorldHandler(PaperRequestProcessingService service, PaperConfig config);

        void registerCommands();

        void scheduleServiceTick();

        void initSoakBridge();
    }

    /**
     * Executes the enable plan. Step order is load-bearing: config before channels
     * (handlers read it), service before the world handler (it feeds the service's
     * dirty tracker), and the soak bridge last so the driver sees a fully wired plugin.
     * /reload runs onDisable then onEnable, so this sequence is also the re-enable
     * contract.
     */
    static void runEnablePlan(EnableSteps steps) {
        // FIRST: resolve display branding before any service/thread/log line is created.
        steps.loadBranding();
        var config = steps.loadConfig();

        // Register incoming channels (C2S)
        // Note: S2C packets are sent directly via NMS (bypassing Bukkit's
        // sendPluginMessage channel check), so no outgoing registration needed.
        steps.registerChannels();
        // Register event listener for player quit
        steps.registerQuitListener();

        // Start processing service
        var service = steps.startService(config);
        LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");

        // Register dirty chunk event listeners. enabled=false gates here (mirrors Fabric's
        // ChunkSaveDataHook gate): the service tick — and so the dirty-broadcast drain — is
        // disabled, so marking would grow the DirtyColumnTracker without bound for the whole
        // server run. enabled is immutable per run, so skipping registration is safe.
        if (config.enabled) {
            steps.registerWorldHandler(service, config);
        }

        // Register command
        steps.registerCommands();

        // Tick the processing service every server tick (50ms)
        steps.scheduleServiceTick();

        // Dev-only soak harness (no-op unless -Dlss.soak.scenario is set)
        steps.initSoakBridge();

        LSSLogger.info(Brand.displayName() + " (Paper) enabled");
    }

    @Override
    public void onEnable() {
        runEnablePlan(new EnableSteps() {
            @Override
            public void loadBranding() {
                Brand.load(getClassLoader());
            }

            @Override
            public PaperConfig loadConfig() {
                lssConfig = PaperConfig.load(getDataFolder().toPath());
                return lssConfig;
            }

            @Override
            public void registerChannels() {
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_HANDSHAKE, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_CHUNK_REQUEST, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_CLIENT_INFO, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_FAR_PLAYER_PREFS, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_REGION_SUMMARY_REQ, LSSPaperPlugin.this);
            }

            @Override
            public void registerQuitListener() {
                getServer().getPluginManager().registerEvents(LSSPaperPlugin.this, LSSPaperPlugin.this);
            }

            @Override
            public PaperRequestProcessingService startService(PaperConfig config) {
                var nmsServer = ((CraftServer) getServer()).getServer();
                requestService = new PaperRequestProcessingService(nmsServer, LSSPaperPlugin.this, config);
                return requestService;
            }

            @Override
            public void registerWorldHandler(PaperRequestProcessingService service, PaperConfig config) {
                var worldHandler = new PaperWorldHandler(LSSPaperPlugin.this, service.getDirtyTracker());
                worldHandler.registerUpdateListeners(config.updateEvents);
            }

            @Override
            public void registerCommands() {
                // Read the declared command name from plugin.yml rather than hardcoding it,
                // so the Voxy Server Side repackage (which rewrites plugin.yml's command key
                // lsslod -> vsslod) registers its executor without a code fork. The plugin
                // declares exactly one command.
                var cmdName = getDescription().getCommands().keySet().stream().findFirst().orElse(null);
                var cmd = cmdName == null ? null : getCommand(cmdName);
                if (cmd != null) {
                    var executor = new PaperCommands(LSSPaperPlugin.this);
                    cmd.setExecutor(executor);
                    cmd.setTabCompleter(executor);
                }
            }

            @Override
            public void scheduleServiceTick() {
                // GlobalRegionScheduler, not BukkitScheduler: on Folia the legacy scheduler
                // throws UnsupportedOperationException; on plain Paper this runs on the main
                // thread every tick, exactly like the BukkitRunnable it replaces. The
                // global-region thread is the plugin's single pump — every single-owner
                // structure in the pipeline hangs off this cadence (Folia design spec §3).
                getServer().getGlobalRegionScheduler().runAtFixedRate(LSSPaperPlugin.this,
                        scheduledTask -> {
                            var service = requestService;
                            if (service != null) {
                                service.tick();
                            }
                        }, 1L, 1L);
            }

            @Override
            public void initSoakBridge() {
                PaperSoakBridge.init(LSSPaperPlugin.this);
            }
        });
    }

    @Override
    public void onDisable() {
        // Null the field BEFORE shutting down so the next pump fire no-ops — a runtime
        // plugin-manager disable can arrive from a region thread while the pump is mid-tick
        // (the service's shuttingDown flag covers the one already-in-flight tick).
        var service = this.requestService;
        this.requestService = null;
        // Static sidecar facts must not survive /reload or a plugin-manager disable —
        // players who quit while disabled would leak entries forever (review C1-9).
        CLIENT_DATA_VERSIONS.clear();
        // Unregister the channels BEFORE shutdown (2026-08-05 review H5): a frame already
        // dispatched into onPluginMessageReceived proceeds with its captured service
        // reference (nothing can stop it; everything it touches is individually
        // thread-safe), but unregistering first means no NEW frame can dispatch
        // concurrently with the teardown below.
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        if (service != null) {
            LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
            service.shutdown();
        }

        LSSLogger.info(Brand.displayName() + " (Paper) disabled");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        var service = this.requestService;
        if (service == null) return;
        if (message == null || message.length == 0) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        dispatchPluginMessage(channel, player.getName(), message,
                data -> handleHandshake(player, nmsPlayer, data),
                data -> handleBatchChunkRequest(nmsPlayer, data),
                data -> CLIENT_DATA_VERSIONS.put(nmsPlayer.getUUID(),
                        PaperPayloadHandler.decodeClientInfo(data)),
                // Far players (E1): decode on the messenger thread (pure), apply on the
                // PUMP via the runtime-task queue — drained AFTER the lifecycle mailbox,
                // so a prefs frame racing its own Register lands post-registration and
                // the broadcast core's single-threaded contract holds on Folia too.
                data -> {
                    var prefs = dev.vox.lss.common.farplayers.FarPlayerWire.decodePrefs(data);
                    var uuid = nmsPlayer.getUUID();
                    service.enqueueRuntimeTask(
                            () -> service.getFarPlayerService().onPrefs(uuid, prefs));
                },
                // Region summaries (P2 §5): decode on the messenger thread (pure — a
                // Folia region thread is fine, no entity access), offer into the
                // latest-wins mailbox; the pump reads player state at admission. The
                // kill switch is HANDLER-checked (flips apply to connected clients).
                data -> service.handleRegionSummaryRequest(nmsPlayer.getUUID(), data));
    }

    /** Test seam: a per-channel message handler; hostile-frame decodes may throw. */
    @FunctionalInterface
    interface PluginMessageHandler {
        void handle(byte[] message) throws Exception;
    }

    /** Contained hostile-frame ERROR rate limit. Any authenticated client can spam malformed
     *  frames at packet rate on these channels, and an unthrottled stack trace per frame is a
     *  log-flood vector (Fabric self-limits — a bad codec decode kicks the client; Plugin
     *  Messaging has no equivalent). First frame logs immediately with the stack; later ones
     *  aggregate into a suppressed count released at most once per interval. Package-visible
     *  and swappable so the glue tests' one-ERROR-row containment pins stay deterministic. */
    static volatile LogThrottle hostileFrameLog = new LogThrottle(60_000);

    /**
     * Channel switch + exception containment for {@link #onPluginMessageReceived},
     * extracted so hostile-frame containment is testable without a CraftPlayer: one
     * malformed frame must be caught and logged — never propagate into Bukkit's
     * messenger — and later messages must still dispatch. Unknown channels are ignored.
     * Errors deliberately propagate (only Exception is contained).
     */
    static void dispatchPluginMessage(String channel, String playerName, byte[] message,
                                      PluginMessageHandler handshakeHandler,
                                      PluginMessageHandler chunkRequestHandler,
                                      PluginMessageHandler clientInfoHandler,
                                      PluginMessageHandler farPlayerPrefsHandler,
                                      PluginMessageHandler regionSummaryReqHandler) {
        try {
            switch (channel) {
                case LSSConstants.CHANNEL_HANDSHAKE -> handshakeHandler.handle(message);
                case LSSConstants.CHANNEL_CHUNK_REQUEST -> chunkRequestHandler.handle(message);
                case LSSConstants.CHANNEL_CLIENT_INFO -> clientInfoHandler.handle(message);
                case LSSConstants.CHANNEL_FAR_PLAYER_PREFS -> farPlayerPrefsHandler.handle(message);
                case LSSConstants.CHANNEL_REGION_SUMMARY_REQ -> regionSummaryReqHandler.handle(message);
            }
        } catch (Exception e) {
            long released = hostileFrameLog.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (released > 0) {
                String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                LSSLogger.error("Error handling plugin message on channel " + channel
                        + " from " + playerName + suffix, e);
            }
        }
    }

    /**
     * Test seam: the session-config reply send, production-wired to
     * {@link PaperPayloadHandler#sendSessionConfig} (CURRENT echoes {@code PROTOCOL_VERSION};
     * the V18 dialect echoes 18 on the same 4-field layout — the old client's gate
     * hard-requires its own version) or {@link PaperPayloadHandler#sendSessionConfigV16}
     * (V16 dialect — the legacy 6-field layout echoing protocol 16; the caps are the old
     * client's pacing) for the handshaking player.
     */
    @FunctionalInterface
    interface SessionConfigSender {
        void send(HandshakeGate.WireDialect dialect, boolean enabled, int lodDistanceChunks,
                  int syncCap, int genCap, boolean generationEnabled);
    }

    /**
     * Test seam: player registration, production-wired to
     * {@link PaperRequestProcessingService#enqueueRegister} — on Folia the handshake message
     * arrives on the player's region thread, so registration is mailboxed and the pump
     * applies it next tick. EVERY dialect identity mark (v16 and v18 alike) rides that
     * mailbox too, as the {@link #dialectFlipFor} runnable the pump runs during its
     * lifecycle drain — never directly from the region thread. (This javadoc used to
     * describe a pre-round-3 design where V16 marked its session identity directly; that
     * direct mark IS the hard-kick race the round-3 review fixed — a column egress
     * deciding its wire shape off a half-published identity — so do not reintroduce it.
     * 2026-08-05 review D1.) Only invoked when the {@link HandshakeGate} decision says to
     * register, so the production lambda may capture a service reference that is non-null
     * whenever servicePresent was true.
     */
    @FunctionalInterface
    interface HandshakeRegistrar {
        /** {@code replyAfterRegister} MUST run after the registration is applied (the pump's
         *  lifecycle drain in production) — never inline before it; see enqueueRegister. */
        void register(int capabilities, HandshakeGate.WireDialect dialect, Runnable replyAfterRegister);
    }

    private void handleHandshake(Player bukkitPlayer, ServerPlayer nmsPlayer, byte[] data) {
        var service = this.requestService;
        // XVER §7: capture Via's answer once per handshake (the && keeps a disabled
        // guard from ever triggering probe resolution); the pure seam applies the rule.
        int viaProtocol = this.lssConfig.enableViaMismatchGuard
                ? dev.vox.lss.common.compat.ViaProbe.playerProtocol(nmsPlayer.getUUID())
                : dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL;
        handleHandshake(data, nmsPlayer.getName().getString(), this.lssConfig, service != null,
                viaProtocol, net.minecraft.SharedConstants.getProtocolVersion(),
                (dialect, enabled, lodDistanceChunks, syncCap, genCap, generationEnabled) -> {
                    // A cross-dialect re-handshake sheds the stale compat identities it is
                    // NOT — otherwise columns keep shipping the old dialect's shape and
                    // hard-kick the re-armed decoder. Placed on the sender seam because it
                    // fires for every replying outcome (REGISTER and NO_CONSUMER/DISABLED
                    // alike); reply-only sheds run inline on the region thread, an
                    // inherited accepted residual (v18-compat design §2.3).
                    if (service != null) {
                        if (dialect != HandshakeGate.WireDialect.V16) {
                            service.getV16CompatManager().onNonV16Handshake(nmsPlayer.getUUID());
                        }
                        // Reply-only outcomes shed a stale CROSS-dialect membership; a
                        // REGISTER's mark happens on the pump via dialectFlipFor.
                        service.getDialectTracker().onNonRegisterHandshake(
                                nmsPlayer.getUUID(), dialect);
                    }
                    if (dialect == HandshakeGate.WireDialect.V16) {
                        PaperPayloadHandler.sendSessionConfigV16(bukkitPlayer, enabled,
                                lodDistanceChunks, syncCap, genCap, generationEnabled);
                    } else {
                        PaperPayloadHandler.sendSessionConfig(bukkitPlayer,
                                sessionConfigVersionFor(dialect),
                                enabled, lodDistanceChunks, generationEnabled);
                    }
                },
                (capabilities, dialect, replyAfterRegister) -> {
                    // The dialect flip runs on the PUMP, immediately before registerPlayer,
                    // via the mailbox's pre-register hook. Both halves of that placement are
                    // load-bearing:
                    //   * BEFORE registerPlayer, because that is where
                    //     wantsCompressedColumns is derived from isV16(). Doing it after —
                    //     which is what the sender seam amounts to for a REGISTER outcome,
                    //     since the drain runs replyAfterRegister last — left a v16 ->
                    //     current re-handshake running its whole session uncompressed.
                    //   * ON THE PUMP, because on Folia the handshake arrives on a REGION
                    //     thread. A flip applied there takes effect instantly while the
                    //     SessionConfig that re-arms the client's decoder waits for the next
                    //     drain, so the remainder of that tick's flush could ship
                    //     new-dialect columns to a decoder still armed for the old one — a
                    //     malformed frame, and a disconnect. (Round-3 review; the first
                    //     bullet's fix originally introduced the second bullet's race.)
                    // All directions go through the hook so none can drift off-pump: each
                    // dialect marks its own identity and sheds the other's (the v18
                    // membership especially must NEVER be marked on the region thread —
                    // v18-compat design §2.3, review F1).
                    service.enqueueRegister(nmsPlayer, capabilities,
                            dialectFlipFor(dialect, service.getV16CompatManager(),
                                    service.getDialectTracker(), nmsPlayer.getUUID()),
                            replyAfterRegister);
                });
    }

    /** The SessionConfig version echo per dialect (v18-compat design §2.4): the V18
     *  dialect echoes 18 on the CURRENT 4-field layout — the v0.8.x client's gate
     *  hard-requires its own version and self-disables on 19. Extracted static so the
     *  literal is pinnable (execution-review finding 1: the production lambda sat one
     *  seam above every test, and a silent V18->PROTOCOL_VERSION regression compiled
     *  clean). V16 never reaches this — it takes the 6-field legacy sender. */
    static int sessionConfigVersionFor(HandshakeGate.WireDialect dialect) {
        return switch (dialect) {
            case V18 -> LSSConstants.V18_COMPAT_PROTOCOL_VERSION;
            case V19 -> LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
            case V16, CURRENT -> LSSConstants.PROTOCOL_VERSION;
        };
    }

    /** The pump-deferred dialect flip: mark the session's dialect in the single-map
     *  tracker (any cross-dialect shed is the overwrite itself) and create/shed the v16
     *  manager's ingress-shim session. Extracted static so the BODY is pinnable against
     *  real manager/tracker instances (execution-review finding 1: dropping the V18
     *  case's mark — which mis-derives wantsCompressedColumns and leaks the codec byte
     *  to every v0.8.x client — passed the whole suite). */
    static Runnable dialectFlipFor(HandshakeGate.WireDialect dialect,
                                   dev.vox.lss.common.compat.V16CompatManager v16,
                                   dev.vox.lss.common.compat.WireDialectTracker dialects,
                                   java.util.UUID uuid) {
        return () -> {
            dialects.onHandshake(uuid, dialect);
            if (dialect == HandshakeGate.WireDialect.V16) {
                // Session identity first, so drip batches merge from the first frame.
                v16.onHandshake(uuid);
            } else {
                v16.onNonV16Handshake(uuid);
            }
        };
    }

    /**
     * Handshake decode → {@link HandshakeGate} → reply/registration glue, extracted
     * behind the sender/registrar seams so call-site obedience is testable. Contract:
     * a VERSION_MISMATCH decision sends NOTHING (any reply would kick the skewed
     * client — see {@link HandshakeGate.Outcome#VERSION_MISMATCH}); NO_CONSUMER
     * replies but never registers; the reply advertises the gate's effectiveEnabled
     * and wires each config field to its session-config slot.
     */
    static void handleHandshake(byte[] data, String playerName, PaperConfig config,
                                boolean servicePresent, SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        // No-Via-signal overload: every pre-C5 glue pin rides this unchanged. The
        // native slot gets 0 — a value no real MC protocol can be — never an LSS
        // protocol number (review m6: pairing NO_SIGNAL with PROTOCOL_VERSION was a
        // landmine one edit from denying everyone via 20 != <real via protocol>).
        handleHandshake(data, playerName, config, servicePresent,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0,
                configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, PaperConfig config,
                                boolean servicePresent, int viaProtocol, int nativeProtocol,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        var handshake = PaperPayloadHandler.decodeHandshake(data);
        if (handshake == null) return;

        LSSLogger.info(Brand.shortName() + " handshake received from " + playerName
                + " (protocol v" + handshake.protocolVersion()
                + ", capabilities=" + handshake.capabilities() + ")");

        var decision = HandshakeGate.evaluate(handshake.protocolVersion(),
                handshake.capabilities(), config.enabled, servicePresent,
                config.enableV16Compat, config.enableV18Compat, config.enableV19Compat,
                dev.vox.lss.common.compat.ViaProbe.isMismatch(viaProtocol, nativeProtocol));

        if (decision.outcome() == HandshakeGate.Outcome.VIA_MISMATCH) {
            // Silent deny — "Minecraft protocol" to keep the number space distinct
            // from the LSS protocol the handshake INFO above prints (review m5). Like
            // VERSION_MISMATCH below, an EXISTING registration deliberately survives
            // (review m1): the reachable window is a no-signal FIRST handshake (Via
            // mid-init) that registered legacy, then a later denial — bounded to that
            // race, healed by rejoin (the re-attach prompt loop this can enter is
            // 1/minute-bounded; see sendReattachPromptPayload).
            LSSLogger.info("LOD unavailable for " + playerName
                    + ": Via reports client Minecraft protocol " + viaProtocol
                    + " vs server " + nativeProtocol + " (cross-MC legacy session"
                    + " cannot be served) — the client must update "
                    + Brand.shortName());
            return;
        }
        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            // An EXISTING registration deliberately survives this rung (and NO_CONSUMER):
            // only a hostile/buggy client re-handshakes cross-capability on a live
            // connection, and a stray duplicate frame must not kill a working stream.
            // The NO_CONSUMER keeps-registration shape is pinned (ServiceLifecycleGameTests,
            // Fabric); the mismatch-survives shape follows from the same early return but
            // is argued, not pinned. Accepted residual: such a client keeps receiving
            // columns it just disclaimed, bounded to its own connection.
            LSSLogger.warn("Player " + playerName
                    + " has incompatible " + Brand.shortName() + " protocol version " + handshake.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        boolean v16 = decision.dialect() == HandshakeGate.WireDialect.V16;
        boolean v18 = decision.dialect() == HandshakeGate.WireDialect.V18;
        Runnable reply = () -> configSender.send(decision.dialect(),
                decision.effectiveEnabled(),
                config.lodDistanceChunks,
                // The caps ARE the old client's pacing — the server's real admission values
                // (ignored by the V18 sender branch; see the v16 compat design §4.1).
                LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                config.generationConcurrencyLimitPerPlayer,
                config.enableChunkGeneration);

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // Reply-only outcome: no state will exist, so the inline reply cannot race it.
            reply.run();
            // Visible to admins via this log.
            LSSLogger.info("Player " + playerName
                    + " has no LOD consumer (caps=" + handshake.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            // REGISTERING outcome: the reply is DEFERRED into the registration so the
            // client cannot declare before its state exists (the Folia pre-registration
            // drop, soak-diagnosed 2026-07-27 — on Folia this handler runs on the region
            // thread while the pump applies registrations next tick; a SessionConfig sent
            // from here invited a first want-set into the gap, dropped uncounted).
            registrar.register(handshake.capabilities(), decision.dialect(), reply);
            LSSLogger.info("Player " + playerName
                    + " registered for " + Brand.shortName() + " LOD request processing (caps="
                    + handshake.capabilities()
                    + (v16 ? ", v16-compat" : "") + (v18 ? ", v18-compat" : "") + ")");
        } else {
            // Reply-without-register (e.g. DISABLED advertisement): nothing to race.
            reply.run();
        }
    }

    private void handleBatchChunkRequest(ServerPlayer nmsPlayer, byte[] data) {
        var decoded = PaperPayloadHandler.decodeBatchChunkRequest(data);
        if (decoded == null) return;
        var service = this.requestService;
        if (service != null) {
            service.handleBatchRequest(nmsPlayer, decoded);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var service = this.requestService;
        if (service != null) {
            // Mailboxed: on Folia this event fires on the quitting player's region thread,
            // and removal mutates pump-owned state (generation service maps among others).
            service.enqueueRemove(event.getPlayer().getUniqueId());
            // Network-level and immediate (both structures are any-thread safe): the quit
            // drops the compat session identities; the mailboxed removePlayer above only
            // resets a want-set that no longer exists — a no-op. The v18 membership is
            // ALSO dropped by the mailbox Remove drain (the quit-race leak guard —
            // v18-compat design §2.3).
            service.getV16CompatManager().onDisconnect(event.getPlayer().getUniqueId());
            service.getDialectTracker().onDisconnect(event.getPlayer().getUniqueId());
        }
        // Service-independent: the sidecar fact is recorded at the network level
        // (possibly before any service exists) and must die with the connection.
        CLIENT_DATA_VERSIONS.remove(event.getPlayer().getUniqueId());
    }

    public PaperRequestProcessingService getRequestService() {
        return this.requestService;
    }

    public PaperConfig getLssConfig() {
        return this.lssConfig;
    }
}
