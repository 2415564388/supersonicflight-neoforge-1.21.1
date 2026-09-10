package com.baranhan123.supersonicflight.network.packet;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.mixin.FallingBlockEntityInvoker;
import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

            // Damage nearby entities on takeoff (50x player attack damage). Same GENERIC_KILL true
            // damage as the sonic impact so both bursts behave consistently (bypass armor/resistance).
            float attackDamage = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            float damage = attackDamage * 50.0f;
            for (net.minecraft.world.entity.LivingEntity nearby : serverLevel.getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    player.getBoundingBox().inflate(5.0))) {
                if (nearby != player) {
                    nearby.hurt(serverLevel.damageSources().source(DamageTypes.GENERIC_KILL, player), damage);
                }
            }
        });
    }

    public static void destroyTerrain(ServerPlayer player, ServerLevel level, int radius) {
        BlockPos playerPos = player.blockPosition();
        RandomSource random = level.random;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;

                BlockPos targetPos = null;
                BlockState targetState = null;

                for (int dy = 3; dy >= -5; dy--) {
                    BlockPos checkPos = playerPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);
                    if (!state.isAir() && !state.getFluidState().isEmpty()) continue;
                    if (!state.isAir()) {
                        float hardness = state.getDestroySpeed(level, checkPos);
                        if (hardness >= 0 && hardness < 50.0f) {
                            targetPos = checkPos;
                            targetState = state;
                            break;
                        }
                    }
                }

                if (targetPos != null && targetState != null) {
                    FallingBlockEntity fallingBlock = FallingBlockEntityInvoker.invokeConstructor(
                            level,
                            targetPos.getX() + 0.5,
                            targetPos.getY(),
                            targetPos.getZ() + 0.5,
                            targetState);
                    fallingBlock.dropItem = false;
                    fallingBlock.time = 1;

                    double vx = dx;
                    double vz = dz;
                    double dist = Math.sqrt(vx * vx + vz * vz);
                    if (dist > 0) { vx /= dist; vz /= dist; }
                    double speed = 0.4 + random.nextDouble() * 0.6;
                    double vSpeed = 0.1 + random.nextDouble() * 0.15;
                    fallingBlock.setDeltaMovement(vx * vSpeed, speed, vz * vSpeed);
                    fallingBlock.hasImpulse = true;
                    level.addFreshEntity(fallingBlock);
                    level.destroyBlock(targetPos, false);
                }
            }
        }
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
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    float hardness = state.getDestroySpeed(level, pos);
                    if (hardness >= 0.0f && hardness < 50.0f) {
                        level.destroyBlock(pos, false);
                    }
                }
            }
        }
    }
}
