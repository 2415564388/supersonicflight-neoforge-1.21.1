package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.config.SupersonicFlightCameraConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SupersonicFlightClient {

    public static float currentCameraRoll = 0.0f;

    /**
     * Vertical-FOV multiplier the flight FOV effect is currently applying (1.0 = vanilla).
     * Written by {@code GameRendererMixin} and read by the grab renderer, which has to scale its
     * first-person hand placement by the same factor or the held entity drifts off the hand.
     */
    public static float currentFovMultiplier = 1.0f;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            SupersonicConfigClient.load();
            SupersonicFlightCameraConfig.load();
        });

        // Register client tick handler
        NeoForge.EVENT_BUS.register(FlightInputHandler.class);
    }
}
