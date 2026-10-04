package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.ShaderCompat;
import com.baranhan123.supersonicflight.client.SupersonicFlightClient;
import com.baranhan123.supersonicflight.network.packet.HandPosSyncPayload;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person source of the hand-position sync.
 *
 * <p>Reads the left arm's world transform straight out of the model matrix just before the model is
 * submitted, so the server pins the held entity to exactly where the arm is actually drawn.
 *
 * <p>Deliberately limited to the local player. The reference version sent a sync for whichever
 * player entity happened to be rendering, which meant another player's grab could overwrite your
 * own hand position on the server. Remote players' victims simply fall back to the server-side
 * hand position, which is where the entity is anyway.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class HandPositionTrackerMixin<T extends LivingEntity, M extends EntityModel<T>> {

    /** Shoulder height in model space (7.5 / 16). */
    private static final float SHOULDER_OFFSET = 0.46875F;

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void extractHandPosition(T livingEntity, float entityYaw, float partialTicks, PoseStack poseStack,
                                     MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        // `screen != null` means this render is for a GUI (inventory player preview), where the
        // model transform has nothing to do with the world camera.
        if (client.screen != null) return;
        if (livingEntity != client.player) return;
        if (!(livingEntity instanceof SupersonicFlightPlayer core)) return;
        if (!(core.isTryingToGrab() || core.getGrabbedTarget() != null)) return;
        if (ShaderCompat.isShadowPass()) return;
        if (!(((LivingEntityRenderer<?, ?>) (Object) this).getModel() instanceof HumanoidModel<?> model)) return;

        // Walk the arm bone to find where the hand actually ended up, in world-ish space.
        poseStack.pushPose();
        model.leftArm.translateAndRotate(poseStack);
        poseStack.translate(0.0F, SHOULDER_OFFSET, 0.0F);
        Vector3f localPos = poseStack.last().pose().getTranslation(new Vector3f());
        Vector3f localDir = new Vector3f(0.0F, -1.0F, 0.0F);
        poseStack.last().normal().transform(localDir);
        poseStack.popPose();

        Camera camera = client.gameRenderer.getMainCamera();
        float invRoll = (float) Math.toRadians(-SupersonicFlightClient.currentCameraRoll);
        float invPitch = (float) Math.toRadians(-camera.getXRot());
        float invYaw = (float) Math.toRadians(-(camera.getYRot() + 180.0F));
        localPos.rotateZ(invRoll).rotateX(invPitch).rotateY(invYaw);

        Vec3 worldPos = camera.getPosition().add(localPos.x(), localPos.y(), localPos.z());
        Vec3 bodyPos = new Vec3(
                Mth.lerp(partialTicks, livingEntity.xo, livingEntity.getX()),
                Mth.lerp(partialTicks, livingEntity.yo, livingEntity.getY()),
                Mth.lerp(partialTicks, livingEntity.zo, livingEntity.getZ()));
        Vec3 handOffset = worldPos.subtract(bodyPos);

        core.setCalculatedHandPos(worldPos);
        core.setCalculatedHandOffset(handOffset);

        // Send the offset, not the world position: at flight speed an absolute position is stale
        // on arrival and the held entity visibly trails the hand.
        PacketDistributor.sendToServer(new HandPosSyncPayload(handOffset.x(), handOffset.y(), handOffset.z()));
    }
}
