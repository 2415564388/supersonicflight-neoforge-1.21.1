package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.SupersonicFlightClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** Apply camera roll (Z-axis rotation) to the PoseStack during hurt-bob phase */
    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void applyCameraRoll(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        float roll = SupersonicFlightClient.currentCameraRoll;
        if (Math.abs(roll) > 0.001f) {
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
        }
    }
}
