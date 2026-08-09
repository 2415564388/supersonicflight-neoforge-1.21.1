package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.client.mixin.PostChainAccessor;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class SonicBoomEffect {

    private static final ResourceLocation SHADER_LOC =
            ResourceLocation.fromNamespaceAndPath("supersonicflight", "shaders/post/sonic_boom.json");

    private static PostChain postChain;
    private static float currentThrottle = 0f;
    private static float rippleTime = 0f;
    private static float sonicFlash = 0f;
    private static long sonicEntryTime = 0;
    private static long launchTime = 0;
    private static long impactTime = 0;
    private static boolean wasSonic = false;
    private static FlightState prevState = FlightState.NONE;

    /** Called by MachDiskManager when ground impact detected */
    public static void triggerImpact() {
        impactTime = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            currentThrottle = 0f;
            sonicFlash = 0f;
            return;
        }

        if (mc.player instanceof SupersonicFlightPlayer fp) {
            FlightState state = fp.getFlightState();

            if (state == FlightState.LAUNCH) {
                currentThrottle = 0.3f;
                if (prevState != FlightState.LAUNCH) launchTime = System.currentTimeMillis();
            } else if (state == FlightState.SONIC) {
                currentThrottle = Math.min(1f, currentThrottle + 0.1f);
                if (!wasSonic) {
                    wasSonic = true;
                    sonicEntryTime = System.currentTimeMillis();
                    rippleTime = 1.0f;
                    sonicFlash = 1.0f;  // Trigger white flash
                }
            } else if (state == FlightState.HOVER) {
                currentThrottle = Math.max(0f, currentThrottle - 0.05f);
                wasSonic = false;
                launchTime = 0;
            } else {
                currentThrottle = Math.max(0f, currentThrottle - 0.1f);
                wasSonic = false;
                launchTime = 0;
            }

            // Flash decay: 5 ticks (0.25s) — always decay regardless of state
            if (sonicFlash > 0) {
                sonicFlash = Math.max(0, sonicFlash - 0.2f);
            }

            // Ripple decay: always decay
            if (rippleTime > 0) {
                rippleTime = Math.max(0, rippleTime - 0.05f);
            }
            prevState = state;
        } else {
            currentThrottle = 0f;
            rippleTime = 0f;
            sonicFlash = 0f;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (currentThrottle <= 0.01f && sonicFlash <= 0.01f) return;

        Minecraft mc = Minecraft.getInstance();

        try {
            if (postChain == null) {
                postChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                        mc.getMainRenderTarget(), SHADER_LOC);
                postChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            }

            float time = (System.currentTimeMillis() % 100000) / 1000f;
            float takeoffShake = 0f;
            if (launchTime > 0) {
                float sinceTicks = (System.currentTimeMillis() - launchTime) / 50f;
                if (sinceTicks < 8) takeoffShake = 1.0f - sinceTicks / 8f;
                else launchTime = 0;
            }
            if (impactTime > 0) {
                float sinceTicks = (System.currentTimeMillis() - impactTime) / 50f;
                if (sinceTicks < 15) takeoffShake = Math.max(takeoffShake, 1.0f - sinceTicks / 15f);
                else impactTime = 0;
            }

            for (PostPass pass : ((PostChainAccessor) postChain).getPasses()) {
                pass.getEffect().safeGetUniform("Throttle").set(currentThrottle);
                pass.getEffect().safeGetUniform("RippleTime").set(rippleTime);
                pass.getEffect().safeGetUniform("Time").set(time);
                pass.getEffect().safeGetUniform("TakeoffShake").set(takeoffShake);
                pass.getEffect().safeGetUniform("FlashIntensity").set(sonicFlash);
            }

            postChain.process(mc.getTimer().getGameTimeDeltaPartialTick(false));
        } catch (Exception e) {
            postChain = null;
        }
    }
}
