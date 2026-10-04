package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.util.GrabPunchManager;
import com.baranhan123.supersonicflight.util.GrabbedEntityIndex;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Culling and nametag fixes for held entities.
 *
 * <p>The inherited culling box follows the entity's raw position, which under flight-speed latency
 * can sit outside the view even though the entity is drawn in front of the player — so a held mob
 * would flicker in and out. Nametags likewise need repositioning to sit above the held model, and
 * must be hidden entirely in first person where there is no visible body.
 */
@Mixin(EntityRenderer.class)
public abstract class GrabbedEntityRendererMixin2<T extends Entity> {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void forceRenderWhenGrabbed(T entity, Frustum frustum, double x, double y, double z,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (entity.level() == null || !entity.level().isClientSide()) return;
        if (!(entity instanceof LivingEntity)) return;

        Player holdingPlayer = getHoldingPlayer(entity);
        if (holdingPlayer == null) return;

        // Cull against the holder, not the entity, so the victim is drawn whenever its captor is.
        if (holdingPlayer != Minecraft.getInstance().player
                && !frustum.isVisible(holdingPlayer.getBoundingBox())) {
            cir.setReturnValue(false);
        } else {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void fixGrabbedNametagSpace(T entity, Component component, PoseStack poseStack,
                                        MultiBufferSource buffer, int packedLight, float partialTick,
                                        CallbackInfo ci) {
        if (entity.level() == null || !entity.level().isClientSide()) return;

        Player holdingPlayer = getHoldingPlayer(entity);
        if (holdingPlayer == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        boolean isFirstPerson = holdingPlayer == minecraft.player
                && minecraft.options.getCameraType().isFirstPerson();
        if (isFirstPerson) {
            ci.cancel();
            return;
        }

        // Cancel the model's hold drop exactly, then lift the label clear of the head. Must use the
        // same holdDrop the position mixin applied, or the nametag drifts with the mob's height.
        poseStack.translate(0.0, GrabPunchManager.holdDrop(entity), 0.0);
        if (holdingPlayer instanceof SupersonicFlightPlayer flightPlayer) {
            poseStack.mulPose(Axis.XP.rotationDegrees(flightPlayer.getLerpedFlightThrottle(partialTick) * 40.0F));
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(
                Mth.lerp(partialTick, holdingPlayer.xRotO, holdingPlayer.getXRot())));
        poseStack.mulPose(Axis.YP.rotationDegrees(
                Mth.lerp(partialTick, holdingPlayer.yRotO, holdingPlayer.getYRot())));
        poseStack.translate(0.0, -entity.getBbHeight() + 0.2, 0.0);
    }

    private static Player getHoldingPlayer(Entity entity) {
        // Gate first: shouldRender runs for every entity in the world, every frame.
        if (!GrabbedEntityIndex.contains(entity)) return null;

        for (Player player : entity.level().players()) {
            if (player instanceof SupersonicFlightPlayer core && core.getGrabbedTarget() == entity) {
                return player;
            }
        }
        return null;
    }
}
