package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Makes the left and right mouse buttons act on the mob being held, wherever the crosshair points.
 *
 * <p>Both skill inputs are dispatched by vanilla from {@code Minecraft#startAttack} (heavy punch) and
 * {@code Minecraft#startUseItem} (let go), and both branch on the crosshair result — a ray from the eye
 * along the look vector. The victim is pinned to the player's <b>hand</b> (see
 * {@code GrabPunchManager.resolveHandPos}), which sits off that axis, so the ray never reaches it:
 * vanilla falls through to a MISS and does nothing at all. Both inputs silently no-op.
 *
 * <p>Re-aiming the victim at the crosshair was rejected as the fix because the client's pick tests the
 * entity's <b>network-synced</b> position while the server pins it from its own view of the player —
 * one tick of client prediction apart, i.e. ~9 blocks at SONIC speed, so the ray would miss again at
 * exactly the speeds this skill is built around.
 *
 * <p>The two inputs are therefore handled differently, because the left click is <em>not</em> vanilla's
 * to dispatch once a combat mod is installed — see {@link #supersonicflight$attackHeldTarget}:
 *
 * <ul>
 *   <li><b>Left click</b> — the attack is dispatched directly, never through vanilla's branch.</li>
 *   <li><b>Right click</b> — the held entity is substituted for the crosshair result for the duration
 *       of the vanilla call, so vanilla's own ENTITY branch runs and all of its side effects (swing,
 *       interact packet) come along for free.</li>
 * </ul>
 *
 * <p>Inactive while nothing is held, so the <em>first</em> right-click (which must still hit the mob
 * being aimed at, to start the grab) and all ordinary interaction behave exactly as before.
 */
@Mixin(Minecraft.class)
public abstract class GrabbedAimTargetMixin {

    @Shadow
    public HitResult hitResult;

    /**
     * Left click. The punch is dispatched directly instead of by forging the crosshair result.
     *
     * <p>Better Combat takes the left click away from vanilla. Its {@code MinecraftClientInject} cancels
     * {@code startAttack} at HEAD whenever the main-hand item carries Better Combat weapon attributes,
     * then runs its own upswing/combo flow and sends its own {@code C2S_AttackRequest} packet. The
     * configured grab item is such a weapon, so vanilla's switch on {@link #hitResult} — and a forged
     * result with it — never executes, and the click produced no punch at all. Verified against
     * {@code bettercombat-neoforge-2.4.0}: {@code pre_doAttack} does
     * {@code WeaponRegistry.getAttributes(player.getMainHandItem())} and, on a hit,
     * {@code startUpswing(...)} + {@code CallbackInfoReturnable#cancel()}.
     *
     * <p>{@code MultiPlayerGameMode#attack} is exactly the call vanilla's own ENTITY branch makes, so the
     * server still receives the ordinary attack packet and {@code PlayerEntityMixin}'s punch hook fires.
     * Returning false keeps the click away from Better Combat's upswing, so left-clicking a held mob
     * cannot also swing at whatever else is in reach.
     */
    @WrapMethod(method = "startAttack")
    private boolean supersonicflight$attackHeldTarget(Operation<Boolean> original) {
        Minecraft self = (Minecraft) (Object) this;
        LivingEntity held = supersonicflight$heldTarget();
        if (held == null) return original.call();

        self.gameMode.attack(self.player, held);
        return false;
    }

    /**
     * Right click. Vanilla's ENTITY branch reaches {@code GrabPunchHandler}, which lets go.
     *
     * <p>This one can stay on vanilla's path: Better Combat's {@code pre_doItemUse} only cancels while
     * an upswing is running, and the left-click handler above prevents any upswing from starting while
     * something is held.
     */
    @WrapMethod(method = "startUseItem")
    private void supersonicflight$useOnHeldTarget(Operation<Void> original) {
        LivingEntity held = supersonicflight$heldTarget();
        if (held == null) {
            original.call();
            return;
        }

        HitResult crosshair = this.hitResult;
        // Location is the entity's own position, which is what vanilla's own pick would have reported.
        this.hitResult = new EntityHitResult(held);
        try {
            original.call();
        } finally {
            this.hitResult = crosshair;
        }
    }

    /** The mob currently being held, or null when there is none worth acting on. */
    @Unique
    private LivingEntity supersonicflight$heldTarget() {
        Minecraft self = (Minecraft) (Object) this;
        if (!(self.player instanceof SupersonicFlightPlayer core)) return null;

        LivingEntity held = core.getGrabbedTarget();
        if (held == null || held.isRemoved() || !held.isAlive()) return null;
        return held;
    }
}
