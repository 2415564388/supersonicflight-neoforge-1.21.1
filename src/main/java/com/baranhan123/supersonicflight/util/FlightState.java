package com.baranhan123.supersonicflight.util;

public enum FlightState {
    NONE,
    LAUNCH,   // Vertical takeoff with Mach disk at feet, no elytra pose
    HOVER,    // Normal flight with WASD, no elytra pose
    SONIC     // Supersonic with Mach disk behind, elytra pose (Ctrl held)
}
