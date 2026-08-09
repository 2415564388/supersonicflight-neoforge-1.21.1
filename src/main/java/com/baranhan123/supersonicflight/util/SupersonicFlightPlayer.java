package com.baranhan123.supersonicflight.util;

public interface SupersonicFlightPlayer {
    FlightState getFlightState();
    void setFlightState(FlightState state);
    float getFlightThrottle();
    void setFlightThrottle(float throttle);
    boolean isFlightAccelerating();
    void setFlightAccelerating(boolean accelerating);
    float getLerpedFlightThrottle(float partialTicks);
    void stopFlight();
    void handleFlightCollision();
    int getFlightTicks();
    void setFlightTicks(int ticks);
    int getTakeoffTicks();
    void setTakeoffTicks(int ticks);
    boolean isClientLocalPlayer();
    void setClientLocalPlayer(boolean isClientLocal);

    /** Whether the mod's flight system is enabled for this player (set by command) */
    boolean isFlightEnabled();
    void setFlightEnabled(boolean enabled);
}
