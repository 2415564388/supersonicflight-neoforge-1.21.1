package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whole-body lunge while throwing a punch in flight.
 *
 * <p>Unlike the model pose (which bends limbs), this rotates the entire player entity in the world,
 * so the punch carries the body forward. Only applied while flying — on the ground the model pose
 * alone reads correctly. Ported from ViltrumiteCore's {@code PunchRendererCoreMixin}.
 */
@Mixin(value = PlayerRenderer.class, priority = 1500)
public class PunchRendererCoreMixin {

    @Inject(
            method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At("TAIL"))
    private void onSetupPunchTransforms(AbstractClientPlayer player, PoseStack poseStack, float ageInTicks,
                                        float rotationYaw, float partialTicks, float scale, CallbackInfo ci) {
        if (!(player instanceof SupersonicFlightPlayer core)) return;

        int punchTicks = core.getPunchTicks();
        if (punchTicks <= 0) return;
        if (core.getFlightState() == FlightState.NONE) return;

        float time = (20.0F - (punchTicks - partialTicks)) / 20.0F;
        time = Mth.clamp(time, 0.0F, 1.0F);

        // Wind up backwards, snap forward, hold, then relax.
        float lungeMultiplier;
        if (time < 0.25F) {
            lungeMultiplier = Mth.lerp(time / 0.25F, 0.0F, -0.3F);
        } else if (time < 0.35F) {
            lungeMultiplier = Mth.lerp((time - 0.25F) / 0.1F, -0.3F, 1.0F);
        } else if (time < 0.65F) {
            lungeMultiplier = 1.0F;
        } else if (time < 0.85F) {
            lungeMultiplier = Mth.lerp((time - 0.65F) / 0.2F, 1.0F, 0.0F);
        } else {
            lungeMultiplier = 0.0F;
        }

        float mirror = core.isLeftArmPunch() ? -1.0F : 1.0F;
        float lookPitch = player.getViewXRot(partialTicks);
        float pivotY = 0.6F;

        // Pivot around the chest rather than the feet so the lean looks driven from the core.
        poseStack.translate(0.0F, pivotY, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-lookPitch * 0.5F * Math.abs(lungeMultiplier)));
        poseStack.mulPose(Axis.YP.rotationDegrees(8.0F * lungeMultiplier * mirror));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-6.0F * lungeMultiplier * mirror));
        poseStack.translate(0.0F, -pivotY, 0.0F);

        // Drive the body along the look direction, kept level so a steep dive does not bury the head.
        float pitchOffset = Mth.sin(lookPitch * (float) (Math.PI / 180.0));
        float moveZ = (-0.2F + pitchOffset * 0.7F) * lungeMultiplier;
        float moveX = 0.05F * lungeMultiplier * mirror;
        poseStack.translate(moveX, 0.0F, moveZ);
    }
}
