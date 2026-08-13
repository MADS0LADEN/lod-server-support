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
 *   <li><b>Vehicles render at E3</b> — a mounted target renders UNMOUNTED at its own
 *       wire position (the R-10 pre-rendering scenario: no crash, no seat math).</li>
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
    /** Small identity→Item cache (equipment strings repeat every frame). */
    private final Map<String, Item> itemCache = new ConcurrentHashMap<>();
    private int nextProxyId = PROXY_ID_BASE;
    private boolean crashLatched;

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
        proxies.clear();
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
        // The bit gate covers arm + the soak/benchmark properties; farPlayersEnabled
        // is checked HERE because the bit deliberately no longer carries it (the
        // subscription is the prefs carrier — E2 review M2): a disabled viewer still
        // delivers its shareSelf opt-out, it just renders nothing.
        if (FarPlayerClientSupport.capabilityBit() == 0 || !config.farPlayersEnabled) {
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
            updateWalkAnimation(position, allowWalk, gliding || swimming, animationTick);
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
