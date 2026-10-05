package com.baranhan123.supersonicflight.mixin;

import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.network.packet.FlightLaunchPayload;
import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.GrabPunchManager;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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

    // Grab & heavy punch (ported from ViltrumiteCore). These MUST stay in this one static
    // initializer: SynchedEntityData.defineId hands out ids in class-init order, so splitting
    // Player accessors across two mixins risks a client/server id desync.
    @Unique
    private static final EntityDataAccessor<Boolean> TRYING_TO_GRAB =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);
    @Unique
    private static final EntityDataAccessor<Integer> GRABBED_TARGET_ID =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);
    @Unique
    private static final EntityDataAccessor<Integer> PUNCH_TICKS =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);
    @Unique
    private static final EntityDataAccessor<Boolean> IS_LEFT_ARM_PUNCH =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);
    @Unique
    private static final EntityDataAccessor<Float> PUNCH_STRENGTH =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);
    @Unique
    private static final EntityDataAccessor<Integer> PUNCH_COOLDOWN =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);
    /**
     * Ticks left on the client's impact shockwave. Deliberately the LAST defineId in this class:
     * ids are handed out in class-init order, so appending here leaves every existing id untouched.
     */
    @Unique
    private static final EntityDataAccessor<Integer> IMPACT_FX_TICKS =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);

    @Unique
    private boolean isClientLocalPlayer = false;
    @Unique
    private float smoothedThrottle = 0.0f;

    /** Length of the heavy-punch animation; the impact lands at tick 15 of it. */
    @Unique
    private static final int PUNCH_ANIMATION_TICKS = 20;

    /**
     * Height above the player kept clear on every LAUNCH tick. The launch boost climbs 6 blocks per
     * tick, so 12 covers the next tick's ascent plus margin.
     */
    @Unique
    private static final int TAKEOFF_CLEAR_TICK_HEIGHT = 12;

    /** Minimum ticks between two sonic impacts (see sonicGroundImpact). */
    @Unique
    private static final int IMPACT_COOLDOWN_TICKS = 20;

    /**
     * How long the client's impact shockwave lasts. Equal to the impact cooldown on purpose: the
     * counter is guaranteed to fall back to 0 before another impact can be accepted, which is what
     * makes the client's "rose above the previous value" edge test unambiguous.
     */
    @Unique
    private static final int IMPACT_FX_DURATION_TICKS = 20;
    @Unique
    private int lastSonicImpactTick = -1000;
    /** Cleared on impact and re-armed once the player is clear of the ground/wall again. */
    @Unique
    private boolean sonicImpactArmed = true;
    /** Server tick of the last accepted takeoff, for rate limiting (see getLastTakeoffTick). */
    @Unique
    private int lastTakeoffTick = -1000;

    /** Server-side hand offset (relative to the player) where a grabbed entity is held. */
    @Unique
    private Vec3 serverHandPos = null;
    /** Client-only hand positions, filled in by the render mixins. */
    @Unique
    private Vec3 calculatedHandPos = null;
    @Unique
    private Vec3 calculatedHandOffset = null;
    @Unique
    private Vec3 firstPersonLocalHandPos = null;

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
        builder.define(TRYING_TO_GRAB, false);
        builder.define(GRABBED_TARGET_ID, -1);
        builder.define(PUNCH_TICKS, 0);
        builder.define(IS_LEFT_ARM_PUNCH, false);
        builder.define(PUNCH_STRENGTH, 0.0f);
        builder.define(PUNCH_COOLDOWN, 0);
        builder.define(IMPACT_FX_TICKS, 0);
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

    @Override
    public int getLastTakeoffTick() {
        return lastTakeoffTick;
    }

    @Override
    public void setLastTakeoffTick(int tick) {
        this.lastTakeoffTick = tick;
    }

    // --- Grab & heavy punch interface implementation ---

    @Override
    public boolean isTryingToGrab() {
        return getEntityData().get(TRYING_TO_GRAB);
    }

    @Override
    public void setTryingToGrab(boolean trying) {
        getEntityData().set(TRYING_TO_GRAB, trying);
    }

    @Override
    public LivingEntity getGrabbedTarget() {
        int id = getEntityData().get(GRABBED_TARGET_ID);
        if (id == -1) return null;
        // level() resolves the id on whichever side is asking; -1 is the "nothing grabbed" sentinel.
        return level().getEntity(id) instanceof LivingEntity living ? living : null;
    }

    @Override
    public void setGrabbedTarget(LivingEntity target) {
        getEntityData().set(GRABBED_TARGET_ID, target == null ? -1 : target.getId());
    }

    @Override
    public int getPunchTicks() {
        return getEntityData().get(PUNCH_TICKS);
    }

    @Override
    public void setPunchTicks(int ticks) {
        getEntityData().set(PUNCH_TICKS, ticks);
    }

    @Override
    public boolean startPunch() {
        if (level().isClientSide()) return false;
        // Rejected punches must change nothing at all — see the interface doc.
        if (getPunchCooldown() > 0 || getPunchTicks() > 0) return false;

        setLeftArmPunch(!isLeftArmPunch());
        // Punching at flight speed hits far harder, mirroring ViltrumiteCore's throttle-scaled punch.
        setPunchStrength(getFlightState() != FlightState.NONE ? getFlightThrottle() : 0.0f);
        setPunchCooldown(SupersonicConfig.INSTANCE.punchCooldownTicks);
        setPunchTicks(PUNCH_ANIMATION_TICKS);
        return true;
    }

    @Override
    public boolean isLeftArmPunch() {
        return getEntityData().get(IS_LEFT_ARM_PUNCH);
    }

    @Override
    public void setLeftArmPunch(boolean leftArm) {
        getEntityData().set(IS_LEFT_ARM_PUNCH, leftArm);
    }

    @Override
    public float getPunchStrength() {
        return getEntityData().get(PUNCH_STRENGTH);
    }

    @Override
    public void setPunchStrength(float strength) {
        getEntityData().set(PUNCH_STRENGTH, strength);
    }

    @Override
    public int getPunchCooldown() {
        return getEntityData().get(PUNCH_COOLDOWN);
    }

    @Override
    public void setPunchCooldown(int ticks) {
        getEntityData().set(PUNCH_COOLDOWN, ticks);
    }

    @Override
    public int getImpactFxTicks() {
        return getEntityData().get(IMPACT_FX_TICKS);
    }

    @Override
    public void setImpactFxTicks(int ticks) {
        getEntityData().set(IMPACT_FX_TICKS, ticks);
    }

    @Override
    public Vec3 getServerHandPos() {
        return serverHandPos;
    }

    @Override
    public void setServerHandPos(Vec3 pos) {
        this.serverHandPos = pos;
    }

    @Override
    public Vec3 getCalculatedHandPos() {
        return calculatedHandPos;
    }

    @Override
    public void setCalculatedHandPos(Vec3 pos) {
        this.calculatedHandPos = pos;
    }

    @Override
    public Vec3 getCalculatedHandOffset() {
        return calculatedHandOffset;
    }

    @Override
    public void setCalculatedHandOffset(Vec3 offset) {
        this.calculatedHandOffset = offset;
    }

    @Override
    public Vec3 getFirstPersonLocalHandPos() {
        return firstPersonLocalHandPos;
    }

    @Override
    public void setFirstPersonLocalHandPos(Vec3 pos) {
        this.firstPersonLocalHandPos = pos;
    }

    /** Left-clicking the entity you are holding throws the heavy punch instead of a melee swing. */
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttackGrabbedTarget(net.minecraft.world.entity.Entity target, CallbackInfo ci) {
        // Only meaningful while the superhero power set is active.
        if (!isFlightEnabled()) return;

        LivingEntity grabbed = getGrabbedTarget();
        if (grabbed != null && grabbed == target) {
            // Cancelled on both sides so the client does not also play a melee swing, but only
            // the server changes the punch state — the synced value drives the client's animation.
            if (!level().isClientSide()) {
                startPunch();
            }
            ci.cancel();
        }
    }

    // --- Tick logic ---

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        FlightState currentState = getFlightState();

        // Grab / punch run every tick regardless of flight state, so this deliberately sits
        // above the `currentState == NONE` early-return below.
        GrabPunchManager.tick(this, self);

        // Same reason it sits above that early return, and here the placement is load-bearing: a
        // player who cancels flight inside the impact shockwave's window exits through that return,
        // so a decrement placed below it would freeze the counter at a positive value — the client's
        // edge test would never see it fall back to 0, and every later impact would be silent.
        if (!level().isClientSide() && getImpactFxTicks() > 0) {
            setImpactFxTicks(getImpactFxTicks() - 1);
        }

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

            // Clear the tube above so the 6-blocks/tick ascent never gets stuck on a ceiling.
            if (!level().isClientSide() && SupersonicConfig.INSTANCE.breakBlocksAboveOnTakeoff) {
                if (level() instanceof ServerLevel serverLevel) {
                    breakBlocksAbove((Player) (Object) this, serverLevel);
                }
            }

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

        // Collision handling (server-side).
        //
        // Collisions never change the flight state. Hitting terrain means "you cannot move that
        // way", not "you are no longer flying" — and letting the environment end SONIC made the
        // state flip HOVER↔SONIC while scraping terrain, which pumped the FOV, re-fired the
        // sonic-entry effect and cost the player their speed every time. The collision itself
        // already stops the movement, so nothing needs to be suppressed. Only double-tap space
        // leaves a flight state.
        if (!level().isClientSide() && currentState != FlightState.LAUNCH) {
            boolean touchingGround = onGround() && getTakeoffTicks() == 0;
            // Ramming terrain from below sets verticalCollision but NOT onGround (vanilla only sets
            // onGround when moving down) and NOT horizontalCollision — so flying straight up into a
            // ceiling used to be completely unhandled and produced no impact at all.
            // verticalCollisionBelow is true only when the collision happened while descending, so
            // testing it false isolates "hit something above" without depending on onGround.
            boolean rammedCeiling = verticalCollision && !verticalCollisionBelow;
            boolean inContact = touchingGround || rammedCeiling || horizontalCollision;

            // Re-arm the impact once the player is clear again; the cooldown alone would still let
            // a player resting on the ground detonate a fresh crater every second.
            if (!inContact) {
                sonicImpactArmed = true;
            } else if (currentState == FlightState.SONIC) {
                sonicGroundImpact(self, rammedCeiling);
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

    /**
     * @param ceiling true when the player rammed terrain from below, so the crater is carved upward
     *                instead of down into the ground
     */
    @Unique
    private void sonicGroundImpact(Player self, boolean ceiling) {
        // Since collisions no longer end flight, a player resting against the ground satisfies
        // `onGround()` every tick. Fire once per contact episode (the armed flag, re-armed in the
        // collision block) with a small cooldown so sliding along a wall cannot machine-gun it.
        if (!sonicImpactArmed) return;
        if (this.tickCount - lastSonicImpactTick < IMPACT_COOLDOWN_TICKS) return;
        sonicImpactArmed = false;
        this.lastSonicImpactTick = this.tickCount;
        // Tell the client an impact actually happened, so it can spawn the shockwave. There is no
        // client-bound packet; this rides the same synced entity data as the flight state, and
        // FlightShockwaveManager edge-detects it rising.
        setImpactFxTicks(IMPACT_FX_DURATION_TICKS);

        // Immune to fall damage
        self.fallDistance = 0;
        self.resetFallDistance();

        // Terrain destruction on impact
        if (SupersonicConfig.INSTANCE.breakBlocksOnImpact && self instanceof ServerPlayer sp) {
            if (self.level() instanceof ServerLevel sl) {
                if (ceiling) {
                    FlightLaunchPayload.destroyTerrainAbove(sp, sl, SupersonicConfig.INSTANCE.destructionRadius);
                } else {
                    FlightLaunchPayload.destroyTerrain(sp, sl, SupersonicConfig.INSTANCE.destructionRadius);
                }
            }
        }

        // True damage to nearby entities. GENERIC_KILL is in the vanilla minecraft:bypasses_armor /
        // bypasses_resistance tags, so it ignores armor and resistance (matching the modpack's
        // "真实伤害" convention in hunter_extralevel.js). Scaled by config, not a flat 50x.
        float attackDamage = (float) self.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        float damage = attackDamage * SupersonicConfig.INSTANCE.impactDamageMultiplier;
        for (LivingEntity nearby : self.level().getEntitiesOfClass(LivingEntity.class,
                self.getBoundingBox().inflate(6.0))) {
            if (nearby != self) {
                nearby.hurt(self.level().damageSources().source(DamageTypes.GENERIC_KILL, self), damage);
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

    /**
     * Breaks a short tube of blocks directly above the player on each launch tick. The LAUNCH
     * boost moves 6 blocks/tick, so this clears feet+1 .. feet+12 (a couple of ticks of ascent
     * plus margin) to keep the next tick's climb free — an underground takeoff smashes through
     * the ceiling instead of getting stuck against it.
     */
    @Unique
    private void breakBlocksAbove(Player self, ServerLevel level) {
        int radius = Math.max(0, SupersonicConfig.INSTANCE.takeoffClearRadius);
        BlockPos feet = self.blockPosition();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // These are RELATIVE offsets: BlockPos#offset ADDS them to the origin. Passing an
                // absolute Y (feet.getY() + 1) here double-counted the player's height and cleared
                // feet+65 .. feet+76 instead of feet+1 .. feet+12 — so the ceiling directly overhead
                // was never cleared, and a 3x3x12 block of holes appeared high above the player.
                for (int dy = 1; dy <= TAKEOFF_CLEAR_TICK_HEIGHT; dy++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    float hardness = state.getDestroySpeed(level, pos);
                    if (hardness >= 0.0f && hardness < SupersonicConfig.INSTANCE.destructionMaxHardness) {
                        level.destroyBlock(pos, false);
                    }
                }
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
