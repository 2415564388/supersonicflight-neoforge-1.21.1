package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.client.SupersonicFlightClient;
import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** Smoothed 0..1 FOV boost factor, ramps up on SONIC/LAUNCH and decays on exit */
    @Unique private float smoothedFovBoost = 0.0f;

    /** Apply camera roll (Z-axis rotation) to the PoseStack during hurt-bob phase */
    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void applyCameraRoll(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        float roll = SupersonicFlightClient.currentCameraRoll;
        if (Math.abs(roll) > 0.001f) {
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
        }
    }

    /**
     * High-speed flight FOV widening, consistent at any window size / aspect ratio.
     *
     * Vanilla 1.21.1 clamps the {@code getFieldOfViewModifier} path to 1.5x. Widening the
     * HORIZONTAL FOV instead makes the vertical FOV depend on the window aspect, so resizing the
     * window while flying makes the view jump. Instead we apply a fixed multiplier to the
     * VERTICAL FOV (same value at every window size), then only clamp the resulting horizontal
     * FOV so ultra-wide windows never degrade into fish-eye. We only touch
     * {@code useFovSetting == true} (the world render) so the first-person held item keeps its
     * stable vanilla FOV.
     */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(Camera camera, float partialTick, boolean useFovSetting,
                          CallbackInfoReturnable<Double> cir) {
        if (!useFovSetting) return;
        if (!SupersonicConfigClient.INSTANCE.enableFovEffect) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(mc.player instanceof SupersonicFlightPlayer flightPlayer)) return;

        // Target boost per state: SONIC full, LAUNCH half, everything else (HOVER/NONE) settles.
        FlightState state = flightPlayer.getFlightState();
        float target = switch (state) {
            case SONIC -> 1.0f;
            case LAUNCH -> 0.5f;
            default -> 0.0f;
        };

        float smooth = Mth.clamp(SupersonicConfigClient.INSTANCE.fovSmooth, 0.02f, 1.0f);
        smoothedFovBoost += (target - smoothedFovBoost) * smooth;

        if (smoothedFovBoost < 0.001f) {
            smoothedFovBoost = 0.0f;
            return; // fully settled back to vanilla, no override needed
        }

        // Vertical FOV multiplier, clamped for stale configs that carry the old absolute 11.0.
        float verticalMultiplier = Mth.clamp(SupersonicConfigClient.INSTANCE.fovMultiplier, 1.0f, 2.0f);

        double baseVerticalFov = cir.getReturnValue(); // degrees (vertical)
        double aspect = (double) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        if (aspect < 0.05) aspect = 0.05; // guard against degenerate window size

        double fov = baseVerticalFov * (1.0 + smoothedFovBoost * (verticalMultiplier - 1.0));

        // Safety cap on the resulting horizontal FOV so ultra-wide windows don't fish-eye/invert.
        double maxHorizontal = Math.max(SupersonicConfigClient.INSTANCE.fovMaxHorizontal, 30.0);
        double horizontalFov = Math.toDegrees(2.0 * Math.atan(
                Math.tan(Math.toRadians(fov) / 2.0) * aspect));
        if (horizontalFov > maxHorizontal) {
            fov = Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(maxHorizontal) / 2.0) / aspect));
        }

        cir.setReturnValue(fov);
    }
}
