package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.GrabAnimationManager;
import com.baranhan123.supersonicflight.client.ShaderCompat;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person "force choke" arm pose while holding an entity.
 *
 * <p>The raised left arm is built by copying the head's own rotation and adding a small offset, so
 * the arm tracks where the player looks — the victim is always held out in front of the face.
 * Ported verbatim from ViltrumiteCore's {@code GrabModelMixin}.
 */
@Mixin(value = PlayerModel.class, priority = 2000)
public abstract class GrabModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    public GrabModelMixin(ModelPart root) {
        super(root);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void applyGrabPose(T livingEntity, float limbSwing, float limbSwingAmount, float ageInTicks,
                               float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(livingEntity instanceof SupersonicFlightPlayer core)) return;

        // A punch keyframes both arms — the punching one and the idle one — so it collides with the
        // grab pose, which also drives the left arm. During the punch wind-up the victim is still
        // held, so without this both poses apply at once and the arm visibly jitters.
        if (core.getPunchTicks() > 0) return;

        boolean isGrabbing = core.getGrabbedTarget() != null || core.isTryingToGrab();
        Minecraft client = Minecraft.getInstance();
        boolean isLocalFirstPerson = livingEntity == client.player
                && client.options.getCameraType().isFirstPerson();
        // The local first-person body is hidden anyway; bow out early unless a shader pack is
        // rendering our shadow, which does need the correct pose.
        if (isLocalFirstPerson && !ShaderCompat.isShadowPass()) return;

        float weight = GrabAnimationManager.calculateWeight(livingEntity, isGrabbing);
        if (weight < 0.001F && !isGrabbing) return;

        float targetPitch = -1.8453F + this.head.xRot;
        float targetYaw = this.head.yRot + 0.35F;

        this.leftArm.xRot = Mth.lerp(weight, this.leftArm.xRot, targetPitch);
        this.leftArm.yRot = Mth.lerp(weight, this.leftArm.yRot, targetYaw);
        this.leftArm.zRot = Mth.lerp(weight, this.leftArm.zRot, 0.0F);

        PlayerModel<T> model = (PlayerModel<T>) (Object) this;
        model.leftSleeve.copyFrom(this.leftArm);
    }
}
