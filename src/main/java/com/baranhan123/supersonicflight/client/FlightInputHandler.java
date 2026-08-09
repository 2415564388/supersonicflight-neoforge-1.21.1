package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.client.sound.FlightWindSoundInstance;
import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.network.packet.*;
import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class FlightInputHandler {

    private static boolean wasJumpPressed = false;
    private static long lastJumpTime = 0;
    private static FlightWindSoundInstance windSound = null;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        SupersonicFlightPlayer flightPlayer = (SupersonicFlightPlayer) player;
        FlightState state = flightPlayer.getFlightState();
        boolean isFlying = state != FlightState.NONE;

        boolean jumpPressed = mc.options.keyJump.isDown();

        // --- Double-tap Space: LAUNCH or CANCEL ---
        if (jumpPressed && !wasJumpPressed) {
            long now = System.currentTimeMillis();
            if (now - lastJumpTime < 300) {
                // Consume jump events so vanilla creative flight doesn't also trigger
                while (mc.options.keyJump.consumeClick()) {}
                if (state == FlightState.NONE && flightPlayer.isFlightEnabled()) {
                    PacketDistributor.sendToServer(new FlightLaunchPayload());
                } else if (isFlying) {
                    PacketDistributor.sendToServer(new FlightTogglePayload());
                }
            }
            lastJumpTime = now;
        }
        wasJumpPressed = jumpPressed;

        // --- W key: forward in HOVER ---
        boolean forwardHeld = mc.options.keyUp.isDown();
        if (state == FlightState.HOVER) {
            boolean wasForward = flightPlayer.isFlightAccelerating();
            if (forwardHeld != wasForward) {
                PacketDistributor.sendToServer(new FlightForwardPayload(forwardHeld));
            }
        }

        // --- Ctrl held = SONIC (uses raw GLFW to bypass sprint toggle mode) ---
        long window = mc.getWindow().getWindow();
        boolean ctrlPhysicallyHeld = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        // Creative mode fix: force flying=false so vanilla creative flight doesn't override elytra pose
        if (isFlying) {
            player.getAbilities().flying = false;
        }

        if (state != FlightState.NONE && state != FlightState.LAUNCH) {
            boolean wantSonic = ctrlPhysicallyHeld;
            boolean isSonic = (state == FlightState.SONIC);
            if (wantSonic != isSonic) {
                PacketDistributor.sendToServer(new FlightSonicPayload(wantSonic));
            }
        }

        // --- Wind sound management ---
        if (SupersonicConfigClient.INSTANCE.enableWindLoopSound && isFlying) {
            if (windSound == null || windSound.isStopped()) {
                windSound = new FlightWindSoundInstance(player, ModSounds.WIND_LOOP.get());
                mc.getSoundManager().play(windSound);
            }
        } else {
            if (windSound != null && !windSound.isStopped()) {
                mc.getSoundManager().stop(windSound);
                windSound = null;
            }
        }
    }
}
