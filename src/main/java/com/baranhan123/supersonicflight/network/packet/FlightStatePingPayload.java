package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.client.FlightShockwaveManager;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: "your flight state just became this".
 *
 * <p>A latency fix, and specifically a fix for a <em>channel</em> mismatch rather than for the server
 * being slow. The other effects at these moments — the takeoff explosion and foot particles — go out
 * as their own {@code ClientboundLevelParticlesPacket}, sent the instant the server spawns them. The
 * flight state instead travels as synced entity data, which is batched at the end of the tick. Both
 * cross the same network, so the difference is entirely in when they are sent, and it is enough that
 * the shockwave visibly lands after the explosion it is supposed to accompany.
 *
 * <p>The client applies this by writing its own copy of the synced value. That is safe: the server's
 * authoritative update carries the same value, so it simply confirms what is already there.
 *
 * <p>Sent to the acting player only — other players' clients still learn through the normal sync,
 * which is fine because someone else's shockwave has no timing to keep.
 */
public record FlightStatePingPayload(int state) implements CustomPacketPayload {

    public static final Type<FlightStatePingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SupersonicFlight.MOD_ID, "flight_state_ping"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlightStatePingPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    FlightStatePingPayload::state,
                    FlightStatePingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FlightStatePingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof SupersonicFlightPlayer core)) return;
            // Range-checked rather than trusting the wire: an out-of-range ordinal would otherwise
            // throw out of the packet handler.
            FlightState[] states = FlightState.values();
            if (payload.state() < 0 || payload.state() >= states.length) return;

            FlightState state = states[payload.state()];
            core.setFlightState(state);
            // Spawn the shockwave here, not on the next tick: this handler runs ~10 ms after the
            // request, while the tick loop would not look for another 50.
            //
            // The dist check guards a client-only class being named from common code. It cannot
            // actually be reached on a dedicated server — this is a playToClient payload, so the
            // handler only ever runs on a client — but the check makes that explicit instead of
            // leaving it to be inferred from the registration.
            if (FMLEnvironment.dist.isClient()) {
                FlightShockwaveManager.onStatePushed(context.player(), state);
            }
        });
    }
}
