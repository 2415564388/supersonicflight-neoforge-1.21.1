package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.util.GrabbedEntityIndex;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a held entity read as "lifted off the ground" rather than "standing in mid-air".
 *
 * <p>Three pieces: face the victim back at the player and freeze its walk animation for the frame,
 * tilt it with the player's pitch, and stop mobs from cycling their limb-swing animation while
 * held. Players keep their own limb animation because their pose mixins already handle it.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class GrabbedEntityRendererMixin<T extends LivingEntity> {

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void onRenderAnimationFix(T entity, float entityYaw, float partialTicks, PoseStack matrices,
                                      MultiBufferSource vertexConsumers, int light, CallbackInfo ci) {
        if (entity.level() == null || !entity.level().isClientSide()) return;

        Player holdingPlayer = getHoldingPlayer(entity);
        if (holdingPlayer == null) return;

        float playerLerpedYaw = Mth.lerp(partialTicks, holdingPlayer.yRotO, holdingPlayer.getYRot());
        float targetYaw = playerLerpedYaw + 180.0F;
        entity.setYRot(targetYaw);
        entity.yRotO = targetYaw;
        entity.yBodyRot = targetYaw;
        entity.yBodyRotO = targetYaw;
        entity.yHeadRot = targetYaw;
        entity.yHeadRotO = targetYaw;
        if (entity.walkAnimation != null) {
            entity.walkAnimation.setSpeed(0.0F);
        }

        if (entity instanceof Warden warden) {
            warden.sonicBoomAnimationState.stop();
            warden.roarAnimationState.stop();
            warden.attackAnimationState.stop();
            warden.sniffAnimationState.stop();
            warden.emergeAnimationState.stop();
            warden.diggingAnimationState.stop();
        }
    }

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void onSetupTransforms(T entity, PoseStack matrices, float ageInTicks, float rotationYaw,
                                   float partialTicks, float scale, CallbackInfo ci) {
        if (entity.level() == null || !entity.level().isClientSide()) return;

        Player holdingPlayer = getHoldingPlayer(entity);
        if (holdingPlayer == null) return;

        Minecraft client = Minecraft.getInstance();
        boolean isFirstPerson = holdingPlayer == client.player
                && client.options.getCameraType().isFirstPerson();

        if (holdingPlayer instanceof SupersonicFlightPlayer flightPlayer && !isFirstPerson) {
            float throttle = flightPlayer.getLerpedFlightThrottle(partialTicks);
            matrices.mulPose(Axis.XP.rotationDegrees(throttle * 40.0F));
        }

        float playerPitch = Mth.lerp(partialTicks, holdingPlayer.xRotO, holdingPlayer.getXRot());
        matrices.mulPose(Axis.XP.rotationDegrees(playerPitch));

        if (isFirstPerson) {
            matrices.scale(1.4F, 1.4F, 1.4F);
        }

        // No downward drop here on purpose: GrabbedEntityPositionMixin already positions the model
        // relative to the hand. ViltrumiteCore applied the drop in both places, which stacked.
    }

    /**
     * Freezes mob limb swing while held. An entity carried through the air still accumulates limb
     * swing from the movement it is being dragged through, which looks like it is sprinting in
     * place. Implemented with {@code @WrapOperation} rather than the reference's {@code @Redirect}
     * so it composes with other mods that also touch this call site.
     */
    @WrapOperation(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"))
    private void stopLimbFlailing(EntityModel<?> model, Entity entity, float limbSwing, float limbSwingAmount,
                                  float ageInTicks, float netHeadYaw, float headPitch, Operation<Void> original) {
        if (!(entity instanceof LivingEntity living) || getHoldingPlayer(living) == null) {
            original.call(model, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            return;
        }

        if (entity instanceof Player) {
            original.call(model, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        } else {
            original.call(model, entity, 0.0F, 0.0F, ageInTicks, netHeadYaw, headPitch);
        }
    }

    private static Player getHoldingPlayer(LivingEntity entity) {
        // Gate first: called twice per rendered living entity per frame (render HEAD and the
        // setupAnim wrapper), so the common "nobody is holding this" case must be O(1).
        if (!GrabbedEntityIndex.contains(entity)) return null;

        for (Player player : entity.level().players()) {
            if (player instanceof SupersonicFlightPlayer core && core.getGrabbedTarget() == entity) {
                return player;
            }
        }
        return null;
    }
}
