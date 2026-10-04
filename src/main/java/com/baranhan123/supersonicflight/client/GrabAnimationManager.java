package com.baranhan123.supersonicflight.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.WeakHashMap;

/**
 * Smooths the 0→1 "is this player holding someone" weight used by the grab pose mixins.
 *
 * <p>The pose is driven off a target weight rather than a boolean so the arm eases into and out of
 * the grab instead of snapping. Keyed weakly by entity so leaving the world does not leak.
 */
public final class GrabAnimationManager {

    private static final WeakHashMap<LivingEntity, GrabAnimState> GRAB_STATES = new WeakHashMap<>();

    private GrabAnimationManager() {
    }

    public static float calculateWeight(LivingEntity entity, boolean isGrabbing) {
        GrabAnimState state = GRAB_STATES.computeIfAbsent(entity, k -> new GrabAnimState());
        long now = System.currentTimeMillis();
        float delta = (now - state.lastTime) / 1000.0f;

        if (delta > 0.0f) {
            // Clamp so a lag spike does not snap the pose straight to the target.
            if (delta > 0.1f) delta = 0.1f;
            state.lastTime = now;

            float targetWeight = isGrabbing ? 1.0f : 0.0f;
            // Frame-rate independent exponential ease.
            float smoothFactor = 1.0f - (float) Math.exp(-12.0f * delta);
            state.weight = Mth.lerp(smoothFactor, state.weight, targetWeight);
        }

        return state.weight;
    }

    private static final class GrabAnimState {
        float weight = 0.0f;
        long lastTime = System.currentTimeMillis();
    }
}
