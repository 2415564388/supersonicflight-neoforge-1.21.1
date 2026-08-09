package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FlightSonicPayload(boolean sonicActive) implements CustomPacketPayload {
    public static final Type<FlightSonicPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SupersonicFlight.MOD_ID, "flight_sonic"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlightSonicPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    FlightSonicPayload::sonicActive,
                    FlightSonicPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FlightSonicPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                SupersonicFlightPlayer flightPlayer = (SupersonicFlightPlayer) player;
                FlightState current = flightPlayer.getFlightState();
                if (current == FlightState.NONE || current == FlightState.LAUNCH) return;

                if (payload.sonicActive()) {
                    flightPlayer.setFlightState(FlightState.SONIC);
                } else {
                    flightPlayer.setFlightState(FlightState.HOVER);
                }
            }
        });
    }
}
