package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.GrabAnimationManager;
import com.baranhan123.supersonicflight.network.packet.HandPosSyncPayload;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person left arm raised in the "force choke" grab pose, and the source of the hand-position
 * sync that pins the held entity to that arm.
 *
 * <p>Keyframes ported verbatim from ViltrumiteCore. The pose blends in via
 * {@link GrabAnimationManager} so releasing a mob eases the arm back down.
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1200)
public abstract class FirstPersonGrabMixin {

    @Unique private static final float GRAB_X = 1.14F;
    @Unique private static final float GRAB_Y = 0.22F;
    @Unique private static final float GRAB_Z = -0.49F;
    @Unique private static final float GRAB_RX = 10.19F;
    @Unique private static final float GRAB_RY = 38.98F;
    @Unique private static final float GRAB_RZ = -58.58F;

    @Inject(
            method = "renderArmWithItem",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
                    shift = At.Shift.AFTER))
    private void onRenderFirstPersonGrab(AbstractClientPlayer player, float partialTicks, float pitch,
                                         InteractionHand hand, float attackAnim, ItemStack stack,
                                         float equipAnim, PoseStack poseStack, MultiBufferSource buffer,
                                         int combinedLight, CallbackInfo ci) {
        if (!(player instanceof SupersonicFlightPlayer core)) return;

        // See GrabModelMixin: the punch owns the arm while it plays, and the grab pose would fight
        // it during the wind-up (the victim is still held until the impact tick).
        if (core.getPunchTicks() > 0) return;

        boolean isGrabbing = core.getGrabbedTarget() != null || core.isTryingToGrab();
        float weight = GrabAnimationManager.calculateWeight(player, isGrabbing);
        if (weight < 0.001F && !isGrabbing) return;

        HumanoidArm currentArm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        if (currentArm != HumanoidArm.LEFT) return;

        float m = -1.0F;
        float x = Mth.lerp(weight, 0.0F, GRAB_X * m);
        float y = Mth.lerp(weight, 0.0F, GRAB_Y);
        float z = Mth.lerp(weight, 0.0F, GRAB_Z);
        float rx = Mth.lerp(weight, 0.0F, GRAB_RX);
        float ry = Mth.lerp(weight, 0.0F, GRAB_RY * m);
        float rz = Mth.lerp(weight, 0.0F, GRAB_RZ * m);

        poseStack.translate(x, y, z);
        poseStack.mulPose(new Quaternionf().rotateX((float) Math.toRadians(rx)));
        poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(ry)));
        poseStack.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(rz)));

        Minecraft client = Minecraft.getInstance();
        if (player != client.player || !client.options.getCameraType().isFirstPerson()) return;

        // Hand position in camera space, read straight out of the pose we just applied.
        poseStack.pushPose();
        poseStack.translate(-1.5, 0.0, -1.5);
        Vector3f localPos = poseStack.last().pose().getTranslation(new Vector3f());
        poseStack.popPose();

        core.setFirstPersonLocalHandPos(new Vec3(localPos.x(), localPos.y(), localPos.z()));

        Camera camera = client.gameRenderer.getMainCamera();
        Vector3f worldCalc = new Vector3f(localPos);
        worldCalc.rotateX((float) Math.toRadians(-camera.getXRot()));
        worldCalc.rotateY((float) Math.toRadians(-(camera.getYRot() + 180.0F)));
        Vec3 worldPos = camera.getPosition().add(worldCalc.x(), worldCalc.y(), worldCalc.z());
        core.setCalculatedHandPos(worldPos);

        // The server only needs the offset: at SONIC speed an absolute position would arrive stale
        // and the held mob would lag behind the hand.
        Vec3 offset = worldPos.subtract(player.position());
        core.setCalculatedHandOffset(offset);
        if (isGrabbing) {
            PacketDistributor.sendToServer(new HandPosSyncPayload(offset.x(), offset.y(), offset.z()));
        }
    }
}
