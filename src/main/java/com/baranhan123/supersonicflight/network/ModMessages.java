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
        // "1.1" until FlightStatePingPayload was added; a client and server that disagree about the
        // payload set must not connect.
        final PayloadRegistrar registrar = event.registrar("1.2");

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

        registrar.playToServer(
                HandPosSyncPayload.TYPE,
                HandPosSyncPayload.STREAM_CODEC,
                HandPosSyncPayload::handle
        );

        // Server -> client, and the only one: sends the new flight state on the same immediate path
        // the takeoff particles use, instead of the end-of-tick entity-data batch, so the shockwave
        // lands with the explosion rather than after it.
        registrar.playToClient(
                FlightStatePingPayload.TYPE,
                FlightStatePingPayload.STREAM_CODEC,
                FlightStatePingPayload::handle
        );

    }
}
