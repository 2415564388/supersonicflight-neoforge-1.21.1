package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void onRenderFirstPersonItem(AbstractClientPlayer player, float partialTick, float pitch,
                                          InteractionHand hand, float swingProgress, ItemStack stack,
                                          float equippedProgress, PoseStack poseStack,
                                          MultiBufferSource bufferSource, int combinedLight,
                                          CallbackInfo ci) {
        if (!(player instanceof SupersonicFlightPlayer flightPlayer)) return;

        FlightState state = flightPlayer.getFlightState();
        if (state == FlightState.NONE) return;

        float throttle = flightPlayer.getLerpedFlightThrottle(partialTick);
        if (throttle > 0.3f && hand == InteractionHand.MAIN_HAND) {
            poseStack.translate(0, -0.3 * throttle, -0.5 * throttle);
        }
    }
}
