package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.SupersonicFlightClient;
import com.baranhan123.supersonicflight.config.SupersonicFlightCameraConfig;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public class FlightCameraMixin {

    @Unique private float smoothedCameraRoll = 0.0f;
    @Unique private float lastCameraYaw = 0.0f;
    @Unique private long lastRenderTimeMs = 0;

    @Inject(method = "setup", at = @At("TAIL"))
    private void applyFlightCameraRoll(net.minecraft.world.level.BlockGetter level,
                                        net.minecraft.world.entity.Entity entity,
                                        boolean detached, boolean thirdPersonReverse, float partialTick,
                                        CallbackInfo ci) {
        if (!SupersonicFlightCameraConfig.INSTANCE.cameraRoll) return;
        if (!(entity instanceof Player player)) return;
        if (!(player instanceof SupersonicFlightPlayer flightPlayer)) return;

        FlightState state = flightPlayer.getFlightState();
        if (state == FlightState.NONE) {
            smoothedCameraRoll *= 0.9f;
            SupersonicFlightClient.currentCameraRoll = smoothedCameraRoll;
            return;
        }

        long now = System.currentTimeMillis();
        if (lastRenderTimeMs == 0) lastRenderTimeMs = now;
        float deltaTime = (now - lastRenderTimeMs) / 1000.0f;
        lastRenderTimeMs = now;

        // Calculate turn speed
        float currentYaw = player.getYRot();
        float turnSpeed = (currentYaw - lastCameraYaw) / Math.max(deltaTime, 0.001f);
        lastCameraYaw = currentYaw;

        var config = SupersonicFlightCameraConfig.INSTANCE;
        float targetRoll = -turnSpeed * config.cameraRollMultiplier;
        targetRoll = Math.max(-config.maxCameraRoll, Math.min(config.maxCameraRoll, targetRoll));

        float smoothFactor = 1.0f - (float) Math.exp(-deltaTime * config.cameraRollRoughness);
        smoothedCameraRoll += (targetRoll - smoothedCameraRoll) * smoothFactor;

        SupersonicFlightClient.currentCameraRoll = smoothedCameraRoll;
    }
}
