package com.baranhan123.supersonicflight.mixin;

import com.baranhan123.supersonicflight.util.GrabPunchManager;
import com.baranhan123.supersonicflight.util.GrabbedEntityIndex;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A held entity must not be shoved around by the world. {@code setNoAi} stops it from acting, but
 * vanilla collision pushing would still slide it out of the player's hand, so both push entry
 * points are cancelled for grabbed entities (in either direction).
 */
@Mixin(Entity.class)
public abstract class GrabbedEntityPhysicsMixin {

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void onPushEntity(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living && isGrabbed(living)) {
            ci.cancel();
            return;
        }
        if (other instanceof LivingEntity living && isGrabbed(living)) {
            ci.cancel();
        }
    }

    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void onPushVector(double x, double y, double z, CallbackInfo ci) {
        if ((Entity) (Object) this instanceof LivingEntity living && isGrabbed(living)) {
            ci.cancel();
        }
    }

    /**
     * {@code Entity#push} is a hot path — every entity collision, every tick, on both sides — so the
     * scan over players must only happen for entities that could possibly be held.
     *
     * <p>Two cheap gates because the two sides see different data: the {@code SupersonicGrabbed}
     * tag works on the server (which sets it), but tags are not part of {@code SynchedEntityData}
     * and so never reach a client — there the per-tick {@link GrabbedEntityIndex} is what answers.
     * Both are O(1), and either one missing a real grab is impossible: the tag is set the moment a
     * grab starts, and the index is rebuilt from the same synced target id every tick.
     */
    private static boolean isGrabbed(LivingEntity entity) {
        if (!entity.getTags().contains(GrabPunchManager.GRABBED_TAG)
                && !GrabbedEntityIndex.contains(entity)) {
            return false;
        }
        if (entity.level() == null) return false;

        for (Player player : entity.level().players()) {
            if (player instanceof SupersonicFlightPlayer core && core.getGrabbedTarget() == entity) {
                return true;
            }
        }
        return false;
    }
}
