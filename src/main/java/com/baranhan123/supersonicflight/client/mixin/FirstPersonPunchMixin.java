package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person arm animation for the heavy punch.
 *
 * <p>Keyframes ported verbatim from ViltrumiteCore: the arm pulls back (S1), snaps forward past its
 * target (S2, with a 1.15× overshoot), holds, then eases back to the vanilla pose. The timeline is
 * normalised against the 20-tick punch so it stays in sync with the third-person pose and the
 * shockwave.
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1500)
public abstract class FirstPersonPunchMixin {

    @Unique private static final float S1_X = 0.43F;
    @Unique private static final float S1_Y = 0.3F;
    @Unique private static final float S1_Z = 0.24F;
    @Unique private static final float S1_RX = -10.52F;
    @Unique private static final float S1_RY = 14.02F;
    @Unique private static final float S1_RZ = -2.98F;
    @Unique private static final float S2_X = -0.31F;
    @Unique private static final float S2_Y = 0.27F;
    @Unique private static final float S2_Z = -0.4F;
    @Unique private static final float S2_RX = -38.16F;
    @Unique private static final float S2_RY = 13.25F;
    @Unique private static final float S2_RZ = 31.7F;

    @Inject(
            method = "renderArmWithItem",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
                    shift = At.Shift.AFTER))
    private void onRenderFirstPersonPunch(AbstractClientPlayer player, float partialTicks, float pitch,
                                          InteractionHand hand, float swingProgress, ItemStack stack,
                                          float equipProgress, PoseStack poseStack, MultiBufferSource buffer,
                                          int combinedLight, CallbackInfo ci) {
        if (!(player instanceof SupersonicFlightPlayer core)) return;

        int punchTicks = core.getPunchTicks();
        if (punchTicks <= 0) return;

        boolean isLeftPunch = core.isLeftArmPunch();
        HumanoidArm currentArm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        if (isLeftPunch != (currentArm == HumanoidArm.LEFT)) return;

        float time = (20.0F - (punchTicks - partialTicks)) / 20.0F;
        time = Mth.clamp(time, 0.0F, 1.0F);

        float m = isLeftPunch ? -1.0F : 1.0F;
        float targetX = 0.0F, targetY = 0.0F, targetZ = 0.0F;
        float targetRX = 0.0F, targetRY = 0.0F, targetRZ = 0.0F;
        float overMultiplier = 1.15F;

        if (time < 0.2F) {
            float t = time / 0.2F;
            t *= t; // ease-in: slow anticipation, then commit
            targetX = Mth.lerp(t, 0.0F, S1_X);
            targetY = Mth.lerp(t, 0.0F, S1_Y);
            targetZ = Mth.lerp(t, 0.0F, S1_Z);
            targetRX = Mth.lerp(t, 0.0F, S1_RX);
            targetRY = Mth.lerp(t, 0.0F, S1_RY);
            targetRZ = Mth.lerp(t, 0.0F, S1_RZ);
        } else if (time < 0.25F) {
            float t = (time - 0.2F) / 0.05F;
            targetX = Mth.lerp(t, S1_X, S2_X * overMultiplier);
            targetY = Mth.lerp(t, S1_Y, S2_Y * overMultiplier);
            targetZ = Mth.lerp(t, S1_Z, S2_Z * overMultiplier);
            targetRX = Mth.lerp(t, S1_RX, S2_RX * overMultiplier);
            targetRY = Mth.lerp(t, S1_RY, S2_RY * overMultiplier);
            targetRZ = Mth.lerp(t, S1_RZ, S2_RZ * overMultiplier);
        } else if (time < 0.32F) {
            float t = (time - 0.25F) / 0.07F;
            targetX = Mth.lerp(t, S2_X * overMultiplier, S2_X);
            targetY = Mth.lerp(t, S2_Y * overMultiplier, S2_Y);
            targetZ = Mth.lerp(t, S2_Z * overMultiplier, S2_Z);
            targetRX = Mth.lerp(t, S2_RX * overMultiplier, S2_RX);
            targetRY = Mth.lerp(t, S2_RY * overMultiplier, S2_RY);
            targetRZ = Mth.lerp(t, S2_RZ * overMultiplier, S2_RZ);
        } else if (time < 0.65F) {
            targetX = S2_X;
            targetY = S2_Y;
            targetZ = S2_Z;
            targetRX = S2_RX;
            targetRY = S2_RY;
            targetRZ = S2_RZ;
        } else if (time < 0.9F) {
            float t = (time - 0.65F) / 0.25F;
            targetX = Mth.lerp(t, S2_X, 0.0F);
            targetY = Mth.lerp(t, S2_Y, 0.0F);
            targetZ = Mth.lerp(t, S2_Z, 0.0F);
            targetRX = Mth.lerp(t, S2_RX, 0.0F);
            targetRY = Mth.lerp(t, S2_RY, 0.0F);
            targetRZ = Mth.lerp(t, S2_RZ, 0.0F);
        }

        poseStack.translate(targetX * m, targetY, targetZ);
        poseStack.mulPose(new Quaternionf().rotateX((float) Math.toRadians(targetRX)));
        poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(targetRY * m)));
        poseStack.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(targetRZ * m)));
    }
}
