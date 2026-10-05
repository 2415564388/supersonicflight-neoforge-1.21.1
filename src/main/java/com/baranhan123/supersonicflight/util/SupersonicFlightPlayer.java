package com.baranhan123.supersonicflight.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public interface SupersonicFlightPlayer {
    FlightState getFlightState();
    void setFlightState(FlightState state);
    float getFlightThrottle();
    void setFlightThrottle(float throttle);
    boolean isFlightAccelerating();
    void setFlightAccelerating(boolean accelerating);
    float getLerpedFlightThrottle(float partialTicks);
    void stopFlight();
    int getFlightTicks();
    void setFlightTicks(int ticks);
    int getTakeoffTicks();
    void setTakeoffTicks(int ticks);
    boolean isClientLocalPlayer();
    void setClientLocalPlayer(boolean isClientLocal);

    /** Whether the mod's flight system is enabled for this player (set by command) */
    boolean isFlightEnabled();
    void setFlightEnabled(boolean enabled);

    // --- Server-side rate limiting ---

    /**
     * Server tick of this player's last takeoff.
     *
     * <p>The takeoff packet is client-triggered and destroys terrain, so without a server-side
     * record a modified client could fire it far faster than a human can double-tap and grind the
     * world (and the server thread) away.
     */
    int getLastTakeoffTick();
    void setLastTakeoffTick(int tick);

    // --- Grab & heavy punch (ported from ViltrumiteCore) ---

    /** True while the grab is being resolved (set by the interact handler, cleared on success/failure). */
    boolean isTryingToGrab();
    void setTryingToGrab(boolean trying);

    /** The LivingEntity currently held in this player's hand, or null. Synced via entity id. */
    LivingEntity getGrabbedTarget();
    void setGrabbedTarget(LivingEntity target);

    /** Counts down from 20 while the heavy punch animation plays; the impact lands at tick 15. */
    int getPunchTicks();
    void setPunchTicks(int ticks);

    /**
     * Starts a heavy punch (server side), picking the arm and power.
     *
     * <p>Deliberately atomic: callers must not flip {@link #setLeftArmPunch} themselves. Doing that
     * before discovering the punch was rejected made the arm flag toggle without the animation
     * starting, which the client renders as the arm twitching twice.
     *
     * @return true if a punch actually started
     */
    boolean startPunch();

    /** Which arm throws the punch (alternates for variety). */
    boolean isLeftArmPunch();
    void setLeftArmPunch(boolean leftArm);

    /** 0..1 punch power, taken from the flight throttle so punching at speed hits harder. */
    float getPunchStrength();
    void setPunchStrength(float strength);

    int getPunchCooldown();
    void setPunchCooldown(int ticks);

    /**
     * Ticks remaining on the client's impact shockwave after a <em>real</em> sonic ground or wall
     * impact (0 = none). Server-set and synced; the client watches it rise.
     */
    int getImpactFxTicks();
    void setImpactFxTicks(int ticks);

    /** Server-side hand position (player-relative offset applied to the hand) for holding the target. */
    Vec3 getServerHandPos();
    void setServerHandPos(Vec3 pos);

    /** Client-only: hand position in world space, derived from the rendered arm pose. */
    Vec3 getCalculatedHandPos();
    void setCalculatedHandPos(Vec3 pos);

    /** Client-only: hand position relative to the player's body, so it stays valid at flight speed. */
    Vec3 getCalculatedHandOffset();
    void setCalculatedHandOffset(Vec3 offset);

    /** Client-only: hand position in first-person camera space. */
    Vec3 getFirstPersonLocalHandPos();
    void setFirstPersonLocalHandPos(Vec3 pos);
}
