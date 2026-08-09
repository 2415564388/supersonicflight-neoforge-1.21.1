package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FlightForwardPayload(boolean forwardHeld) implements CustomPacketPayload {
    public static final Type<FlightForwardPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SupersonicFlight.MOD_ID, "flight_forward"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlightForwardPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    FlightForwardPayload::forwardHeld,
                    FlightForwardPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FlightForwardPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                ((SupersonicFlightPlayer) player).setFlightAccelerating(payload.forwardHeld());
            }
        });
    }
}
