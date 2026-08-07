package dev.vox.lss.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.vox.lss.trace.MoveDesyncTracer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tier 2 pins for the move-desync tracer's hook layer (move-desync-tracer-plan.md §3):
 * real {@code handleMovePlayer} dispatch through the production mixins, asserted at the
 * tracer's in-memory test sink ({@code enableForTest} — the gametest JVM does not set the
 * activation property).
 *
 * <p>The stock mock player is hard-coded CREATIVE (review F-2: {@code GameTestHelper$3}
 * overrides {@code gameMode()}), which gates the {@code moved wrongly!} warn off — so the
 * wrongly assertion hand-rolls a survival player replicating the factory's steps. The
 * SILENT rejection, by contrast, is reachable in creative ({@code
 * isEntityCollidingWithAnythingNew} fires on target-position collisions regardless of
 * game mode) — which is exactly the zero-observability path the tracer exists for.
 *
 * <p>Each assertion uses a fresh player: a rejection latches
 * {@code awaitingPositionFromClient} and every later move early-returns; the mock
 * connection never ticks, so {@code resetPosition()} reseeds per call (budgeted, not
 * fought). Tracer enable/drive/assert/disable all happen synchronously inside one test
 * body on the server thread, so concurrent gametests cannot interleave rows.
 */
public class MoveTraceGameTests {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void tooQuicklyClaimEmitsRowWithBothPacketCounts(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var origin = helper.absolutePos(BlockPos.ZERO);
        double x = origin.getX() + 0.5;
        double z = origin.getZ() + 0.5;
        player.absSnapTo(x, 200, z);

        var rows = drive(helper, player,
                new ServerboundMovePlayerPacket.Pos(x + 18.5, 200, z, false, false));

        var tooQuickly = rowsOfType(rows, "too_quickly", player.getUUID());
        helper.assertTrue(tooQuickly.size() == 1,
                "expected exactly one too_quickly row, got " + tooQuickly.size() + " in " + rows);
        var row = tooQuickly.get(0);
        helper.assertTrue(row.has("delta_packets") && row.has("delta_packets_used"),
                "both packet-count fields must be present (review F-8): " + row);
        helper.assertTrue(row.get("delta_packets_used").getAsInt() >= 1,
                "the post-clamp count the check applied must be >= 1: " + row);
        helper.assertTrue(row.has("expected_dist") && row.has("origin") && row.has("claimed"),
                "check-input reconstruction fields must be present: " + row);
        helper.assertTrue(!row.has("simulated") && !row.has("residual"),
                "too_quickly fires before move() — no simulated stop may exist (U-14): " + row);
        helper.assertTrue(rowsOfType(rows, "rejected", player.getUUID()).isEmpty(),
                "the too-quickly teleport is NOT the slice-anchored rejection: " + rows);
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void silentRejectionIntoPlacedBlocksEmitsRejectedRow(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var origin = helper.absolutePos(BlockPos.ZERO);
        double x = origin.getX() + 0.5;
        double z = origin.getZ() + 0.5;
        player.absSnapTo(x, 210, z);
        // A solid 3x3x3 block of stone with its center ~3 blocks +x of the player: the
        // claimed AABB ends inside it, the pre-move AABB is free air.
        var level = helper.getLevel();
        var center = BlockPos.containing(x + 3, 210.9, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(center.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }

        var rows = drive(helper, player,
                new ServerboundMovePlayerPacket.Pos(x + 3, 210, z, false, false));

        var rejected = rowsOfType(rows, "rejected", player.getUUID());
        helper.assertTrue(rejected.size() == 1,
                "expected exactly one rejected row, got " + rejected.size() + " in " + rows);
        var row = rejected.get(0);
        helper.assertTrue(row.has("logged_wrongly") && !row.get("logged_wrongly").getAsBoolean(),
                "a CREATIVE player cannot fire the wrongly warn — this rejection is the"
                        + " SILENT path (logged_wrongly=false), the tracer's whole point: " + row);
        helper.assertTrue(rowsOfType(rows, "wrongly", player.getUUID()).isEmpty(),
                "no wrongly warn may fire for a creative player: " + rows);
        helper.assertTrue(row.has("simulated") && row.has("residual") && row.has("restored"),
                "collision-event geometry must be present: " + row);
        helper.assertTrue(row.get("residual").getAsDouble() > 0.25,
                "the claim ended inside the wall — residual must be substantial: " + row);
        helper.assertTrue(row.has("send_state") && row.has("send_state_claimed"),
                "send-state for BOTH the simulated-stop and claimed chunks: " + row);
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void survivalWronglyClaimEmitsWronglyAndLoggedRejection(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        // Hand-rolled SURVIVAL player replicating makeMockServerPlayerInLevel's steps —
        // the stock mock hard-codes CREATIVE and the wrongly warn is gated !isCreative
        // (review F-2).
        var profile = new GameProfile(UUID.randomUUID(), "MoveTraceSvl");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            player.setGameMode(GameType.SURVIVAL);
            var origin = helper.absolutePos(BlockPos.ZERO);
            double x = origin.getX() + 0.5;
            double z = origin.getZ() + 0.5;
            player.absSnapTo(x, 220, z);
            var center = BlockPos.containing(x + 3, 220.9, z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlockAndUpdate(center.offset(dx, dy, dz),
                                Blocks.STONE.defaultBlockState());
                    }
                }
            }

            var rows = drive(helper, player,
                    new ServerboundMovePlayerPacket.Pos(x + 3, 220, z, false, false));

            var wrongly = rowsOfType(rows, "wrongly", player.getUUID());
            helper.assertTrue(wrongly.size() == 1,
                    "expected exactly one wrongly row, got " + wrongly.size() + " in " + rows);
            var rejected = rowsOfType(rows, "rejected", player.getUUID());
            helper.assertTrue(rejected.size() == 1,
                    "the wrongly claim must also reject, got " + rejected.size() + " in " + rows);
            helper.assertTrue(rejected.get(0).get("logged_wrongly").getAsBoolean(),
                    "the rejection follows the warn for the SAME packet — the identity token"
                            + " must read true (review F-3): " + rejected.get(0));
            var restored = rejected.get(0).getAsJsonArray("restored");
            helper.assertTrue(Math.abs(restored.get(0).getAsDouble() - x) < 1e-6,
                    "restored must be the pre-move position (the player-felt snap target,"
                            + " review F-9): " + rejected.get(0));
        } finally {
            server.getPlayerList().remove(player);
        }
        helper.succeed();
    }

    // ---- helpers ----

    /** Enable → drive → disable, all synchronous on the server thread. */
    private static List<String> drive(GameTestHelper helper, ServerPlayer player,
                                      ServerboundMovePlayerPacket packet) {
        var rows = new ArrayList<String>();
        MoveDesyncTracer.enableForTest(rows::add);
        try {
            primeListenerForMoves(player.connection);
            player.connection.handleMovePlayer(packet);
        } finally {
            MoveDesyncTracer.disableForTest();
        }
        return rows;
    }

    /** A mock connection never sends the client's side of the placement handshake:
     *  the placement teleport latches {@code awaitingPositionFromClient} (every move is
     *  swallowed by updateAwaitingTeleport) and placeNewPlayer arms the 60-tick
     *  {@code clientLoadedTimeoutTimer} (hasClientLoaded() reads false until a real
     *  client confirms). Both cleared here — test-only reflection, dev runtime = named
     *  mappings; a vanilla rename reds this loudly. */
    private static void primeListenerForMoves(ServerGamePacketListenerImpl connection) {
        try {
            var awaiting = ServerGamePacketListenerImpl.class
                    .getDeclaredField("awaitingPositionFromClient");
            awaiting.setAccessible(true);
            awaiting.set(connection, null);
            var timer = ServerGamePacketListenerImpl.class
                    .getDeclaredField("clientLoadedTimeoutTimer");
            timer.setAccessible(true);
            timer.setInt(connection, 0);
            var waiting = ServerGamePacketListenerImpl.class
                    .getDeclaredField("waitingForRespawn");
            waiting.setAccessible(true);
            waiting.setBoolean(connection, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not prime the listener for moves — "
                    + "did vanilla rename a field? (awaitingPositionFromClient /"
                    + " clientLoadedTimeoutTimer / waitingForRespawn)", e);
        }
    }

    private static List<JsonObject> rowsOfType(List<String> rows, String type, UUID player) {
        var out = new ArrayList<JsonObject>();
        for (String row : rows) {
            var obj = JsonParser.parseString(row).getAsJsonObject();
            if (type.equals(obj.get("type").getAsString())
                    && obj.has("player")
                    && player.toString().equals(obj.get("player").getAsString())) {
                out.add(obj);
            }
        }
        return out;
    }
}
