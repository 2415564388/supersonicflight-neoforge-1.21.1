package com.baranhan123.supersonicflight.mixin;

import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.network.packet.FlightLaunchPayload;
import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerEntityMixin extends LivingEntity implements SupersonicFlightPlayer {

    @Unique
    private static final EntityDataAccessor<Byte> FLIGHT_STATE =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BYTE);
    @Unique
    private static final EntityDataAccessor<Float> FLIGHT_THROTTLE =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);
    @Unique
    private static final EntityDataAccessor<Boolean> FLIGHT_ACCELERATING =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);
    @Unique
    private static final EntityDataAccessor<Integer> FLIGHT_TICKS =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);
    @Unique
    private static final EntityDataAccessor<Integer> TAKEOFF_TICKS =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);
    @Unique
    private static final EntityDataAccessor<Boolean> FLIGHT_ENABLED =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);

    @Unique
    private boolean isClientLocalPlayer = false;
    @Unique
    private float smoothedThrottle = 0.0f;

    // Speeds
    @Unique
    private static final float NORMAL_SPEED = 2.0f;  // blocks/tick when W is held
    @Unique
    private static final float SONIC_SPEED = 9.0f;   // blocks/tick when Ctrl is held
    @Unique
    private static final float LAUNCH_SPEED = 6.0f;  // vertical boost speed

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    // Initialize synched data
    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void onDefineSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(FLIGHT_STATE, (byte) FlightState.NONE.ordinal());
        builder.define(FLIGHT_THROTTLE, 0.0f);
        builder.define(FLIGHT_ACCELERATING, false);
        builder.define(FLIGHT_TICKS, 0);
        builder.define(TAKEOFF_TICKS, 0);
        builder.define(FLIGHT_ENABLED, false);
    }

    // --- SupersonicFlightPlayer interface ---

    @Override
    public FlightState getFlightState() {
        return FlightState.values()[getEntityData().get(FLIGHT_STATE)];
    }

    @Override
    public void setFlightState(FlightState state) {
        getEntityData().set(FLIGHT_STATE, (byte) state.ordinal());
        // Elytra pose for YSM: SONIC triggers fall-flying, other states cancel it
        if (state == FlightState.SONIC) {
            if (!this.getSharedFlag(7)) {
                this.setSharedFlag(7, true);
            }
        } else {
            if (this.getSharedFlag(7)) {
                this.setSharedFlag(7, false);
            }
        }
    }

    @Override
    public float getFlightThrottle() {
        return getEntityData().get(FLIGHT_THROTTLE);
    }

    @Override
    public void setFlightThrottle(float throttle) {
        getEntityData().set(FLIGHT_THROTTLE, throttle);
    }

    @Override
    public boolean isFlightAccelerating() {
        return getEntityData().get(FLIGHT_ACCELERATING);
    }

    @Override
    public void setFlightAccelerating(boolean accelerating) {
        getEntityData().set(FLIGHT_ACCELERATING, accelerating);
    }

    @Override
    public float getLerpedFlightThrottle(float partialTicks) {
        float raw = getFlightThrottle();
        if (level().isClientSide()) {
            smoothedThrottle += (raw - smoothedThrottle) * 0.15f;
            return smoothedThrottle;
        }
        return raw;
    }

    @Override
    public int getFlightTicks() {
        return getEntityData().get(FLIGHT_TICKS);
    }

    @Override
    public void setFlightTicks(int ticks) {
        getEntityData().set(FLIGHT_TICKS, ticks);
    }

    @Override
    public int getTakeoffTicks() {
        return getEntityData().get(TAKEOFF_TICKS);
    }

    @Override
    public void setTakeoffTicks(int ticks) {
        getEntityData().set(TAKEOFF_TICKS, ticks);
    }

    @Override
    public boolean isClientLocalPlayer() {
        return isClientLocalPlayer;
    }

    @Override
    public void setClientLocalPlayer(boolean clientLocal) {
        this.isClientLocalPlayer = clientLocal;
    }

    @Override
    public boolean isFlightEnabled() {
        return getEntityData().get(FLIGHT_ENABLED);
    }

    @Override
    public void setFlightEnabled(boolean enabled) {
        getEntityData().set(FLIGHT_ENABLED, enabled);
        if (!enabled) {
            stopFlight();
        }
    }

    // --- Tick logic ---

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        FlightState currentState = getFlightState();

        // Handle takeoff ticks (vertical boost during LAUNCH)
        if (getTakeoffTicks() > 0) {
            setTakeoffTicks(getTakeoffTicks() - 1);
            Vec3 velocity = getDeltaMovement();
            setDeltaMovement(velocity.x(), LAUNCH_SPEED, velocity.z());
            hasImpulse = true;

            if (getTakeoffTicks() == 0 && currentState == FlightState.LAUNCH) {
                // Launch complete, transition to HOVER
                setFlightState(FlightState.HOVER);
            }
        }

        if (currentState == FlightState.NONE) {
            // Decrease flight ticks when not flying
            if (getFlightTicks() > 0) {
                setFlightTicks(Math.max(0, getFlightTicks() - 2));
            }
            // If flight mode is enabled by command, always immune to fall damage
            if (isFlightEnabled()) {
                self.fallDistance = 0;
                self.resetFallDistance();
            }
            return;
        }

        // --- Active flight ---

        // Immune to fall damage during any mod flight
        self.fallDistance = 0;
        self.resetFallDistance();

        setNoGravity(true);

        // Prevent creative flight from fighting with mod flight — vanilla resets the
        // fall-flying flag when abilities.flying=true, causing flicker every few seconds
        if (self.getAbilities().flying) {
            self.getAbilities().flying = false;
            self.onUpdateAbilities();
        }

        // Maintain elytra flight state: SONIC = fall-flying, others = normal
        if (currentState == FlightState.SONIC) {
            if (!this.getSharedFlag(7)) this.setSharedFlag(7, true);
        } else {
            if (this.getSharedFlag(7)) this.setSharedFlag(7, false);
        }

        float maxSpeed = SupersonicConfig.INSTANCE.maxFlightSpeed;

        if (currentState == FlightState.LAUNCH) {
            // LAUNCH: vertical boost handled by takeoff ticks above
            // Counter for flight time
            setFlightTicks(Math.min(400, getFlightTicks() + 1));

        } else if (currentState == FlightState.HOVER) {
            // HOVER: W=forward, release=decelerate
            if (isFlightAccelerating()) {
                Vec3 look = getLookAngle();
                float speed = NORMAL_SPEED;
                setDeltaMovement(look.x() * speed, look.y() * speed, look.z() * speed);
                setFlightTicks(Math.min(400, getFlightTicks() + 1));
            } else {
                // Decelerate smoothly
                Vec3 vel = getDeltaMovement();
                setDeltaMovement(vel.scale(0.9));
                setFlightTicks(Math.max(0, getFlightTicks() - 2));
            }

        } else if (currentState == FlightState.SONIC) {
            // SONIC: high-speed forward flight
            Vec3 look = getLookAngle();
            float speed = Math.min(maxSpeed, SONIC_SPEED);
            setDeltaMovement(look.x() * speed, look.y() * speed, look.z() * speed);
            setFlightTicks(Math.min(400, getFlightTicks() + 1));
        }

        // Collision handling (server-side)
        if (!level().isClientSide() && currentState != FlightState.LAUNCH) {
            if (onGround() && getTakeoffTicks() == 0) {
                if (currentState == FlightState.SONIC) {
                    sonicGroundImpact(self);
                }
                stopFlight();
            } else if (horizontalCollision) {
                if (currentState == FlightState.SONIC) {
                    // Wall impact: same destruction as ground
                    sonicGroundImpact(self);
                }
                switchToHover();
            }
        }
    }

    @Override
    public void stopFlight() {
        setFlightState(FlightState.NONE);
        setFlightThrottle(0.0f);
        setFlightAccelerating(false);
        setTakeoffTicks(0);

        if (this.getSharedFlag(7)) {
            this.setSharedFlag(7, false);
        }
        setNoGravity(false);
    }

    @Unique
    public void switchToHover() {
        setFlightState(FlightState.HOVER);
        setFlightThrottle(0.0f);
        setFlightAccelerating(false);
        setTakeoffTicks(0);
    }

    @Override
    public void handleFlightCollision() {
        switchToHover();
    }

    @Unique
    private void sonicGroundImpact(Player self) {
        // Immune to fall damage
        self.fallDistance = 0;
        self.resetFallDistance();

        // Terrain destruction on impact
        if (SupersonicConfig.INSTANCE.breakBlocksOnImpact && self instanceof ServerPlayer sp) {
            if (self.level() instanceof ServerLevel sl) {
                FlightLaunchPayload.destroyTerrain(sp, sl, SupersonicConfig.INSTANCE.destructionRadius);
            }
        }

        // Explosion-like damage to nearby entities (50x attack damage)
        float attackDamage = (float) self.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        float damage = attackDamage * 50.0f;
        for (LivingEntity nearby : self.level().getEntitiesOfClass(LivingEntity.class,
                self.getBoundingBox().inflate(6.0))) {
            if (nearby != self) {
                nearby.hurt(self.level().damageSources().explosion(self, self), damage);
            }
        }

        // Sounds and particles on both sides
        if (self.level().isClientSide()) {
            self.level().playLocalSound(self.getX(), self.getY(), self.getZ(),
                    ModSounds.SONIC_BOOM.get(), SoundSource.PLAYERS, 5.0f, 0.6f, false);
            self.level().playLocalSound(self.getX(), self.getY(), self.getZ(),
                    ModSounds.TAKEOFF.get(), SoundSource.PLAYERS, 5.0f, 0.8f, false);
        } else {
            self.level().playSound(null, self.getX(), self.getY(), self.getZ(),
                    ModSounds.SONIC_BOOM.get(), SoundSource.PLAYERS, 5.0f, 0.6f);
            self.level().playSound(null, self.getX(), self.getY(), self.getZ(),
                    ModSounds.TAKEOFF.get(), SoundSource.PLAYERS, 5.0f, 0.8f);
            // Star-like particles
            if (self.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                        self.getX(), self.getY() + 0.5, self.getZ(),
                        30, 2.0, 0.5, 2.0, 0.3);
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.FIREWORK,
                        self.getX(), self.getY() + 0.5, self.getZ(),
                        20, 1.5, 0.5, 1.5, 0.2);
            }
        }
    }

    // --- NBT persistence ---

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void writeCustomData(CompoundTag tag, CallbackInfo ci) {
        tag.putString("SupersonicFlightState", getFlightState().name());
        tag.putFloat("SupersonicFlightThrottle", getFlightThrottle());
        tag.putBoolean("SupersonicFlightEnabled", isFlightEnabled());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void readCustomData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("SupersonicFlightState")) {
            try {
                setFlightState(FlightState.valueOf(tag.getString("SupersonicFlightState")));
            } catch (IllegalArgumentException e) {
                setFlightState(FlightState.NONE);
            }
        }
        if (tag.contains("SupersonicFlightThrottle")) {
            setFlightThrottle(tag.getFloat("SupersonicFlightThrottle"));
        }
        if (tag.contains("SupersonicFlightEnabled")) {
            setFlightEnabled(tag.getBoolean("SupersonicFlightEnabled"));
        }
    }
}
