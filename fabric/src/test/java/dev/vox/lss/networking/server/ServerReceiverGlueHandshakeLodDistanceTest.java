package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins handshake SessionConfig LOD distance resolution: the default when no per-world
 * overrides exist, and the matching override for the handshaking player's dimension.
 */
class ServerReceiverGlueHandshakeLodDistanceTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private int prevDefault;
    private java.util.Map<String, Integer> prevByWorld;

    @AfterEach
    void restoreConfig() {
        var config = LSSServerConfig.CONFIG;
        config.lodDistanceChunks = this.prevDefault;
        config.lodDistanceChunksByWorld = this.prevByWorld;
    }

    private void snapshotConfig() {
        var config = LSSServerConfig.CONFIG;
        this.prevDefault = config.lodDistanceChunks;
        this.prevByWorld = config.lodDistanceChunksByWorld == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(config.lodDistanceChunksByWorld);
    }

    private static ServerPlayer mockPlayer(ResourceKey<Level> dimension) {
        var player = mock(ServerPlayer.class);
        var level = mock(ServerLevel.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(Component.literal("handshake-test"));
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(dimension);
        return player;
    }

    @Test
    void handshakeSessionConfigUsesDefaultLodDistanceWhenOverridesAreEmpty() {
        snapshotConfig();
        var config = LSSServerConfig.CONFIG;
        config.lodDistanceChunks = 251;
        config.lodDistanceChunksByWorld = new LinkedHashMap<>();

        var player = mockPlayer(Level.OVERWORLD);

        var replies = new ArrayList<SessionConfigS2CPayload>();
        ServerReceiverGlue.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                        LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                player, null, replies::add);

        assertEquals(1, replies.size());
        assertEquals(251, replies.get(0).lodDistanceChunks());
    }

    @Test
    void handshakeSessionConfigUsesPerWorldLodDistanceForThePlayerDimension() {
        snapshotConfig();
        var config = LSSServerConfig.CONFIG;
        config.lodDistanceChunks = 128;
        config.lodDistanceChunksByWorld = new LinkedHashMap<>();
        config.lodDistanceChunksByWorld.put(LSSConstants.DIM_STR_THE_NETHER, 64);

        var player = mockPlayer(Level.NETHER);

        var replies = new ArrayList<SessionConfigS2CPayload>();
        ServerReceiverGlue.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                        LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                player, null, replies::add);

        assertEquals(1, replies.size());
        assertEquals(64, replies.get(0).lodDistanceChunks());
    }

    @Test
    void handshakeFallsBackToDefaultWhenPlayerLevelIsUnavailable() {
        snapshotConfig();
        var config = LSSServerConfig.CONFIG;
        config.lodDistanceChunks = 96;
        config.lodDistanceChunksByWorld = new LinkedHashMap<>();
        config.lodDistanceChunksByWorld.put(LSSConstants.DIM_STR_THE_NETHER, 4);

        var player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(Component.literal("handshake-test"));
        when(player.level()).thenThrow(new NullPointerException("unstubbed mock player"));

        var replies = new ArrayList<SessionConfigS2CPayload>();
        ServerReceiverGlue.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                        LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                player, null, replies::add);

        assertEquals(1, replies.size());
        assertEquals(96, replies.get(0).lodDistanceChunks());
    }
}
