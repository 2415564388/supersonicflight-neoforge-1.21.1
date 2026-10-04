package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.baranhan123.supersonicflight.util.TerrainDestruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FlightLaunchPayload() implements CustomPacketPayload {
    public static final Type<FlightLaunchPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SupersonicFlight.MOD_ID, "flight_launch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlightLaunchPayload> STREAM_CODEC =
            StreamCodec.unit(new FlightLaunchPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FlightLaunchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;

            SupersonicFlightPlayer flightPlayer = (SupersonicFlightPlayer) player;
            if (flightPlayer.getFlightState() != FlightState.NONE) return;
            if (!flightPlayer.isFlightEnabled()) return;

            // Server-side rate limit. Takeoff is client-triggered and destroys terrain, so without
            // this a modified client could alternate launch/cancel far faster than a human can
            // double-tap and repeatedly crater the world on the server thread.
            int cooldown = SupersonicConfig.INSTANCE.takeoffCooldownTicks;
            if (cooldown > 0 && player.tickCount - flightPlayer.getLastTakeoffTick() < cooldown) {
                return;
            }
            flightPlayer.setLastTakeoffTick(player.tickCount);

            // Enter LAUNCH state - vertical takeoff
            flightPlayer.setFlightState(FlightState.LAUNCH);
            flightPlayer.setTakeoffTicks(10); // 10 ticks of vertical boost
            flightPlayer.setFlightThrottle(1.0f);

            ServerLevel serverLevel = (ServerLevel) player.level();

            // Mach disk spawn signal (handled client-side via state sync)
            // The client MachDiskManager watches for LAUNCH state transitions

            // Launch particles at feet
            serverLevel.sendParticles(ParticleTypes.FLAME,
                    player.getX(), player.getY(), player.getZ(),
                    60, 0.5, 0.1, 0.5, 0.2);
            serverLevel.sendParticles(ParticleTypes.CLOUD,
                    player.getX(), player.getY(), player.getZ(),
                    40, 0.8, 0.1, 0.8, 0.2);
            serverLevel.sendParticles(ParticleTypes.EXPLOSION,
                    player.getX(), player.getY(), player.getZ(),
                    5, 0.3, 0.1, 0.3, 0.1);

            // Launch sounds
            serverLevel.playSound(null,
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0f, 1.0f);
            serverLevel.playSound(null,
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.5f, 1.0f);
            serverLevel.playSound(null,
                    player.getX(), player.getY(), player.getZ(),
                    ModSounds.TAKEOFF.get(), SoundSource.PLAYERS, 2.5f, 1.0f);

            // Terrain destruction on takeoff
            if (SupersonicConfig.INSTANCE.breakBlocksOnTakeoff) {
                destroyTerrain(player, serverLevel, SupersonicConfig.INSTANCE.destructionRadius);
            }

            // Clear a vertical shaft above so an underground takeoff smashes through the ceiling
            // instead of getting stuck. Done here (packet handler) so it uses the exact position
            // where the launch was triggered, regardless of server/client movement timing.
            if (SupersonicConfig.INSTANCE.breakBlocksAboveOnTakeoff) {
                clearColumnAbove(player, serverLevel);
            }

            // Damage nearby entities on takeoff. GENERIC_KILL is true damage (bypasses armor and
            // resistance), scaled by the configurable multiplier rather than a flat 50x.
            float attackDamage = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            float damage = attackDamage * SupersonicConfig.INSTANCE.takeoffDamageMultiplier;
            for (net.minecraft.world.entity.LivingEntity nearby : serverLevel.getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    player.getBoundingBox().inflate(5.0))) {
                if (nearby != player) {
                    nearby.hurt(serverLevel.damageSources().source(DamageTypes.GENERIC_KILL, player), damage);
                }
            }
        });
    }

    /**
     * Blasts a crater under the player, using the mod's shared destruction rule (the same one the
     * heavy punch uses): shaped volume, block-break particles, and a configurable drop roll.
     *
     * <p>Clipped at roughly the player's feet so it hollows out the ground rather than also clearing
     * the air above — a full sphere at radius 8 would be a floating ball of nothing.
     */
    /** Crater for a downward impact (takeoff, landing): a bowl hollowed out below the player. */
    public static void destroyTerrain(ServerPlayer player, ServerLevel level, int radius) {
        Vec3 feet = player.position();
        TerrainDestruction.shatterCrater(
                level,
                feet,
                radius,
                feet.y - radius,   // down to the full sphere extent
                feet.y + 1.0,      // but not the air above the player
                TerrainDestruction.dropChanceFraction(SupersonicConfig.INSTANCE.destructionDropChance),
                player);
    }

    /** Crater for ramming a ceiling: a dome hollowed out above the player, not below. */
    public static void destroyTerrainAbove(ServerPlayer player, ServerLevel level, int radius) {
        Vec3 head = player.getEyePosition();
        TerrainDestruction.shatterCrater(
                level,
                head,
                radius,
                head.y - 1.0,      // do not eat the floor far beneath
                head.y + radius,   // up to the full sphere extent
                TerrainDestruction.dropChanceFraction(SupersonicConfig.INSTANCE.destructionDropChance),
                player);
    }

    /**
     * Breaks a vertical tube of blocks above the player at the moment of takeoff, so the launch
     * ascent never collides with a ceiling. Only breaks blocks the player could reasonably smash
     * through (hardness 0..50, skipping air and fluids — never bedrock/obsidian).
     */
    public static void clearColumnAbove(ServerPlayer player, ServerLevel level) {
        int radius = Math.max(0, SupersonicConfig.INSTANCE.takeoffClearRadius);
        int height = Math.max(0, SupersonicConfig.INSTANCE.takeoffClearHeight);
        BlockPos feet = player.blockPosition();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 1; dy <= height; dy++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    // Same breakability rule as everything else, but no drops and no particles:
                    // a 64-high shaft of debris would bury the player in items and lag the client.
                    if (!TerrainDestruction.isBreakable(level, pos, state)) continue;
                    level.destroyBlock(pos, false);
                }
            }
        }
    }
}
