package dev.vox.lss.trace;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field/method-existence pins (move-desync-tracer-plan.md §3, review F-1): every member
 * the tracer touches on MC classes, verified reflectively against the REAL named classes
 * (the test JVM runs in the named namespace — the {@code SaveHookContractTest}
 * "target still exists" idiom). A future MC rename turns into a red test at build time
 * instead of a silently degraded tracer.
 *
 * <p>The Moonrise members ({@code moonrise$getChunkLoader}, {@code sentChunks} et al.)
 * are pinned by {@link MoonriseSendStateCompatTest} against the real-package-name stubs —
 * the real jar is not on any test classpath; the stub shape was verified against
 * Moonrise-Fabric 1.1.0 ({@code moonrise-opt-W0HImEBl}) on 2026-08-06 and the live
 * tripwire is the deploy ladder's {@code rung=} diag check (§4.3).
 */
class MoveTraceFieldPinsTest {

    @Test
    void accessorTargetFieldsStillExist() throws Exception {
        assertField("firstGoodX", double.class);
        assertField("firstGoodY", double.class);
        assertField("firstGoodZ", double.class);
        assertField("lastGoodX", double.class);
        assertField("lastGoodY", double.class);
        assertField("lastGoodZ", double.class);
        assertField("receivedMovePacketCount", int.class);
        assertField("knownMovePacketCount", int.class);
        assertField("awaitingPositionFromClient", Vec3.class);
    }

    @Test
    void publicMembersTheHooksAndVanillaRungRelyOn() throws Exception {
        Field player = ServerGamePacketListenerImpl.class.getDeclaredField("player");
        assertTrue(Modifier.isPublic(player.getModifiers()), "hooks read listener.player directly");

        Field chunkSender = ServerGamePacketListenerImpl.class.getDeclaredField("chunkSender");
        assertTrue(Modifier.isPublic(chunkSender.getModifiers()),
                "the vanilla rung reads chunkSender without an accessor (review F-13)");
        assertEquals(PlayerChunkSender.class, chunkSender.getType());

        var isPending = PlayerChunkSender.class.getDeclaredMethod("isPending", long.class);
        assertTrue(Modifier.isPublic(isPending.getModifiers()));

        var latency = ServerCommonPacketListenerImpl.class.getDeclaredMethod("latency");
        assertTrue(Modifier.isPublic(latency.getModifiers()));
        assertEquals(int.class, latency.getReturnType());
    }

    @Test
    void rejectionTeleportTargetStillExists() throws Exception {
        var teleport = ServerGamePacketListenerImpl.class.getDeclaredMethod("teleport",
                double.class, double.class, double.class, float.class, float.class);
        assertTrue(Modifier.isPublic(teleport.getModifiers()),
                "the slice-anchored rejection target is teleport(DDDFF)V");
    }

    @Test
    void movePacketGettersTheClaimRecomputationUses() throws Exception {
        for (String getter : new String[] {"getX", "getY", "getZ"}) {
            var m = ServerboundMovePlayerPacket.class.getMethod(getter, double.class);
            assertEquals(double.class, m.getReturnType(),
                    getter + "(default) is the claimed-target recomputation input (review F-5)");
        }
        ServerboundMovePlayerPacket.class.getMethod("hasPosition");
        ServerboundMovePlayerPacket.class.getMethod("hasRotation");
    }

    private static void assertField(String name, Class<?> type) throws Exception {
        Field f = ServerGamePacketListenerImpl.class.getDeclaredField(name);
        assertEquals(type, f.getType(), name);
    }
}
