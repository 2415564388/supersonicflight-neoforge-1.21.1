package com.baranhan123.supersonicflight.client.mixin;

import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {

    /** Extreme FOV widening during SONIC — uses the vanilla FOV modifier path */
    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void onGetFovModifier(CallbackInfoReturnable<Float> cir) {
        if (!SupersonicConfigClient.INSTANCE.enableFovEffect) return;

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        if (!(self instanceof SupersonicFlightPlayer flightPlayer)) return;

        if (flightPlayer.getFlightState() != FlightState.SONIC) return;

        cir.setReturnValue(SupersonicConfigClient.INSTANCE.fovMultiplier);
    }
}
