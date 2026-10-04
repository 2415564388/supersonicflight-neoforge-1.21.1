package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.ShaderCompat;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person full-body punch pose.
 *
 * <p>Every limb is keyframed: the punch arm winds back (S1) then drives forward past its target
 * with a 1.15× overshoot (S2) before settling, while the idle arm, legs, torso and cape follow
 * along so the whole body sells the swing. Constants ported verbatim from ViltrumiteCore's
 * {@code PunchModelMixin}; the timeline is normalised against the 20-tick punch.
 */
@Mixin(value = PlayerModel.class, priority = 1150)
public abstract class PunchModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    /** PlayerModel's cape; private, so it has to be shadowed. */
    @Shadow
    @Final
    private ModelPart cloak;

    public PunchModelMixin(ModelPart root) {
        super(root);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void onSetAngles(T livingEntity, float limbSwing, float limbSwingAmount, float ageInTicks,
                             float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(livingEntity instanceof SupersonicFlightPlayer core)) return;

        int punchTicks = core.getPunchTicks();
        if (punchTicks <= 0) return;

        Minecraft client = Minecraft.getInstance();
        boolean isLocalFirstPerson = livingEntity == client.player
                && client.options.getCameraType().isFirstPerson();
        if (isLocalFirstPerson && !ShaderCompat.isShadowPass()) return;

        float partialTick = client.getTimer().getGameTimeDeltaPartialTick(false);
        float time = (20.0F - (punchTicks - partialTick)) / 20.0F;
        time = Mth.clamp(time, 0.0F, 1.0F);

        boolean sneaking = livingEntity.isCrouching();
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        boolean isLeft = core.isLeftArmPunch();
        float m = isLeft ? -1.0F : 1.0F;
        float armBaseY = sneaking ? 5.2F : 2.0F;
        float bodyBaseY = sneaking ? 3.2F : 0.0F;
        float headBaseY = sneaking ? 4.2F : 0.0F;

        // The head keeps its vanilla rotation; the arms borrow from it below.
        this.head.zRot = 0.0F;
        this.body.yRot = 0.0F;
        this.body.zRot = 0.0F;

        applyPunchTransform(this.head, time,
                33.48F, 10.43F * m, 10.43F * m, -0.65F * m, 0.14F, -2.54F,
                0.0F, 0.0F, 0.0F, 2.1F * m, 1.67F, -4.2F,
                this.head.xRot, this.head.yRot, this.head.zRot,
                0.0F, headBaseY, 0.0F);

        applyPunchTransform(this.body, time,
                10.43F, 33.48F * m, 0.0F, -0.65F * m, 0.14F, -2.54F,
                23.48F, -23.48F * m, 0.0F, 1.3F * m, 1.67F, -4.2F,
                this.body.xRot, this.body.yRot, this.body.zRot,
                0.0F, bodyBaseY, 0.0F);

        applyPunchTransform(this.cloak, time,
                -50.87F, 27.39F * m, 13.04F * m, 0.0F, 0.0F, 3.33F,
                -75.65F, -31.36F * m, 5.22F * m, -1.45F * m, 1.3F, 4.34F,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F);

        ModelPart punchArm = isLeft ? this.leftArm : this.rightArm;
        ModelPart idleArm = isLeft ? this.rightArm : this.leftArm;
        float punchArmBaseX = isLeft ? 5.0F : -5.0F;
        float idleArmBaseX = isLeft ? -5.0F : 5.0F;

        applyPunchTransform(punchArm, time,
                -45.65F, -14.35F * m, 37.83F * m, -0.65F * m, 0.14F, -2.54F,
                -116.09F, 3.91F * m, 93.91F * m, 1.96F * m, 2.32F, -7.39F,
                punchArm.xRot, punchArm.yRot, punchArm.zRot,
                punchArmBaseX, armBaseY, 0.0F);

        applyPunchTransform(idleArm, time,
                -84.78F, 30.0F * m, 1.3F * m, -0.65F * m, 0.14F, -2.54F,
                36.52F, -11.74F * m, -7.83F * m, 2.1F * m, 1.67F, -1.96F,
                idleArm.xRot, idleArm.yRot, idleArm.zRot,
                idleArmBaseX, armBaseY, 0.0F);

        ModelPart punchLeg = isLeft ? this.leftLeg : this.rightLeg;
        ModelPart idleLeg = isLeft ? this.rightLeg : this.leftLeg;
        float punchLegBaseX = isLeft ? 1.9F : -1.9F;
        float idleLegBaseX = isLeft ? -1.9F : 1.9F;

        applyPunchTransform(punchLeg, time,
                0.0F, 26.09F * m, 0.0F, 0.39F * m, 0.14F, 0.94F,
                0.0F, -23.48F * m, 0.0F, 0.0F, 0.0F, -0.87F,
                punchLeg.xRot, punchLeg.yRot, punchLeg.zRot,
                punchLegBaseX, 12.0F, 0.0F);

        applyPunchTransform(idleLeg, time,
                0.0F, 26.09F * m, 0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, -23.48F * m, 0.0F, -0.14F * m, 0.0F, 0.87F,
                idleLeg.xRot, idleLeg.yRot, idleLeg.zRot,
                idleLegBaseX, 12.0F, 0.0F);

        // Keep the clothing layers glued to the limbs they cover.
        model.hat.copyFrom(this.head);
        model.jacket.copyFrom(this.body);
        model.rightSleeve.copyFrom(this.rightArm);
        model.leftSleeve.copyFrom(this.leftArm);
        model.rightPants.copyFrom(this.rightLeg);
        model.leftPants.copyFrom(this.leftLeg);
    }

    /**
     * Blends one model part through the punch timeline.
     *
     * <p>{@code s1*} is the wind-up pose, {@code s2*} the extended pose, and {@code van*} the
     * part's vanilla rotation to fall back to. Each is expressed relative to a per-part base
     * offset so the limbs stay attached at the shoulder/hip.
     */
    @Unique
    private void applyPunchTransform(ModelPart part, float time,
                                     float s1P, float s1Y, float s1R, float s1X, float s1YPos, float s1Z,
                                     float s2P, float s2Y, float s2R, float s2X, float s2YPos, float s2Z,
                                     float vanP, float vanY, float vanR,
                                     float baseX, float baseY, float baseZ) {
        float overMultiplier = 1.15F;
        float targetP, targetY, targetR, targetX, targetYPos, targetZ;

        if (time < 0.25F) {
            float t = time / 0.25F;
            targetP = Mth.lerp(t, vanP, (float) Math.toRadians(s1P));
            targetY = Mth.lerp(t, vanY, (float) Math.toRadians(s1Y));
            targetR = Mth.lerp(t, vanR, (float) Math.toRadians(s1R));
            targetX = Mth.lerp(t, baseX, baseX + s1X);
            targetYPos = Mth.lerp(t, baseY, baseY + s1YPos);
            targetZ = Mth.lerp(t, baseZ, baseZ + s1Z);
        } else if (time < 0.35F) {
            float t = (time - 0.25F) / 0.1F;
            targetP = Mth.lerp(t, (float) Math.toRadians(s1P), (float) Math.toRadians(s2P) * overMultiplier);
            targetY = Mth.lerp(t, (float) Math.toRadians(s1Y), (float) Math.toRadians(s2Y) * overMultiplier);
            targetR = Mth.lerp(t, (float) Math.toRadians(s1R), (float) Math.toRadians(s2R) * overMultiplier);
            targetX = Mth.lerp(t, baseX + s1X, baseX + s2X * overMultiplier);
            targetYPos = Mth.lerp(t, baseY + s1YPos, baseY + s2YPos * overMultiplier);
            targetZ = Mth.lerp(t, baseZ + s1Z, baseZ + s2Z * overMultiplier);
        } else if (time < 0.5F) {
            float t = (time - 0.35F) / 0.15F;
            targetP = Mth.lerp(t, (float) Math.toRadians(s2P) * overMultiplier, (float) Math.toRadians(s2P));
            targetY = Mth.lerp(t, (float) Math.toRadians(s2Y) * overMultiplier, (float) Math.toRadians(s2Y));
            targetR = Mth.lerp(t, (float) Math.toRadians(s2R) * overMultiplier, (float) Math.toRadians(s2R));
            targetX = Mth.lerp(t, baseX + s2X * overMultiplier, baseX + s2X);
            targetYPos = Mth.lerp(t, baseY + s2YPos * overMultiplier, baseY + s2YPos);
            targetZ = Mth.lerp(t, baseZ + s2Z * overMultiplier, baseZ + s2Z);
        } else if (time < 0.65F) {
            targetP = (float) Math.toRadians(s2P);
            targetY = (float) Math.toRadians(s2Y);
            targetR = (float) Math.toRadians(s2R);
            targetX = baseX + s2X;
            targetYPos = baseY + s2YPos;
            targetZ = baseZ + s2Z;
        } else if (time < 0.9F) {
            float t = (time - 0.65F) / 0.25F;
            targetP = Mth.lerp(t, (float) Math.toRadians(s2P), vanP);
            targetY = Mth.lerp(t, (float) Math.toRadians(s2Y), vanY);
            targetR = Mth.lerp(t, (float) Math.toRadians(s2R), vanR);
            targetX = Mth.lerp(t, baseX + s2X, baseX);
            targetYPos = Mth.lerp(t, baseY + s2YPos, baseY);
            targetZ = Mth.lerp(t, baseZ + s2Z, baseZ);
        } else {
            targetP = vanP;
            targetY = vanY;
            targetR = vanR;
            targetX = baseX;
            targetYPos = baseY;
            targetZ = baseZ;
        }

        part.xRot = targetP;
        part.yRot = targetY;
        part.zRot = targetR;
        part.x = targetX;
        part.y = targetYPos;
        part.z = targetZ;
    }
}
