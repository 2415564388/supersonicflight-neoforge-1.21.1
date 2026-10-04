package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: where the grabbing player's hand currently is, so the held entity can be
 * pinned to it.
 *
 * <p>The value is a <b>player-relative offset</b>, not a world position. At SONIC speed the player
 * moves up to 9 blocks per tick, so an absolute position would be stale by the time it reached the
 * server and the held mob would visibly trail behind the hand. An offset stays correct regardless
 * of latency.
 */
public record HandPosSyncPayload(double x, double y, double z) implements CustomPacketPayload {

    public static final Type<HandPosSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SupersonicFlight.MOD_ID, "hand_pos_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandPosSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.DOUBLE, HandPosSyncPayload::x,
                    ByteBufCodecs.DOUBLE, HandPosSyncPayload::y,
                    ByteBufCodecs.DOUBLE, HandPosSyncPayload::z,
                    HandPosSyncPayload::new);

    /**
     * Furthest the hand can plausibly be from the player's feet; anything beyond this is a bad or
     * hostile packet.
     *
     * <p>Deliberately generous. The first-person arm is authored in camera space with a large
     * constant offset baked in, so a legitimate offset is several blocks long — a tight clamp here
     * silently rejects real updates and the victim snaps back to the server's fallback position,
     * which reads in-game as "the mob dropped to my feet".
     */
    private static final double MAX_OFFSET = 8.0;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HandPosSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof SupersonicFlightPlayer core)) return;
            // Only meaningful while actually holding something, and reject implausible offsets
            // so a modified client cannot use this to fling a grabbed entity across the world.
            if (core.getGrabbedTarget() == null) return;

            Vec3 offset = new Vec3(payload.x(), payload.y(), payload.z());
            if (offset.lengthSqr() > MAX_OFFSET * MAX_OFFSET) return;

            core.setServerHandPos(offset);
        });
    }
}
