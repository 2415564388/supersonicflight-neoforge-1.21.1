package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FlightTogglePayload() implements CustomPacketPayload {
    public static final Type<FlightTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SupersonicFlight.MOD_ID, "flight_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlightTogglePayload> STREAM_CODEC =
            StreamCodec.unit(new FlightTogglePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FlightTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;

            SupersonicFlightPlayer flightPlayer = (SupersonicFlightPlayer) player;
            FlightState currentState = flightPlayer.getFlightState();

            // Cancel flight (double-tap without Shift)
            if (currentState != FlightState.NONE) {
                flightPlayer.stopFlight();
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
        });
    }
}
