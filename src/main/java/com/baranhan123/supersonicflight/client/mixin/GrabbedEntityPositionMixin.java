package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.SupersonicFlightClient;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.GrabPunchManager;
import com.baranhan123.supersonicflight.util.GrabbedEntityIndex;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a held entity at the player's hand instead of at its (server-pinned) entity position.
 *
 * <p>The server does place the entity correctly, but that position is interpolated and network-delayed,
 * so at flight speed it renders behind the hand. This override runs after the dispatcher has already
 * translated to the entity's interpolated position and re-anchors it to the hand, which reads as
 * rock-solid.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class GrabbedEntityPositionMixin {

    @Inject(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private <E extends Entity> void onRenderAbsolutePositionFix(E entity, double x, double y, double z,
                                                                float entityYaw, float partialTicks,
                                                                PoseStack poseStack, MultiBufferSource buffer,
                                                                int packedLight, CallbackInfo ci) {
        if (entity.level() == null || !entity.level().isClientSide()) return;

        Player holdingPlayer = getHoldingPlayer(entity);
        if (holdingPlayer == null) return;

        Minecraft client = Minecraft.getInstance();
        boolean isFirstPerson = holdingPlayer == client.player
                && client.options.getCameraType().isFirstPerson();

        Vec3 handPos = isFirstPerson
                ? firstPersonHandPos(client, holdingPlayer, partialTicks)
                : thirdPersonHandPos(holdingPlayer, partialTicks);
        if (handPos == null) return;

        // In third person the victim is pushed out by its own half-width so the model does not
        // intersect the hand; in first person it sits exactly on the hand.
        Vec3 lookDir = holdingPlayer.getViewVector(partialTicks).normalize();
        float pushForce = isFirstPerson ? 0.0F : entity.getBbWidth() * 0.6F;
        Vec3 adjustedHandPos = handPos.add(lookDir.scale(pushForce));

        double renderX = Mth.lerp(partialTicks, entity.xo, entity.getX());
        double renderY = Mth.lerp(partialTicks, entity.yo, entity.getY());
        double renderZ = Mth.lerp(partialTicks, entity.zo, entity.getZ());
        poseStack.translate(adjustedHandPos.x - renderX, adjustedHandPos.y - renderY, adjustedHandPos.z - renderZ);

        float playerYaw = Mth.lerp(partialTicks, holdingPlayer.yRotO, holdingPlayer.getYRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(playerYaw));
        float playerPitch = Mth.lerp(partialTicks, holdingPlayer.xRotO, holdingPlayer.getXRot());
        poseStack.mulPose(Axis.XP.rotationDegrees(playerPitch));
        if (holdingPlayer instanceof SupersonicFlightPlayer flightPlayer && !isFirstPerson) {
            float throttle = flightPlayer.getLerpedFlightThrottle(partialTicks);
            poseStack.mulPose(Axis.XP.rotationDegrees(throttle * 40.0F));
        }

        if (isFirstPerson) {
            poseStack.scale(1.6F, 1.6F, 1.6F);
        }

        // Drop the model so its body centre sits at the hand. This is the ONLY place the drop is
        // applied — ViltrumiteCore also applied it again in the renderer's setupRotations hook,
        // which stacked to roughly two body-height's worth of sinking.
        poseStack.translate(0.0, -GrabPunchManager.holdDrop(entity), 0.0);

        // Face the victim back at the player and freeze it mid-pose.
        entity.setYRot(180.0F);
        entity.yRotO = 180.0F;
        entity.setXRot(0.0F);
        entity.xRotO = 0.0F;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = 180.0F;
            living.yBodyRotO = 180.0F;
            living.yHeadRot = 180.0F;
            living.yHeadRotO = 180.0F;
            if (living.walkAnimation != null) {
                living.walkAnimation.setSpeed(0.0F);
            }
            // Wardens drive their limbs from animation states, not walkAnimation, so those have
            // to be stopped explicitly or the arms keep swinging.
            if (living instanceof Warden warden) {
                warden.sonicBoomAnimationState.stop();
                warden.roarAnimationState.stop();
                warden.attackAnimationState.stop();
                warden.sniffAnimationState.stop();
                warden.emergeAnimationState.stop();
                warden.diggingAnimationState.stop();
            }
        }
    }

    /**
     * First person: the hand lives in camera space, so it has to be scaled by the same FOV the
     * flight effect is applying and then rotated out of camera space into the world.
     */
    private static Vec3 firstPersonHandPos(Minecraft client, Player holdingPlayer, float partialTicks) {
        Vec3 localHandPos = ((SupersonicFlightPlayer) holdingPlayer).getFirstPersonLocalHandPos();
        if (localHandPos == null) return null;

        Camera camera = client.gameRenderer.getMainCamera();
        float baseFov = client.options.fov().get();
        float totalFov = baseFov * SupersonicFlightClient.currentFovMultiplier;
        float fovDifference = totalFov - 70.0F;
        float xyScale = 1.0F + fovDifference * 0.0035F;
        float zFovOffset = fovDifference * 0.015F;

        float adjustedX = (float) localHandPos.x() * xyScale;
        float adjustedY = (float) localHandPos.y() * xyScale;
        float adjustedZ = (float) localHandPos.z() + zFovOffset;
        if (holdingPlayer instanceof SupersonicFlightPlayer fp && fp.getFlightState() != FlightState.NONE) {
            adjustedZ += 0.15F;
        }

        Vector3f rotator = new Vector3f(adjustedX, adjustedY, adjustedZ);
        rotator.rotateZ((float) Math.toRadians(-SupersonicFlightClient.currentCameraRoll));
        rotator.rotateX((float) Math.toRadians(-camera.getXRot()));
        rotator.rotateY((float) Math.toRadians(-(camera.getYRot() + 180.0F)));
        return camera.getPosition().add(rotator.x(), rotator.y(), rotator.z());
    }

    /** Third person: the offset is player-relative, so it survives high-speed flight without lag. */
    private static Vec3 thirdPersonHandPos(Player holdingPlayer, float partialTicks) {
        SupersonicFlightPlayer core = (SupersonicFlightPlayer) holdingPlayer;
        Vec3 handOffset = core.getCalculatedHandOffset();
        if (handOffset != null) {
            double lerpX = Mth.lerp(partialTicks, holdingPlayer.xo, holdingPlayer.getX());
            double lerpY = Mth.lerp(partialTicks, holdingPlayer.yo, holdingPlayer.getY());
            double lerpZ = Mth.lerp(partialTicks, holdingPlayer.zo, holdingPlayer.getZ());
            return new Vec3(lerpX, lerpY, lerpZ).add(handOffset);
        }
        return core.getCalculatedHandPos();
    }

    private static Player getHoldingPlayer(Entity entity) {
        // Gate first: this runs for every rendered entity, every frame, and the answer is "nobody"
        // almost always. The index makes that case a single hash lookup instead of a player scan.
        if (!GrabbedEntityIndex.contains(entity)) return null;

        for (Player player : entity.level().players()) {
            if (player instanceof SupersonicFlightPlayer core && core.getGrabbedTarget() == entity) {
                return player;
            }
        }
        return null;
    }
}
