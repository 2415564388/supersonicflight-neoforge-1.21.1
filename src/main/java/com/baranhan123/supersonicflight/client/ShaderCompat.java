package com.baranhan123.supersonicflight.client;

import java.lang.reflect.Field;

/**
 * Optional shader-pack interop.
 *
 * <p>Pose overrides applied to a player model must be skipped while a shader pack renders its
 * shadow map, otherwise the shadow is drawn with the wrong pose. Iris exposes a static
 * {@code ACTIVE} flag on {@code ShadowRenderingState}; it is looked up reflectively so the mod
 * needs no compile-time dependency on Iris and degrades to {@code false} when it is absent.
 */
public final class ShaderCompat {

    private static boolean resolved = false;
    private static Field shadowActiveField = null;

    private ShaderCompat() {
    }

    /** True only while a shader pack is rendering its shadow pass. */
    public static boolean isShadowPass() {
        if (!resolved) {
            resolve();
        }
        if (shadowActiveField == null) return false;
        try {
            return (Boolean) shadowActiveField.get(null);
        } catch (Throwable t) {
            // Iris changed shape or is mid-reload — treat as "no shadow pass" rather than crashing.
            shadowActiveField = null;
            return false;
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> cls = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
            shadowActiveField = cls.getField("ACTIVE");
        } catch (Throwable t) {
            shadowActiveField = null;
        }
    }
}
