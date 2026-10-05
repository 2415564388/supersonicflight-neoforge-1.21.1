package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.client.gui.SupersonicConfigScreen;
import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.config.SupersonicFlightCameraConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
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
            registerConfigScreen();
        });

        // Register client tick handler
        NeoForge.EVENT_BUS.register(FlightInputHandler.class);
    }

    /**
     * Offers the Cloth Config screen through the mod list's Config button.
     *
     * <p>NeoForge resolves a mod's config screen itself, via
     * {@code IConfigScreenFactory.getForMod}, so registering the extension point is all that is
     * needed — no ModMenu.
     *
     * <p>Guarded on Cloth Config actually being loaded. That is also what keeps
     * {@link SupersonicConfigScreen} — and the Cloth classes it references — from ever being resolved
     * when the library is absent: without the guard the mod would still start, but linking would fail
     * the moment NeoForge asked for a screen.
     */
    private static void registerConfigScreen() {
        if (!ModList.get().isLoaded("cloth_config")) return;

        ModList.get().getModContainerById(SupersonicFlight.MOD_ID).ifPresent(container ->
                container.registerExtensionPoint(IConfigScreenFactory.class,
                        (modContainer, parent) -> SupersonicConfigScreen.create(parent)));
    }
}
