package dev.vox.lss.networking.server;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;

public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    /**
     * Test seam (D9): atomically swaps the live service reference and returns the previous
     * one, so a gametest can point the static call sites that hard-read
     * {@link #getRequestService()} (the /lsslod commands, the soak metrics exporter) at a
     * service with known state for one synchronous assertion window, then restore it.
     * Refused outside gametest/soak JVMs; production code never calls this.
     */
    public static RequestProcessingService swapServiceForTesting(RequestProcessingService replacement) {
        if (!Boolean.getBoolean("lss.test.integratedServer") && !isSoakJvm()) {
            throw new IllegalStateException(
                    "swapServiceForTesting is only available in gametest/soak JVMs");
        }
        var previous = requestService;
        requestService = replacement;
        return previous;
    }

    private static boolean isSoakJvm() {
        // Blank counts as unset: the soakServer run config always defines the property,
        // as the empty string when no scenario is staged (BenchmarkBridge convention).
        String scenario = System.getProperty("lss.soak.scenario");
        return scenario != null && !scenario.isBlank();
    }

    public static void startServiceForLan(MinecraftServer server) {
        // The LAN publish hook fires on the RENDER thread (the share/options screen calls
        // publishServer directly), and construction is heavy: it starts the processing,
        // save, and disk-reader threads and does a blocking ColumnTimestampCache.load().
        // Hop to the server thread — the same context the dedicated-server start uses.
        // Accepted ≤1-tick window between the LAN listener being up and the service being
        // non-null: a joining client cannot complete login inside it, and the host's own
        // handshake already hops through the client executor. From the server thread
        // itself (the /publish command path) execute() runs inline, so nothing changes
        // there.
        server.execute(() -> startServiceForLanOnServerThread(server));
    }

    private static synchronized void startServiceForLanOnServerThread(MinecraftServer server) {
        // A hop task queued in the last tick before Save-and-Quit runs AFTER the
        // SERVER_STOPPING handler nulled requestService (stopServer drains pending tasks
        // after the Fabric event fires) — starting here would leave a zombie service bound
        // to a dead server for the rest of the client JVM.
        if (!server.isRunning()) return;
        if (requestService != null) return;
        LSSLogger.info(Brand.shortName() + " LOD request processing service starting (LAN server)");
        requestService = new RequestProcessingService(server);
        LSSClientNetworking.triggerHostHandshake();
    }

    // Dimension strings for the save hook, cached per ResourceKey: Identifier.toString
    // allocates, and the hook runs on every committed chunk save. Keyed by the
    // lightweight interned ResourceKey (never the ServerLevel — that would pin departed
    // worlds); bounded by the distinct dimensions a JVM ever loads.
    private static final ConcurrentHashMap<ResourceKey<Level>, String> DIMENSION_STRINGS =
            new ConcurrentHashMap<>();

    // lss:client_info sidecar facts (XVER §2.2): the client's MC data version, keyed by
    // UUID, swept at disconnect. Absence = legacy client (no sidecar channel). Consumed
    // as diagnostics + the C5 Via-guard input.
    private static final ConcurrentHashMap<java.util.UUID, Integer> CLIENT_DATA_VERSIONS =
            new ConcurrentHashMap<>();

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return CLIENT_DATA_VERSIONS.get(uuid);
    }

    /**
     * The dirty-detection hook body ({@code ChunkSaveDataHook}'s copyOf injection — the
     * choke point vanilla's {@code ChunkMap.save} and Moonrise's replacement save
     * pipeline both call; issue #69). Runs on whatever thread legally snapshots the live
     * chunk for saving — the same access domain {@code copyOf} itself needs, so reading
     * section content here is safe wherever the call is legal.
     *
     * <p>Only FULL chunks: a ProtoChunk save during generation has no LOD-servable
     * content yet (re-requesting it reads "not found" and escalates to generation), and
     * the completed chunk reaches clients through the generation serve path. The
     * {@code enabled=false} gate also lives here: the service tick (and so the
     * dirty-broadcast drain) is disabled, so marking would grow the tracker without
     * bound — and the content hash serializes the column on every save for nothing.
     * Vanilla re-saves loaded chunks for metadata alone (inhabitedTime), so a save is
     * not evidence of change — only hash-confirmed content edits mark dirty.
     */
    public static void onChunkSaveData(ServerLevel level, ChunkAccess chunk) {
        if (!(chunk instanceof LevelChunk levelChunk)) return;
        var service = requestService;
        if (service == null || !LSSServerConfig.CONFIG.enabled) return;
        // Skip gate (2026-08-05 review P3 + the three-lens follow-up): see skipDirtyHash.
        if (skipDirtyHash(service.hasEverRegisteredPlayer(), service.getLodStore() != null,
                service.timestampCacheBootedEmpty())) return;
        String dimension = DIMENSION_STRINGS.computeIfAbsent(level.dimension(),
                key -> key.identifier().toString());
        var obs = service.getDirtyContentFilter().observeSave(level, levelChunk, dimension);
        if (obs.changed()) {
            service.getDirtyTracker().markDirty(dimension, chunk.getPos().x(), chunk.getPos().z());
            // Save-hook store bridge, DELETE-only (4-agent round R2-M2): the write-
            // through deposit this branch used to make could never survive — the same
            // mark it sets is drained by the broadcaster into the unconditional
            // dirty->store fan-out, whose tombstone strictly postdates the deposit's
            // enqueue, so every hook deposit died within one broadcast interval while
            // costing a compress+insert+delete and real shed pressure on the bounded
            // queue. The PROMPT delete is what carries the value: it closes the
            // up-to-10 s window in which the PRE-edit store row would keep serving hits
            // before the fan-out drain lands. Fresh bytes re-enter the store through
            // the dirty-broadcast re-serve's delivery-path deposit (which is what
            // actually re-warmed edited columns all along). Runs OUTSIDE the filter
            // monitor; tombstone put + control-queue add, safe off-main.
            applySaveObservationToStore(service.getLodStore(), dimension,
                    chunk.getPos().x(), chunk.getPos().z(), obs);
        }
    }

    /**
     * The review-P3 skip-gate predicate, pure so the truth table is pinnable (three-lens
     * review, test-adequacy MAJOR). Skip the dirty-content serialize+hash only while ALL
     * three hold:
     * <ul>
     *   <li>no LSS client has EVER registered this session (one-way latch — session
     *       state like held columns outlives its player, so the hash must resume forever
     *       after the first join);</li>
     *   <li>the store is inert (with a store, a skipped online edit would leave a
     *       pre-edit store row serving hits all session — Fabric sweeps at boot only);</li>
     *   <li>the persisted timestamp cache BOOTED EMPTY (correctness MAJOR:
     *       {@code <world>/data/lss-timestamps.bin} survives restarts, so a server that
     *       served clients last session boots with stamps a pre-first-join edit must
     *       invalidate — else a warm rejoin draws up_to_date for pre-edit terrain).</li>
     * </ul>
     * Under the full conjunction nothing the hash maintains is observable: no cache
     * stamps on any boot, no client-held columns, no store rows, and dirty marks with no
     * audience. A server running LSS with no LSS-playing users otherwise paid a
     * serialize+hash per chunk save forever (~30-60 µs each; 10-40 ms per save-all).
     * Accepted cost once a client DOES join: skip-era positions have no stored hash, so
     * their first post-join save reads absent-hash → changed → one spurious dirty
     * mark+broadcast each (bounded by loaded chunks, drained per interval).
     */
    static boolean skipDirtyHash(boolean everRegistered, boolean storePresent,
                                 boolean timestampCacheBootedEmpty) {
        return !everRegistered && !storePresent && timestampCacheBootedEmpty;
    }

    /** The save-hook -> store bridge, extracted for direct testing: a content-changing
     *  save DELETES the position's store row (see onChunkSaveData — the old write-
     *  through deposit was provably always dead on arrival; delete-only keeps the
     *  stale-row-closure without the doomed work). Covers the serializer fail-open
     *  case by construction: changed-but-undepositable also just deletes. */
    static void applySaveObservationToStore(dev.vox.lss.common.store.LodStoreService store,
                                            String dimension, int cx, int cz,
                                            DirtyContentFilter.SaveObservation obs) {
        if (store == null || !obs.changed()) return;
        store.delete(dimension, dev.vox.lss.common.PositionUtil.packPosition(cx, cz));
    }

    /** Reply hook for {@link #handleHandshake}; production wires {@code ServerPlayNetworking.send}. */
    @FunctionalInterface
    public interface SessionConfigResponder {
        void send(SessionConfigS2CPayload reply);
    }

    /**
     * The handshake receiver body, extracted so gametests can drive crafted frames through
     * the real call-site policy — gate evaluation, reply field wiring, registration — against
     * an explicit service and a recording responder (a caps=0 frame must reply without
     * registering, a foreign-version frame must produce zero reply frames). Production
     * behavior is unchanged: the registered receiver calls this with the live service and a
     * real network sender.
     */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       SessionConfigResponder responder) {
        // XVER §7: consult Via for the client's REAL protocol (a legacy LSS handshake
        // carries no MC version). Captured once so the log line and the gate see the
        // same number; the ternary keeps a disabled guard from ever triggering
        // resolution. Deliberately consulted for v20 handshakes too (the gate discards
        // it there) — the answer is future diagnostics, and the probe is one cached
        // MethodHandle invoke per join (review m12, kept with rationale).
        var config = LSSServerConfig.CONFIG;
        int viaProtocol = config.enableViaMismatchGuard
                ? dev.vox.lss.common.compat.ViaProbe.playerProtocol(player.getUUID())
                : dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL;
        handleHandshake(payload, player, service, responder,
                viaProtocol, SharedConstants.getProtocolVersion());
    }

    /** The Via-signal seam (review MAJOR-2, mirroring the Paper overload pair): the
     *  probe read happens in the caller above, so a gametest can force a mismatch
     *  through the PRODUCTION ladder — without this seam no test JVM can produce a
     *  VIA_MISMATCH on Fabric at all (no real Via in any tier). */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       SessionConfigResponder responder,
                                       int viaProtocol, int nativeProtocol) {
        LSSLogger.info(Brand.shortName() + " handshake received from " + player.getName().getString()
                + " (protocol v" + payload.protocolVersion()
                + ", capabilities=" + payload.capabilities() + ")");

        var config = LSSServerConfig.CONFIG;
        var decision = HandshakeGate.evaluate(payload.protocolVersion(),
                payload.capabilities(), config.enabled, service != null,
                config.enableV16Compat, config.enableV18Compat, config.enableV19Compat,
                dev.vox.lss.common.compat.ViaProbe.isMismatch(viaProtocol, nativeProtocol));

        if (decision.outcome() == HandshakeGate.Outcome.VIA_MISMATCH) {
            // Silent deny; "Minecraft protocol" because the handshake INFO one line up
            // prints an LSS protocol number and the two spaces must not be conflated
            // (review m5). Like VERSION_MISMATCH's early return below, an EXISTING
            // registration deliberately survives (review m1): the reachable window is
            // a no-signal FIRST handshake (Via mid-init) that registered legacy, then
            // a later re-handshake denying — bounded to that race, healed by rejoin;
            // shedding here would add remove-path surface for a corner Via itself
            // closes seconds later.
            LSSLogger.info("LOD unavailable for " + player.getName().getString()
                    + ": Via reports client Minecraft protocol " + viaProtocol
                    + " vs server " + nativeProtocol + " (cross-MC legacy session"
                    + " cannot be served) — the client must update "
                    + Brand.shortName());
            return;
        }
        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            // An EXISTING registration deliberately survives this rung (and NO_CONSUMER
            // below): only a hostile/buggy client re-handshakes cross-capability on a
            // live connection, and a stray duplicate frame must not kill a working
            // stream. The NO_CONSUMER keeps-registration shape is pinned by
            // ServiceLifecycleGameTests; the mismatch-survives shape follows from the
            // same early return but its gametest starts unregistered, so it is argued,
            // not pinned. Accepted residual: such a client keeps receiving columns it
            // just disclaimed, bounded to its own consenting connection.
            LSSLogger.warn("Player " + player.getName().getString()
                    + " has incompatible " + Brand.shortName() + " protocol version " + payload.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        boolean v16 = decision.dialect() == HandshakeGate.WireDialect.V16;
        boolean v18 = decision.dialect() == HandshakeGate.WireDialect.V18;
        boolean v19 = decision.dialect() == HandshakeGate.WireDialect.V19;
        if (service != null) {
            if (!v16) {
                // A cross-dialect re-handshake must shed the stale v16 ingress-shim
                // session (the dialect TRACKER shed is automatic on REGISTER — the
                // onHandshake overwrite — but the manager's synthetic want-set session
                // is separate state).
                service.getV16CompatManager().onNonV16Handshake(player.getUUID());
            }
            if (!decision.registerPlayer()) {
                // Non-register outcomes still shed a stale CROSS-dialect membership.
                service.getDialectTracker().onNonRegisterHandshake(
                        player.getUUID(), decision.dialect());
            }
        }
        responder.send(v16
                ? SessionConfigS2CPayload.v16Legacy(
                        decision.effectiveEnabled(),
                        config.lodDistanceChunks,
                        // The caps ARE the old client's pacing — advertise the server's real
                        // admission values (see the v16 compat design §4.1).
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                        config.generationConcurrencyLimitPerPlayer,
                        config.enableChunkGeneration)
                : new SessionConfigS2CPayload(
                        // v18/v19 compat: the CURRENT 4-field layout, echoing the legacy
                        // client's own version — its gate hard-requires it (v18-compat
                        // §2.4; the v19 rung is the same echo trick).
                        v18 ? LSSConstants.V18_COMPAT_PROTOCOL_VERSION
                            : v19 ? LSSConstants.V19_COMPAT_PROTOCOL_VERSION
                                  : LSSConstants.PROTOCOL_VERSION,
                        decision.effectiveEnabled(),
                        config.lodDistanceChunks,
                        config.enableChunkGeneration,
                        // v20-only append (the encoder omits it for the echo versions).
                        net.minecraft.SharedConstants.getCurrentVersion()
                                .dataVersion().version()));

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // A re-handshake that no longer carries a consumer sheds any prior
            // far-player subscription too (review: same-session downgrade). Null
            // guard (pre-G review): HandshakeGate returns NO_CONSUMER on caps 0
            // regardless of servicePresent, so a v16-era caps-0 client hitting the
            // LAN-construction gap or the SERVER_STOPPING race lands here with no
            // service — the sibling branches carry the same guard.
            if (service != null) {
                service.getFarPlayerService().removeViewer(player.getUUID());
            }
            // Visible to admins via this log.
            LSSLogger.info("Player " + player.getName().getString()
                    + " has no LOD consumer (caps=" + payload.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            if (v16) {
                // Session identity first, so drip batches merge from the first frame.
                service.getV16CompatManager().onHandshake(player.getUUID());
            }
            // Dialect mark first: registerPlayer derives wantsCompressedColumns from it
            // (v18-compat §2.4 — the main-thread mark-before-register contract; one map,
            // so this is also the cross-dialect shed for the tracker).
            service.getDialectTracker().onHandshake(player.getUUID(), decision.dialect());
            service.registerPlayer(player, payload.capabilities());
            // Far players (E1): subscription identity lands at handshake, next to the
            // dialect mark — a CURRENT-dialect session only (legacy layouts predate the
            // bit, so a legacy handshake setting it is noise, not a subscription).
            if ((payload.capabilities() & LSSConstants.CAPABILITY_FAR_PLAYERS) != 0
                    && decision.dialect() == HandshakeGate.WireDialect.CURRENT) {
                service.getFarPlayerService().subscribeViewer(player.getUUID());
            } else {
                // Bit absent or a legacy dialect on a RE-handshake: shed any stale
                // subscription rather than keep streaming frames the decoder no
                // longer expects (review; twin of the Paper Register-drain else).
                service.getFarPlayerService().removeViewer(player.getUUID());
            }
            LSSLogger.info("Player " + player.getName().getString()
                    + " registered for " + Brand.shortName() + " LOD request processing (caps="
                    + payload.capabilities()
                    + (v16 ? ", v16-compat" : "") + (v18 ? ", v18-compat" : "")
                    + (v19 ? ", v19-compat" : "") + ")");
        }
    }

    private static volatile long lastPrefsWarnMillis;

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(
                HandshakeC2SPayload.TYPE,
                (payload, context) -> handleHandshake(payload, context.player(), requestService,
                        reply -> ServerPlayNetworking.send(context.player(), reply))
        );

        ServerPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.ClientInfoC2SPayload.TYPE,
                (payload, context) -> CLIENT_DATA_VERSIONS.put(
                        context.player().getUUID(), payload.dataVersion())
        );

        // Far players (E1): prefs ingress. Fabric receivers run on the server thread —
        // the broadcast core's single-threaded contract holds without marshaling. A
        // malformed frame is contained to a warn (the sidecar-guard doctrine): prefs
        // are additive, and a bad frame must never cost the session.
        ServerPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service == null) return;
                    try {
                        var prefs = dev.vox.lss.common.farplayers.FarPlayerWire
                                .decodePrefs(payload.body());
                        service.getFarPlayerService().onPrefs(context.player().getUUID(), prefs);
                    } catch (Exception e) {
                        // Throttled (review m5b): a hostile sender must not turn a
                        // contained decode failure into log spam.
                        long now = System.currentTimeMillis();
                        if (now - lastPrefsWarnMillis > 60_000) {
                            lastPrefsWarnMillis = now;
                            LSSLogger.warn("Malformed far-player prefs from "
                                    + context.player().getName().getString() + " — ignored ("
                                    + e + ")");
                        }
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                BatchChunkRequestC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleBatchRequest(context.player(), payload);
                    }
                }
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer() && !Boolean.getBoolean("lss.test.integratedServer")) {
                LSSLogger.info(Brand.shortName() + " LOD request processing deferred until LAN");
                return;
            }
            LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");
            requestService = new RequestProcessingService(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var service = requestService;
            if (service != null) {
                LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
                service.shutdown();
                requestService = null;
            }
            // Sidecar facts die with the server (integrated-server world cycles would
            // otherwise accrete entries across sessions — review C1-9).
            CLIENT_DATA_VERSIONS.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var service = requestService;
            if (service != null) {
                service.tick();
            }
        });

        LSSServerCommands.init();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var service = requestService;
            if (service != null) {
                service.removePlayer(handler.getPlayer().getUUID());
                // Network-level: the disconnect drops the compat session identities
                // (removePlayer above only reset the v16 want-set and touches neither
                // membership — dim changes reuse that path and must keep both).
                service.getV16CompatManager().onDisconnect(handler.getPlayer().getUUID());
                service.getDialectTracker().onDisconnect(handler.getPlayer().getUUID());
                // Far players: the subscription dies with the CONNECTION, never with the
                // dimension-change remove+register cycle (the v18-rung checklist).
                service.getFarPlayerService().removeViewer(handler.getPlayer().getUUID());
            }
            // Service-independent: the sidecar fact is recorded at the network level
            // (possibly before any service exists) and must die with the connection.
            CLIENT_DATA_VERSIONS.remove(handler.getPlayer().getUUID());
        });
    }
}
