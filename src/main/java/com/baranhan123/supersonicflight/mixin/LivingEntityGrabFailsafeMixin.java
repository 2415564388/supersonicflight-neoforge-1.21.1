package com.baranhan123.supersonicflight.mixin;

import com.baranhan123.supersonicflight.util.GrabPunchManager;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Safety net for held entities. If the grab ends without {@code releaseTarget} running — the
 * grabbing player disconnected, died, or the chunk unloaded — the entity would otherwise be stuck
 * with its AI permanently disabled. The grabbed tag is persisted to NBT, so this also cleans up
 * across a world reload.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGrabFailsafeMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickFailsafe(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.level().isClientSide()) return;
        if (!entity.getTags().contains(GrabPunchManager.GRABBED_TAG)) return;

        boolean stillHeld = false;
        for (Player player : entity.level().players()) {
            if (player instanceof SupersonicFlightPlayer core && core.getGrabbedTarget() == entity) {
                stillHeld = true;
                break;
            }
        }
        if (stillHeld) return;

        entity.removeTag(GrabPunchManager.GRABBED_TAG);
        if (entity instanceof Mob mob) {
            mob.setNoAi(false);
        }
        entity.hasImpulse = true;
    }
}
