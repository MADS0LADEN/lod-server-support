package dev.vox.lss.mixin.trace;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the movement-check state of {@code handleMovePlayer} to the move-desync tracer
 * (move-desync-tracer-plan.md §1.2). Lives in the NON-REQUIRED {@code lss-trace.mixins.json}
 * config, never {@code lss.mixins.json}: {@code @Accessor} has no soft-fail path (it throws
 * unconditionally at class transform on a missing field), and under a required config that
 * would hard-crash every server — including tracer-DISABLED ones — on any future MC field
 * rename (review F-1). In the non-required config a miss downgrades to a mixin WARN, the
 * cast in the hook body throws {@code ClassCastException} into the tracer's catch-all, and
 * the affected fields go absent. Tier 1 field-existence pins turn the same drift into a
 * red test at build time.
 *
 * <p>Methods are {@code lss$}-prefixed because mixin adds them to the target class.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface AccessorServerGamePacketListener {

    @Accessor("firstGoodX")
    double lss$firstGoodX();

    @Accessor("firstGoodY")
    double lss$firstGoodY();

    @Accessor("firstGoodZ")
    double lss$firstGoodZ();

    @Accessor("lastGoodX")
    double lss$lastGoodX();

    @Accessor("lastGoodY")
    double lss$lastGoodY();

    @Accessor("lastGoodZ")
    double lss$lastGoodZ();

    @Accessor("receivedMovePacketCount")
    int lss$receivedMovePacketCount();

    @Accessor("knownMovePacketCount")
    int lss$knownMovePacketCount();

    @Accessor("awaitingPositionFromClient")
    Vec3 lss$awaitingPositionFromClient();
}
