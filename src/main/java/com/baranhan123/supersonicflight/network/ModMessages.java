package com.baranhan123.supersonicflight.network;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.network.packet.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = SupersonicFlight.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModMessages {

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.1");

        registrar.playToServer(
                FlightTogglePayload.TYPE,
                FlightTogglePayload.STREAM_CODEC,
                FlightTogglePayload::handle
        );

        registrar.playToServer(
                FlightLaunchPayload.TYPE,
                FlightLaunchPayload.STREAM_CODEC,
                FlightLaunchPayload::handle
        );

        registrar.playToServer(
                FlightSonicPayload.TYPE,
                FlightSonicPayload.STREAM_CODEC,
                FlightSonicPayload::handle
        );

        registrar.playToServer(
                FlightForwardPayload.TYPE,
                FlightForwardPayload.STREAM_CODEC,
                FlightForwardPayload::handle
        );

    }
}
