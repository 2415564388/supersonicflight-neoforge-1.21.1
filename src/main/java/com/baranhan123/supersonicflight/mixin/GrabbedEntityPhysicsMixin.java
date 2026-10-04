package com.baranhan123.supersonicflight.mixin;

import com.baranhan123.supersonicflight.util.GrabPunchManager;
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
     * {@code Entity#push} is a hot path — it runs for every entity collision, every tick, on both
     * sides — so the scan over players only happens for entities that could possibly be held. The
     * tag is set the moment a grab starts and cleared on release, so this cannot miss a real grab.
     */
    private static boolean isGrabbed(LivingEntity entity) {
        if (!entity.getTags().contains(GrabPunchManager.GRABBED_TAG)) return false;
        if (entity.level() == null) return false;

        for (Player player : entity.level().players()) {
            if (player instanceof SupersonicFlightPlayer core && core.getGrabbedTarget() == entity) {
                return true;
            }
        }
        return false;
    }
}
