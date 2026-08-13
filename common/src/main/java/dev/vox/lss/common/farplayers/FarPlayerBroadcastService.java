package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.LSSConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The far-player broadcast core (v0.11.0 stage E1 — far-player-proxies-plan.md §3.2 as
 * amended by R-3/R-10). Platform-neutral: the owning service calls {@link #tick} from
 * its existing tick (Fabric server thread / Paper GlobalRegionScheduler pump — Folia-
 * safe by construction) with the per-tick ONLINE snapshots built ONCE (the inversion of
 * SeeU's per-viewer×per-target snapshot construction), and every side effect rides the
 * injected {@link FrameSender} lane.
 *
 * <p>THREADING CONTRACT: single-threaded — every method runs on the owning service's
 * processing thread. Paper marshals network-thread arrivals (subscribe, prefs, quits)
 * through its lifecycle mailbox first, exactly like the dialect-flip precedent.
 *
 * <p>Lifecycle (the v18-rung checklist): subscription identity is keyed at THIS level,
 * not on the per-player request state — it survives the dimension-change
 * remove+register cycle (the platform calls {@link #onViewerDimensionChange}, never
 * remove) and dies only at {@link #removeViewer} (disconnect + Paper's quit-originated
 * mailbox Remove).
 *
 * <p>Roster armor: every updates frame is stamped with the viewer's roster epoch;
 * clients drop unknown-epoch updates. A FULL roster (epoch bump, rows cleared) goes out
 * on subscribe, on ANY prefs receipt — changed or byte-identical (R-3: the prefs
 * re-send IS the reset/re-subscribe mechanism) — and on the viewer's dimension change,
 * rate-bounded to one per {@link #FULL_ROSTER_MIN_INTERVAL_MILLIS} per viewer (prefs
 * are client-triggerable; a chat command must not be a roster-flood lever — a pending
 * full roster HOLDS until the bound clears, never drops).
 *
 * <p>Delta suppression is defined over the WHOLE snapshot (position + angles + pose +
 * vehicle UUID/type + equipment hash — R-10: a quantized-position-unchanged dismount
 * must still force the player into the frame), never position alone.
 */
public final class FarPlayerBroadcastService {

    /** R-3's rate bound on client-triggerable full rosters. */
    public static final long FULL_ROSTER_MIN_INTERVAL_MILLIS = 5_000;

    /** Distance-tiered cadence: beyond this fraction of the effective max ring the
     *  target updates at half rate; beyond twice this, quarter rate. */
    public static final int TIER_HALF_RATE_BLOCKS = 2048;

    /** One online player's per-tick snapshot, built once by the platform adapter.
     *  {@code vehicle} is pre-quantized ({@link FarPlayerWire.Vehicle}); equipment
     *  identity strings are version-neutral registry identities (R-7). */
    public record PlayerSnapshot(UUID uuid, String name, String dimension,
                                 double x, double y, double z,
                                 float yaw, float headYaw, float pitch,
                                 byte poseFlags,
                                 double velXPerSecond, double velYPerSecond, double velZPerSecond,
                                 boolean spectator, boolean invisible, boolean alive,
                                 long equipmentHash,
                                 String[] equipmentIdentities, int[] equipmentCounts,
                                 FarPlayerWire.Vehicle vehicle) {}

    /** The dedicated send lane (FARP §3.2 — NOT the column send queue): the platform
     *  consults its channel-writability/yield gate and charges the bandwidth governor;
     *  false = withheld this tick (the state is unchanged, next tick retries). */
    @FunctionalInterface
    public interface FrameSender {
        boolean send(UUID viewer, String channel, byte[] body);
    }

    /** Reflective vanish-mod bridge seam (melius-vanish on Fabric, vanish meta on
     *  Paper); default sees everyone. */
    @FunctionalInterface
    public interface VanishBridge {
        boolean canSee(UUID viewer, UUID target);
    }

    /** Per-viewer configuration snapshot the platform passes each tick (config is
     *  runtime-mutable via /lsslod set — R-9 — so it is re-read per tick, the
     *  tick-poll pattern). */
    public record Settings(String mode, int maxDistanceBlocks, int minDistanceBlocks,
                           boolean sendSpectators, List<String> exclude,
                           int updateIntervalTicks) {}

    private static final class TargetRow {
        int quantX, quantY, quantZ;
        byte yaw, headYaw, pitch, pose;
        long equipmentHash;
        UUID vehicleUuid;
        String vehicleType;
        boolean equipmentEverSent;
        int tierPhase;
    }

    private static final class ViewerState {
        FarPlayerWire.Prefs prefs;
        int epoch;
        final Map<UUID, Integer> indexByUuid = new HashMap<>();
        int nextIndex;
        boolean fullRosterPending = true; // subscribe = the first full roster
        long lastFullRosterMillis;
        final Map<UUID, TargetRow> rows = new HashMap<>();
    }

    private final Map<UUID, ViewerState> viewers = new HashMap<>();
    private final VanishBridge vanishBridge;

    // Diag counters (FARP §3.2: far-player bytes get their OWN counters — never folded
    // into service.bytes_sent/wire_bytes, which feed the cross-identity audits).
    private final AtomicLong rosterFramesSent = new AtomicLong();
    private final AtomicLong updateFramesSent = new AtomicLong();
    private final AtomicLong entriesSent = new AtomicLong();
    private final AtomicLong suppressedUnchanged = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();

    public FarPlayerBroadcastService(VanishBridge vanishBridge) {
        this.vanishBridge = vanishBridge == null ? (v, t) -> true : vanishBridge;
    }

    // ---- lifecycle (single-threaded, see the class contract) ----

    /** The viewer's session declared CAPABILITY_FAR_PLAYERS. Idempotent. */
    public void subscribeViewer(UUID viewer) {
        viewers.computeIfAbsent(viewer, u -> new ViewerState());
    }

    /** Disconnect + Paper's quit-originated mailbox Remove (the quit-race guard). */
    public void removeViewer(UUID viewer) {
        viewers.remove(viewer);
    }

    /** ANY prefs receipt — changed or byte-identical — queues a full roster (R-3). */
    public void onPrefs(UUID viewer, FarPlayerWire.Prefs prefs) {
        var state = viewers.get(viewer);
        if (state == null) return; // never subscribed (capability bit absent) — ignore
        state.prefs = prefs;
        state.fullRosterPending = true;
    }

    /** The viewer changed dimension: identity survives (v18-rung lifecycle), the roster
     *  does not — epoch bumps on the next send and every row re-sends. */
    public void onViewerDimensionChange(UUID viewer) {
        var state = viewers.get(viewer);
        if (state == null) return;
        state.fullRosterPending = true;
    }

    public boolean isSubscribed(UUID viewer) {
        return viewers.containsKey(viewer);
    }

    public int subscriberCount() {
        return viewers.size();
    }

    public long rosterFramesSent() { return rosterFramesSent.get(); }
    public long updateFramesSent() { return updateFramesSent.get(); }
    public long entriesSent() { return entriesSent.get(); }
    public long suppressedUnchanged() { return suppressedUnchanged.get(); }
    public long bytesSent() { return bytesSent.get(); }

    /** The /lsslod diag line (rendered only while subscribers exist or counters moved —
     *  the conditional-slot convention). */
    public String diagLine() {
        return "FarPlayers: subs=" + viewers.size()
                + ", rosters=" + rosterFramesSent.get()
                + ", updates=" + updateFramesSent.get()
                + ", entries=" + entriesSent.get()
                + ", suppressed=" + suppressedUnchanged.get()
                + ", bytes=" + bytesSent.get();
    }

    // ---- the broadcast tick ----

    /**
     * One broadcast pass. The platform calls this every {@code updateIntervalTicks}
     * server ticks while {@code settings.mode() != "off"} (an "off" mode simply stops
     * the calls — subscriptions and prefs survive a runtime mode flip).
     *
     * @param nowMillis   wall clock (injected for the rate-bound tests)
     * @param online      every online player's snapshot, built once (O(P))
     * @param settings    the tick's config snapshot
     * @param sender      the dedicated send lane
     */
    public void tick(long nowMillis, List<PlayerSnapshot> online, Settings settings,
                     FrameSender sender) {
        if (viewers.isEmpty() || "off".equals(settings.mode())) return;
        Map<UUID, PlayerSnapshot> byUuid = new HashMap<>(online.size());
        for (var s : online) {
            byUuid.put(s.uuid(), s);
        }
        for (var entry : viewers.entrySet()) {
            var viewerUuid = entry.getKey();
            var state = entry.getValue();
            var viewerSnap = byUuid.get(viewerUuid);
            if (viewerSnap == null) continue; // not in this tick's world (join/quit edge)
            if (state.prefs == null || !state.prefs.enabled()) continue;
            tickViewer(nowMillis, viewerUuid, state, viewerSnap, online, settings, sender);
        }
    }

    private void tickViewer(long nowMillis, UUID viewerUuid, ViewerState state,
                            PlayerSnapshot viewer, List<PlayerSnapshot> online,
                            Settings settings, FrameSender sender) {
        // Visible set under the filter ladder (SeeU's proven order + LSS privacy).
        var visible = new ArrayList<PlayerSnapshot>();
        for (var target : online) {
            if (isVisible(viewerUuid, state, viewer, target, settings)) {
                visible.add(target);
            }
        }

        // Roster maintenance. A pending full roster (subscribe/prefs/dim change) sends
        // at the rate bound; membership diffs are incremental frames otherwise.
        boolean fullSent = false;
        if (state.fullRosterPending
                && nowMillis - state.lastFullRosterMillis >= FULL_ROSTER_MIN_INTERVAL_MILLIS) {
            state.epoch++;
            state.indexByUuid.clear();
            state.nextIndex = 0;
            state.rows.clear();
            var added = new ArrayList<FarPlayerWire.RosterEntry>(visible.size());
            for (var t : visible) {
                int idx = state.nextIndex++;
                state.indexByUuid.put(t.uuid(), idx);
                added.add(new FarPlayerWire.RosterEntry(idx, t.uuid(), t.name()));
            }
            byte[] frame = FarPlayerWire.encodeRoster(
                    new FarPlayerWire.Roster(state.epoch, true, added, new int[0]));
            if (!sender.send(viewerUuid, LSSConstants.CHANNEL_FAR_PLAYER_ROSTER, frame)) {
                // Withheld (yield gate) — the pending flag survives, next tick retries.
                state.epoch--; // the epoch the client never saw must not burn
                state.indexByUuid.clear();
                state.nextIndex = 0;
                return;
            }
            state.fullRosterPending = false;
            state.lastFullRosterMillis = nowMillis;
            rosterFramesSent.incrementAndGet();
            bytesSent.addAndGet(frame.length);
            fullSent = true;
        } else {
            // Incremental membership diff.
            var added = new ArrayList<FarPlayerWire.RosterEntry>();
            for (var t : visible) {
                if (!state.indexByUuid.containsKey(t.uuid())) {
                    int idx = state.nextIndex++;
                    state.indexByUuid.put(t.uuid(), idx);
                    added.add(new FarPlayerWire.RosterEntry(idx, t.uuid(), t.name()));
                }
            }
            var removed = new ArrayList<Integer>();
            var visibleUuids = new java.util.HashSet<UUID>(visible.size());
            for (var t : visible) visibleUuids.add(t.uuid());
            var it = state.indexByUuid.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (!visibleUuids.contains(e.getKey())) {
                    removed.add(e.getValue());
                    state.rows.remove(e.getKey());
                    it.remove();
                }
            }
            if (!added.isEmpty() || !removed.isEmpty()) {
                int[] removedIdx = removed.stream().mapToInt(Integer::intValue).toArray();
                byte[] frame = FarPlayerWire.encodeRoster(
                        new FarPlayerWire.Roster(state.epoch, false, added, removedIdx));
                if (!sender.send(viewerUuid, LSSConstants.CHANNEL_FAR_PLAYER_ROSTER, frame)) {
                    // Withheld: roll the membership back so the next tick re-diffs.
                    for (var a : added) state.indexByUuid.remove(a.uuid());
                    state.nextIndex -= added.size();
                    return;
                }
                rosterFramesSent.incrementAndGet();
                bytesSent.addAndGet(frame.length);
            }
        }

        // Updates with tiering + whole-snapshot delta suppression.
        var entries = new ArrayList<FarPlayerWire.UpdateEntry>();
        for (var t : visible) {
            var row = state.rows.get(t.uuid());
            boolean firstSend = row == null;
            if (firstSend) {
                row = new TargetRow();
                state.rows.put(t.uuid(), row);
            }
            int qx = FarPlayerWire.quantizePos(t.x());
            int qy = FarPlayerWire.quantizePos(t.y());
            int qz = FarPlayerWire.quantizePos(t.z());
            byte yaw = FarPlayerWire.angleToByte(t.yaw());
            byte headYaw = FarPlayerWire.angleToByte(t.headYaw());
            byte pitch = FarPlayerWire.angleToByte(t.pitch());
            UUID vehicleUuid = t.vehicle() == null ? null : t.vehicle().uuid();
            String vehicleType = t.vehicle() == null ? null : t.vehicle().typeIdentity();

            boolean changed = firstSend
                    || row.quantX != qx || row.quantY != qy || row.quantZ != qz
                    || row.yaw != yaw || row.headYaw != headYaw || row.pitch != pitch
                    || row.pose != t.poseFlags()
                    || row.equipmentHash != t.equipmentHash()
                    || !java.util.Objects.equals(row.vehicleUuid, vehicleUuid)
                    || !java.util.Objects.equals(row.vehicleType, vehicleType);
            if (!changed) {
                suppressedUnchanged.incrementAndGet();
                continue;
            }
            // Distance tier: far targets update at half/quarter cadence — but a
            // MOUNT/DISMOUNT or equipment change always forces through (R-10).
            boolean forced = firstSend
                    || !java.util.Objects.equals(row.vehicleUuid, vehicleUuid)
                    || !java.util.Objects.equals(row.vehicleType, vehicleType)
                    || row.equipmentHash != t.equipmentHash();
            int divisor = tierDivisor(viewer, t);
            row.tierPhase++;
            if (!forced && divisor > 1 && (row.tierPhase % divisor) != 0) {
                continue; // held to the tier cadence; state row NOT updated — the move
                          // accumulates and ships on the tier boundary
            }

            boolean equipmentDue = t.equipmentIdentities() != null
                    && (!row.equipmentEverSent || row.equipmentHash != t.equipmentHash());
            entries.add(new FarPlayerWire.UpdateEntry(
                    state.indexByUuid.get(t.uuid()),
                    qx, qy, qz, yaw, headYaw, pitch, t.poseFlags(),
                    FarPlayerWire.velocityToShort(t.velXPerSecond()),
                    FarPlayerWire.velocityToShort(t.velYPerSecond()),
                    FarPlayerWire.velocityToShort(t.velZPerSecond()),
                    equipmentDue ? new int[FarPlayerWire.EQUIPMENT_SLOTS] : null,
                    equipmentDue ? t.equipmentCounts() : null,
                    equipmentDue ? t.equipmentIdentities() : null,
                    t.vehicle()));
            row.quantX = qx;
            row.quantY = qy;
            row.quantZ = qz;
            row.yaw = yaw;
            row.headYaw = headYaw;
            row.pitch = pitch;
            row.pose = t.poseFlags();
            row.vehicleUuid = vehicleUuid;
            row.vehicleType = vehicleType;
            if (equipmentDue) {
                row.equipmentEverSent = true;
                row.equipmentHash = t.equipmentHash();
            } else if (t.equipmentIdentities() == null) {
                row.equipmentHash = t.equipmentHash();
            }
        }
        if (entries.isEmpty() && !fullSent) return;
        byte[] frame = FarPlayerWire.encodeUpdates(new FarPlayerWire.Updates(
                state.epoch, viewer.dimension(), settings.updateIntervalTicks(), entries));
        if (sender.send(viewerUuid, LSSConstants.CHANNEL_FAR_PLAYER_UPDATES, frame)) {
            updateFramesSent.incrementAndGet();
            entriesSent.addAndGet(entries.size());
            bytesSent.addAndGet(frame.length);
        }
        // A withheld updates frame is simply dropped — rows already advanced, but the
        // next real change re-sends; pose data at 2 Hz has no loss-recovery need.
    }

    /** The filter ladder, in SeeU's proven order plus the LSS privacy additions. */
    private boolean isVisible(UUID viewerUuid, ViewerState state, PlayerSnapshot viewer,
                              PlayerSnapshot target, Settings settings) {
        if (target.uuid().equals(viewerUuid)) return false;
        if (!target.dimension().equals(viewer.dimension())) return false;
        if (!target.alive()) return false;
        if (target.spectator() && !settings.sendSpectators()) return false;
        if (target.invisible()) return false;
        if (!vanishBridge.canSee(viewerUuid, target.uuid())) return false;

        // Ring: client prefs intersect the server cap.
        double dx = target.x() - viewer.x();
        double dz = target.z() - viewer.z();
        double distSq = dx * dx + dz * dz;
        int max = settings.maxDistanceBlocks();
        var prefs = state.prefs;
        if (prefs != null && prefs.maxDistanceBlocks() > 0) {
            max = Math.min(max, prefs.maxDistanceBlocks());
        }
        int min = settings.minDistanceBlocks();
        if (prefs != null) {
            min = Math.max(min, prefs.minDistanceBlocks());
        }
        if (distSq > (double) max * max) return false;
        if (min > 0 && distSq < (double) min * min) return false;

        // Server-authoritative privacy (the ESP-oracle fix).
        if (isExcluded(target, settings.exclude())) return false;
        var targetState = viewers.get(target.uuid());
        var targetPrefs = targetState == null ? null : targetState.prefs;
        if ("opt-in".equals(settings.mode())) {
            // Only targets whose own client said shareSelf=true are served at all.
            if (targetPrefs == null || !targetPrefs.shareSelf()) return false;
        } else if (targetPrefs != null && !targetPrefs.shareSelf()) {
            // Mode "on": a target that EXPLICITLY opted out is honored.
            return false;
        }
        if (targetPrefs != null && targetPrefs.shareDistanceBlocks() > 0
                && distSq > (double) targetPrefs.shareDistanceBlocks()
                        * targetPrefs.shareDistanceBlocks()) {
            return false;
        }
        return true;
    }

    private static boolean isExcluded(PlayerSnapshot target, List<String> exclude) {
        if (exclude == null || exclude.isEmpty()) return false;
        String uuid = target.uuid().toString();
        for (String e : exclude) {
            if (e == null) continue;
            if (e.equalsIgnoreCase(target.name()) || e.equalsIgnoreCase(uuid)) return true;
        }
        return false;
    }

    /** Distance-tiered cadence divisor: 1 inside the near band, 2 beyond
     *  {@link #TIER_HALF_RATE_BLOCKS}, 4 beyond twice it. */
    private static int tierDivisor(PlayerSnapshot viewer, PlayerSnapshot target) {
        double dx = target.x() - viewer.x();
        double dz = target.z() - viewer.z();
        double distSq = dx * dx + dz * dz;
        double half = TIER_HALF_RATE_BLOCKS;
        if (distSq > 4 * half * half) return 4;
        if (distSq > half * half) return 2;
        return 1;
    }
}
