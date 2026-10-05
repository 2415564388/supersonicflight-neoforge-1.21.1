package com.baranhan123.supersonicflight.util;

public enum FlightState {
    NONE,
    LAUNCH,   // Vertical takeoff with a shockwave at the feet, no elytra pose
    HOVER,    // Normal flight with WASD, no elytra pose
    SONIC     // Supersonic with shockwaves behind, elytra pose (Ctrl held)
}
