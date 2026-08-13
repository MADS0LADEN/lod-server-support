package dev.vox.lss.networking.client;

import com.mojang.authlib.GameProfile;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlayerClientTracker;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSClientConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far-player proxy renderer (E2, FARP §3.3/§7-B — the SeeU
 * {@code RemotePlayer}-proxy + {@code LevelRenderContext} submission approach, proven
 * on 26.2, reimplemented in LSS idiom). Differences from SeeU that are DECISIONS, not
 * drift (all review-pinned in the FARP plan):
 *
 * <ul>
 *   <li><b>No glow, ever, by default</b> — SeeU's {@code setGlowingTag(true)} is a
 *       through-wall outline that contradicts the privacy stance.</li>
 *   <li><b>No fog mixin</b> — a proxy beyond fog-end fades like terrain would;
 *       {@code farPlayersMaxRenderDistanceBlocks} is the alignment knob.</li>
 *   <li><b>Handoff = vanilla's own cull test</b> (E2 review M3 — this REPLACES the
 *       planned Euclidean ±16 band, decisions log entry 16): the proxy renders
 *       exactly when the REAL entity would not — {@code real == null || !chunkLoaded
 *       || !real.shouldRenderAtSqrDistance(camDistSq)}. A distance band measured
 *       Euclidean against square chunk geometry both double-rendered at the render
 *       square's diagonal AND left an invisibility annulus at high render distance
 *       (entity cull ~256 blocks sits far inside a 32-chunk circle). Keying on the
 *       same predicate both directions self-synchronizes the swap with vanilla's
 *       entity pop (the exact crossfade), so no hysteresis band is needed;
 *       {@code ClientEntityEvents.ENTITY_LOAD} stays as the same-frame kill when a
 *       real entity spawns in.</li>
 *   <li><b>Mounts (E3, R-10 v1.3)</b>: the rider renders at its OWN wire position
 *       (the server-side seated position already encodes the seat offset); the mount
 *       renders once per vehicle UUID at its own wire position driven by the RIDER's
 *       velocity hint; an edge-triggered persistent ride link (never per-frame
 *       eject/re-seat) makes isPassenger() true at extraction — vanilla's one path
 *       to seated legs. Degrade ladder in {@link FarPlayerMountLadder}: unknown/
 *       uncreatable types render the rider unmounted, ride-link failure renders
 *       standing at the mounted position — never a crash, never a floating sit.</li>
 *   <li><b>Containment</b>: the whole pass is latch-guarded — a renderer bug degrades
 *       to "no proxies" for the session, never a render-thread crash loop.</li>
 * </ul>
 *
 * <p>Threading: every touchpoint runs on the client MAIN thread, which IS the render
 * thread (network receivers hop via execute(), ENTITY_LOAD fires from addEntity,
 * COLLECT_SUBMITS is the main-thread extract/submit phase). snapshot() is a shallow
 * copy sharing mutable FarPlayerMotion — it is defense-in-depth, NOT a thread
 * boundary; do not move this pass off-thread trusting it (E2 review n9).
 */
public final class FarPlayerRenderer {

    /** Proxy entity-id base: far above vanilla's server-assigned counter AND disjoint
     *  from SeeU's 1_000_000_000 block (both installed must never collide). Each id is
     *  additionally probed against the live level before use. */
    private static final int PROXY_ID_BASE = 1_900_000_000;

    private static final float WALK_ANIMATION_SCALE = 0.4f;

    private static volatile FarPlayerRenderer instance;

    private final Map<UUID, Proxy> proxies = new HashMap<>();
    /** Render-only mount instances by vehicle UUID (R-10): rider-velocity motion,
     *  evicted when no rider references the UUID in the current frame. Two riders
     *  sharing a vehicle render it ONCE (multi-passenger dedup = render dedup). */
    private final Map<UUID, MountInstance> vehicles = new HashMap<>();
    private final Set<UUID> activeVehicles = new HashSet<>();
    private final Set<UUID> submittedVehicles = new HashSet<>();
    private final FarPlayerMountLadder mountLadder = FarPlayerMountLadder.production();
    /** Small identity→Item cache (equipment strings repeat every frame). */
    private final Map<String, Item> itemCache = new ConcurrentHashMap<>();
    private int nextProxyId = PROXY_ID_BASE;
    private boolean crashLatched;

    private static final class MountInstance {
        final net.minecraft.world.entity.Entity entity;
        final String typeIdentity;
        final dev.vox.lss.common.farplayers.FarPlayerMotion motion;
        long appliedStamp;

        MountInstance(net.minecraft.world.entity.Entity entity, String typeIdentity,
                      dev.vox.lss.common.farplayers.FarPlayerMotion motion) {
            this.entity = entity;
            this.typeIdentity = typeIdentity;
            this.motion = motion;
        }
    }

    /** Installed by {@link FarPlayerClientSupport#initRenderer()} (called from
     *  {@code LSSClient}); static so the session-end path can clear it. */
    static void install(FarPlayerRenderer renderer) {
        instance = renderer;
    }

    /** Session end: proxies die AND the crash latch resets (E2 review m5 — one
     *  contained throw next session beats a per-JVM dead feature). */
    static void clearInstance() {
        var r = instance;
        if (r != null) {
            r.clear();
            r.crashLatched = false;
        }
    }

    /** The ENTITY_LOAD edge trigger: a REAL player entity appearing kills its proxy
     *  the same frame (crossfade guard — never render both). Main client thread. */
    static void onRealPlayerLoad(UUID uuid) {
        var r = instance;
        if (r != null) {
            r.proxies.remove(uuid);
        }
    }

    void clear() {
        // Break ride links before dropping the maps (the SeeU precaution): a pruned
        // proxy still referencing a mount keeps both reachable together otherwise.
        for (var mount : vehicles.values()) {
            mount.entity.ejectPassengers();
        }
        proxies.clear();
        vehicles.clear();
        activeVehicles.clear();
        submittedVehicles.clear();
    }

    /** The COLLECT_SUBMITS pass. */
    public void render(LevelRenderContext context) {
        if (crashLatched) return;
        try {
            renderContained(context);
        } catch (Throwable t) {
            crashLatched = true;
            clear();
            LSSLogger.error("Far-player renderer failed — proxies disabled for this session"
                    + " (a renderer bug must never take the render thread down)", t);
        }
    }

    private void renderContained(LevelRenderContext context) {
        var config = LSSClientConfig.CONFIG;
        // The bit gate covers arm + the soak/benchmark properties; the EFFECTIVE
        // enabled term (config AND the SeeU-coexist gate, E3) is checked HERE because
        // the bit deliberately no longer carries it (the subscription is the prefs
        // carrier — E2 review M2): a disabled viewer still delivers its shareSelf
        // opt-out, it just renders nothing.
        if (FarPlayerClientSupport.capabilityBit() == 0
                || !FarPlayerClientSupport.effectiveFarPlayersEnabled()) {
            if (!proxies.isEmpty()) clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        var localPlayer = minecraft.player;
        var poseStack = context.poseStack();
        if (level == null || localPlayer == null || poseStack == null
                || context.submitNodeCollector() == null) {
            clear();
            return;
        }

        FarPlayerClientTracker tracker = FarPlayerClientSupport.tracker();
        String trackerDimension = tracker.dimension();
        if (trackerDimension == null
                || !trackerDimension.equals(level.dimension().identifier().toString())) {
            clear();
            return;
        }

        Vec3 cameraPosition = minecraft.gameRenderer.mainCamera().position();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int animationTick = localPlayer.tickCount;
        long now = FarPlayerClientSupport.monotonicMillis();
        int maxRender = config.farPlayersMaxRenderDistanceBlocks;
        int minRender = config.farPlayersMinDistanceBlocks;

        Set<UUID> active = new HashSet<>();
        for (var tracked : tracker.snapshot().values()) {
            var sample = tracked.motion().sample(now);
            Vec3 position = new Vec3(sample.x(), sample.y(), sample.z());
            double distance = position.distanceTo(localPlayer.position());
            if (distance < minRender || (maxRender > 0 && distance > maxRender)) {
                continue;
            }

            // Handoff = vanilla's own draw decision (M3 — see the class javadoc):
            // the proxy renders exactly when the real entity would not. Same
            // predicate both directions, so the swap is frame-synchronized with
            // vanilla's entity pop — no band, no flicker, no diagonal double-render,
            // no high-render-distance invisibility annulus.
            var real = level.getPlayerByUUID(tracked.uuid());
            if (real != null
                    && level.hasChunk(Mth.floor(position.x) >> 4,
                            Mth.floor(position.z) >> 4)
                    && real.shouldRenderAtSqrDistance(
                            cameraPosition.distanceToSqr(real.position()))) {
                continue;
            }

            Proxy proxy = proxies.compute(tracked.uuid(), (uuid, current) ->
                    current == null || current.level() != level
                            ? new Proxy(level, uuid, tracked.name(), nextEntityId(level))
                            : current);
            boolean allowWalk = config.farPlayersMaxAnimationDistanceBlocks > 0
                    && distance <= config.farPlayersMaxAnimationDistanceBlocks;
            proxy.apply(tracked, sample, position, config.farPlayersNameTags,
                    maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                    itemCache);
            active.add(tracked.uuid());

            // R-10 v1.3 mounts: the rider renders at its OWN wire position (the
            // server-side seated position already encodes the seat offset); the ride
            // link exists ONLY to make isPassenger() true at render-state extraction
            // (the one vanilla path to seated legs). Edge-triggered — never SeeU's
            // per-frame eject/re-seat.
            var wireVehicle = tracked.latest().vehicle();
            if (wireVehicle != null) {
                var mount = mountFor(wireVehicle, tracked, level, now);
                if (mount != null) {
                    activeVehicles.add(wireVehicle.uuid());
                    if (proxy.getVehicle() != mount.entity) {
                        proxy.stopRiding();
                        // 26.2's 3-arg overload: the 1-arg form delegates to
                        // (entity, false, true) — bytecode-checked — so this passes
                        // force=TRUE (render-only instances can fail vanilla's
                        // canRide/distance checks) and keeps the third arg at its
                        // vanilla default. A false return is rung 3 of the ladder —
                        // mounted-position standing pose, never a floating sit.
                        proxy.startRiding(mount.entity, true, true);
                    }
                    if (submittedVehicles.add(wireVehicle.uuid())) {
                        var vSample = mount.motion.sample(now);
                        applyMountState(mount.entity, vSample, animationTick);
                        var vState = dispatcher.extractEntity(mount.entity, partialTick);
                        dispatcher.submit(
                                vState,
                                context.levelState().cameraRenderState,
                                vSample.x() - cameraPosition.x,
                                vSample.y() - cameraPosition.y,
                                vSample.z() - cameraPosition.z,
                                poseStack,
                                context.submitNodeCollector());
                    }
                } else if (proxy.isPassenger()) {
                    proxy.stopRiding(); // ladder-degraded: render unmounted
                }
            } else if (proxy.isPassenger()) {
                proxy.stopRiding(); // dismount edge
            }

            var renderState = dispatcher.extractEntity(proxy, partialTick);
            dispatcher.submit(
                    renderState,
                    context.levelState().cameraRenderState,
                    position.x - cameraPosition.x,
                    position.y - cameraPosition.y,
                    position.z - cameraPosition.z,
                    poseStack,
                    context.submitNodeCollector());
        }
        proxies.keySet().removeIf(uuid -> !active.contains(uuid));
        // Vehicle lifecycle (R-10): evict instances no rider referenced this frame
        // (level-change/type-change recreation happens in mountFor).
        vehicles.keySet().removeIf(uuid -> !activeVehicles.contains(uuid));
        activeVehicles.clear();
        submittedVehicles.clear();
    }

    /** Resolves/creates/updates the render-only mount instance for one rider's wire
     *  vehicle block. Null = ladder-degraded (render the rider unmounted). */
    private MountInstance mountFor(dev.vox.lss.common.farplayers.FarPlayerWire.Vehicle wire,
                                   FarPlayerClientTracker.TrackedFarPlayer rider,
                                   ClientLevel level, long now) {
        MountInstance mount = vehicles.get(wire.uuid());
        if (mount != null
                && (mount.entity.level() != level || !mount.typeIdentity.equals(wire.typeIdentity()))) {
            mount = null; // level change pins the old ClientLevel; same-UUID new type recreates
        }
        if (mount == null) {
            var entity = mountLadder.createMount(wire.typeIdentity(), level);
            if (entity == null) return null;
            entity.setId(nextEntityId(level));
            entity.noPhysics = true;
            entity.setNoGravity(true);
            entity.setInvisible(false);
            mount = new MountInstance(entity, wire.typeIdentity(),
                    new dev.vox.lss.common.farplayers.FarPlayerMotion(
                            FarPlayerWire.dequantizePos(wire.quantX()),
                            FarPlayerWire.dequantizePos(wire.quantY()),
                            FarPlayerWire.dequantizePos(wire.quantZ()),
                            FarPlayerWire.byteToAngle(wire.yaw()),
                            FarPlayerWire.byteToAngle(wire.pitch()),
                            rider.cadenceTicks(), now));
            mount.appliedStamp = rider.receivedAtMillis();
            vehicles.put(wire.uuid(), mount);
        } else if (mount.appliedStamp != rider.receivedAtMillis()) {
            // A new updates frame for this rider: feed the mount's wire position with
            // the RIDER's velocity hint (R-10 v1.3 — they share a velocity by
            // definition; separate hints shear visibly at horse/boat speeds).
            var e = rider.latest();
            mount.motion.applyRaw(
                    FarPlayerWire.dequantizePos(wire.quantX()),
                    FarPlayerWire.dequantizePos(wire.quantY()),
                    FarPlayerWire.dequantizePos(wire.quantZ()),
                    FarPlayerWire.byteToAngle(wire.yaw()),
                    FarPlayerWire.byteToAngle(wire.yaw()),
                    FarPlayerWire.byteToAngle(wire.pitch()),
                    FarPlayerWire.shortToVelocity(e.velX()),
                    FarPlayerWire.shortToVelocity(e.velY()),
                    FarPlayerWire.shortToVelocity(e.velZ()),
                    rider.cadenceTicks(), rider.receivedAtMillis());
            mount.appliedStamp = rider.receivedAtMillis();
        }
        return mount;
    }

    private static void applyMountState(net.minecraft.world.entity.Entity entity,
                                        dev.vox.lss.common.farplayers.FarPlayerMotion.Sample s,
                                        int animationTick) {
        Vec3 position = new Vec3(s.x(), s.y(), s.z());
        entity.tickCount = animationTick;
        entity.setOldPosAndRot(position, s.yaw(), s.pitch());
        entity.xo = position.x;
        entity.yo = position.y;
        entity.zo = position.z;
        entity.xOld = position.x;
        entity.yOld = position.y;
        entity.zOld = position.z;
        entity.snapTo(position, s.yaw(), s.pitch());
        entity.setYRot(s.yaw());
        entity.yRotO = s.yaw();
        entity.setXRot(s.pitch());
        entity.xRotO = s.pitch();
    }

    /** Monotonic id from the LSS block, probed against the live level (a taken id —
     *  another mod's synthetic entity — is skipped, never reused). */
    private int nextEntityId(ClientLevel level) {
        int id = nextProxyId;
        while (level.getEntity(id) != null) id++;
        nextProxyId = id + 1;
        if (nextProxyId >= Integer.MAX_VALUE - 4096) nextProxyId = PROXY_ID_BASE;
        return id;
    }

    private static final class Proxy extends RemotePlayer {
        private final UUID trackedUuid;
        private int maxRenderDistanceBlocks = 16384;
        private Vec3 lastWalkPosition;
        private int lastWalkTick = Integer.MIN_VALUE;

        private Proxy(ClientLevel level, UUID trackedUuid, String name, int entityId) {
            super(level, new GameProfile(trackedUuid, name));
            this.trackedUuid = trackedUuid;
            this.setId(entityId);
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setInvisible(false);
            // Deliberately NO setGlowingTag — see the class javadoc.
        }

        void apply(FarPlayerClientTracker.TrackedFarPlayer tracked,
                   dev.vox.lss.common.farplayers.FarPlayerMotion.Sample sample,
                   Vec3 position, boolean nameTags, int maxRenderDistanceBlocks,
                   boolean allowWalk, int animationTick, Map<String, Item> itemCache) {
            byte pose = tracked.latest().poseFlags();
            boolean gliding = (pose & FarPlayerWire.POSE_GLIDE) != 0;
            boolean swimming = (pose & FarPlayerWire.POSE_SWIM) != 0;
            boolean sneaking = (pose & FarPlayerWire.POSE_SNEAK) != 0;

            this.maxRenderDistanceBlocks = maxRenderDistanceBlocks;
            this.tickCount = animationTick;
            this.setOldPosAndRot(position, sample.yaw(), sample.pitch());
            this.xo = position.x;
            this.yo = position.y;
            this.zo = position.z;
            this.xOld = position.x;
            this.yOld = position.y;
            this.zOld = position.z;
            this.snapTo(position, sample.yaw(), sample.pitch());
            this.setYRot(sample.yaw());
            this.yRotO = sample.yaw();
            this.setXRot(sample.pitch());
            this.xRotO = sample.pitch();
            this.setYBodyRot(sample.yaw());
            this.yBodyRotO = sample.yaw();
            this.setYHeadRot(sample.headYaw());
            this.yHeadRotO = sample.headYaw();
            this.setShiftKeyDown(sneaking);
            this.setSwimming(swimming);
            this.setPose(gliding ? Pose.FALL_FLYING
                    : swimming ? Pose.SWIMMING
                    : sneaking ? Pose.CROUCHING
                    : Pose.STANDING);
            applyEquipment(tracked, itemCache);
            this.setCustomName(Component.literal(tracked.name()));
            this.setCustomNameVisible(nameTags);
            updateWalkAnimation(position, allowWalk,
                    gliding || swimming || tracked.latest().vehicle() != null,
                    animationTick);
        }

        private void applyEquipment(FarPlayerClientTracker.TrackedFarPlayer tracked,
                                    Map<String, Item> itemCache) {
            // Wire slot order (FarPlayerWire/EQUIPMENT docs): HEAD CHEST LEGS FEET MAIN OFF.
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
            String[] ids = tracked.equipmentIdentities();
            int[] counts = tracked.equipmentCounts();
            for (int i = 0; i < slots.length; i++) {
                this.setItemSlot(slots[i], stackFor(
                        ids == null ? null : ids[i],
                        counts == null ? 1 : Math.max(1, counts[i]), itemCache));
            }
        }

        private static ItemStack stackFor(String identity, int count,
                                          Map<String, Item> itemCache) {
            if (identity == null) return ItemStack.EMPTY;
            Item item = itemCache.computeIfAbsent(identity, id -> {
                try {
                    // Cross-version sessions (Via) can carry identities this client's
                    // registry lacks — an unknown identity renders as an EMPTY slot,
                    // the far-player analogue of the column fallback ladder.
                    return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                } catch (Exception e) {
                    return Items.AIR;
                }
            });
            return item == null || item == Items.AIR ? ItemStack.EMPTY
                    : new ItemStack(item, count);
        }

        private void updateWalkAnimation(Vec3 position, boolean allowWalk,
                                         boolean nonWalkingPose, int animationTick) {
            if (lastWalkPosition == null || animationTick == lastWalkTick) {
                if (lastWalkPosition == null) {
                    lastWalkPosition = position;
                    lastWalkTick = animationTick;
                    this.walkAnimation.stop();
                }
                return;
            }
            lastWalkTick = animationTick;
            if (!allowWalk || nonWalkingPose) {
                this.walkAnimation.stop();
                lastWalkPosition = position;
                return;
            }
            float movement = (float) Mth.length(position.x - lastWalkPosition.x, 0,
                    position.z - lastWalkPosition.z);
            this.walkAnimation.update(Math.min(movement * 4.0f, 1.0f),
                    WALK_ANIMATION_SCALE, 1.0f);
            lastWalkPosition = position;
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            // TAB-listed players carry skins; the proxy borrows them (SeeU's approach).
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                PlayerInfo info = connection.getPlayerInfo(trackedUuid);
                if (info != null) return info;
            }
            return super.getPlayerInfo();
        }

        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSquared) {
            double max = Math.max(64, maxRenderDistanceBlocks);
            return distanceSquared <= max * max;
        }
    }
}
