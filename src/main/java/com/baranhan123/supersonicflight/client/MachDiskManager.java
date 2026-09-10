package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

/**
 * In-world shockwave / mach disk rendering.
 *
 * Heavily inspired by the original ViltrumiteFlight {@code MachDiskManager} (and the
 * ViltrumiteCore {@code DashVFXManager}): instead of one small fading ring we now spawn
 * several STAGGERED rings that expand quickly, glow with an ADDITIVE blend, and fade out
 * over about a second. Rings are time-based (milliseconds) so their motion is frame-rate
 * independent.
 */
@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class MachDiskManager {

    // Ring geometry: bright core band from 85% of the radius to 112% — soft outer glow.
    private static final float INNER_SCALE = 0.85f;
    private static final float OUTER_SCALE = 1.12f;
    private static final int SEGMENTS = 48;
    private static final int PARTICLE_COUNT = 24;

    // Mach disk look is white-blue (reference uses ~220/240/255).
    private static final int RING_R = 235;
    private static final int RING_G = 248;
    private static final int RING_B = 255;

    private static final List<ShockRing> RINGS = new ArrayList<>();
    private static final Map<UUID, FlightState> PREV_STATE = new HashMap<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            RINGS.clear();
            PREV_STATE.clear();
            return;
        }

        long now = System.currentTimeMillis();

        for (AbstractClientPlayer player : mc.level.players()) {
            if (!(player instanceof SupersonicFlightPlayer fp)) continue;
            UUID uuid = player.getUUID();
            FlightState prev = PREV_STATE.getOrDefault(uuid, FlightState.NONE);
            FlightState cur = fp.getFlightState();
            Vec3 pos = player.position();

            // Ground impact after SONIC — a big horizontal shockwave burst.
            if (cur == FlightState.NONE && prev == FlightState.SONIC && player.onGround()) {
                Vec3 feet = pos.add(0, 0.08, 0);
                RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 1.5f, 16.0f, 1000L, now, new Vec3(0, 1, 0)));
                RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 1.0f, 12.0f, 900L, now + 110L, new Vec3(0, 1, 0)));
                spawnParticleRing(feet, new Vec3(0, 1, 0), 1.5f, PARTICLE_COUNT);
                SonicBoomEffect.triggerImpact();
            }

            // Instant takeoff — a stack of 3 concentric horizontal rings at the feet.
            if (cur == FlightState.LAUNCH && prev != FlightState.LAUNCH) {
                Vec3 feet = pos.add(0, 0.06, 0);
                RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 1.2f, 11.0f, 900L, now, new Vec3(0, 1, 0)));
                RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 0.9f, 9.0f, 850L, now + 90L, new Vec3(0, 1, 0)));
                RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 0.6f, 7.0f, 800L, now + 180L, new Vec3(0, 1, 0)));
                spawnParticleRing(feet, new Vec3(0, 1, 0), 1.5f, PARTICLE_COUNT);
                spawnDustBurst(feet);
            }

            // Sonic entry — 3 mach disks staggered along the look direction.
            if (cur == FlightState.SONIC && prev != FlightState.SONIC) {
                Vec3 look = player.getLookAngle();
                Vec3 center = pos.add(0, player.getEyeHeight() * 0.5, 0);
                Vec3 outward = look.reverse();
                for (int i = 0; i < 3; i++) {
                    Vec3 diskPos = center.add(look.scale(i * 4.0));
                    RINGS.add(new ShockRing(diskPos.x, diskPos.y, diskPos.z, 1.5f, 22.0f, 1200L, now + i * 100L, outward));
                }
                spawnParticleRing(center.subtract(look.scale(1.8)), outward, 1.8f, PARTICLE_COUNT);
                if (mc.player != null) {
                    mc.player.level().playLocalSound(pos.x, pos.y, pos.z,
                            ModSounds.SONIC_BOOM.get(), SoundSource.PLAYERS, 4.0f, 0.8f, false);
                }
            }

            PREV_STATE.put(uuid, cur);
        }

        RINGS.removeIf(r -> now - r.spawnTime > r.lifetimeMs);
    }

    @SubscribeEvent
    public static void onWorldRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (RINGS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        // The AFTER_LEVEL event gives an identity PoseStack and a rotation-only model-view matrix.
        // We draw rings in CAMERA-RELATIVE world coords transformed by that rotation, so the rings
        // stay fixed in the world instead of tracking the camera. ModelViewMat must be identity
        // while the buffer flushes, hence the temporary model-view stack reset (same trick as the
        // original ViltrumiteFlight MachDiskManager).
        Matrix4f view = event.getModelViewMatrix();

        var mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();

        var buf = mc.renderBuffers().bufferSource().getBuffer(ShockwaveRenderTypes.additiveQuad());
        for (ShockRing r : RINGS) {
            if (now < r.spawnTime) continue; // staggered start
            renderRingGeometry(buf, r, view, camPos, camera, now);
        }

        mc.renderBuffers().bufferSource().endBatch(ShockwaveRenderTypes.additiveQuad());

        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** Draw the ring as a bright additive band (inner edge lit, outer edge transparent). */
    private static void renderRingGeometry(VertexConsumer buf, ShockRing ring, Matrix4f view, Vec3 camPos, Camera camera, long now) {
        float progress = Mth.clamp((float) (now - ring.spawnTime) / ring.lifetimeMs, 0f, 1f);
        // Ease-out growth: bursts outward quickly, then glides to full size.
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress);
        float radius = Mth.lerp(eased, ring.startRadius, ring.endRadius);
        float alpha = Math.max(0.0f, 1.0f - progress); // 1 → 0 over the lifetime

        // Ring center in camera-relative space (world position minus camera position).
        double cx = ring.x - camPos.x, cy = ring.y - camPos.y, cz = ring.z - camPos.z;
        float innerR = radius * INNER_SCALE;
        float outerR = radius * OUTER_SCALE;

        // Ring-plane axes. Horizontal rings lie flat; mach disks face the player's look.
        Vec3 right, up;
        boolean horizontal = ring.normal.y > 0.99;
        if (horizontal) {
            right = new Vec3(1, 0, 0);
            up = new Vec3(0, 0, 1);
        } else {
            var lv = camera.getLookVector();
            Vec3 look = new Vec3(lv.x(), lv.y(), lv.z());
            right = cross(look, new Vec3(0, 1, 0));
            double rLen = right.length();
            if (rLen < 0.001) right = new Vec3(1, 0, 0);
            else right = new Vec3(right.x / rLen, right.y / rLen, right.z / rLen);
            up = cross(right, look);
        }

        int coreA = Math.round(alpha * 190);
        for (int i = 0; i < SEGMENTS; i++) {
            double a1 = (i * 2.0 * Math.PI) / SEGMENTS;
            double a2 = ((i + 1) * 2.0 * Math.PI) / SEGMENTS;
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);

            float ix1 = (float) (cx + right.x * c1 * innerR + up.x * s1 * innerR);
            float iy1 = (float) (cy + right.y * c1 * innerR + up.y * s1 * innerR);
            float iz1 = (float) (cz + right.z * c1 * innerR + up.z * s1 * innerR);
            float ix2 = (float) (cx + right.x * c2 * innerR + up.x * s2 * innerR);
            float iy2 = (float) (cy + right.y * c2 * innerR + up.y * s2 * innerR);
            float iz2 = (float) (cz + right.z * c2 * innerR + up.z * s2 * innerR);

            float ox1 = (float) (cx + right.x * c1 * outerR + up.x * s1 * outerR);
            float oy1 = (float) (cy + right.y * c1 * outerR + up.y * s1 * outerR);
            float oz1 = (float) (cz + right.z * c1 * outerR + up.z * s1 * outerR);
            float ox2 = (float) (cx + right.x * c2 * outerR + up.x * s2 * outerR);
            float oy2 = (float) (cy + right.y * c2 * outerR + up.y * s2 * outerR);
            float oz2 = (float) (cz + right.z * c2 * outerR + up.z * s2 * outerR);

            buf.addVertex(view, ix1, iy1, iz1).setColor(RING_R, RING_G, RING_B, coreA);
            buf.addVertex(view, ox1, oy1, oz1).setColor(RING_R, RING_G, RING_B, 0);
            buf.addVertex(view, ox2, oy2, oz2).setColor(RING_R, RING_G, RING_B, 0);
            buf.addVertex(view, ix2, iy2, iz2).setColor(RING_R, RING_G, RING_B, coreA);
        }
    }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }

    private static void spawnParticleRing(Vec3 center, Vec3 normal, float radius, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 up = new Vec3(0, 1, 0);
        if (Math.abs(normal.dot(up)) > 0.99) up = new Vec3(1, 0, 0);
        Vec3 right = cross(normal, up);
        double rLen = right.length();
        right = new Vec3(right.x / rLen, right.y / rLen, right.z / rLen);
        up = cross(right, normal);
        var random = mc.level.random;
        for (int i = 0; i < count; i++) {
            double angle = (i * 2 * Math.PI) / count + random.nextDouble() * 0.3;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double px = center.x + right.x * cos * radius + up.x * sin * radius;
            double py = center.y + right.y * cos * radius + up.y * sin * radius;
            double pz = center.z + right.z * cos * radius + up.z * sin * radius;
            mc.level.addParticle(ParticleTypes.POOF, px, py, pz,
                    (px - center.x) * 0.2 + (random.nextDouble() - 0.5) * 0.08,
                    (py - center.y) * 0.2 + (random.nextDouble() - 0.5) * 0.08,
                    (pz - center.z) * 0.2 + (random.nextDouble() - 0.5) * 0.08);
        }
    }

    /** Dense cloud + flame burst for the takeoff kick. */
    private static void spawnDustBurst(Vec3 center) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var random = mc.level.random;
        for (int i = 0; i < 30; i++) {
            double vx = (random.nextDouble() - 0.5) * 0.5;
            double vz = (random.nextDouble() - 0.5) * 0.5;
            double vy = 0.1 + random.nextDouble() * 0.5;
            mc.level.addParticle(ParticleTypes.CLOUD,
                    center.x + (random.nextDouble() - 0.5) * 1.4,
                    center.y + random.nextDouble() * 0.3,
                    center.z + (random.nextDouble() - 0.5) * 1.4,
                    vx, vy, vz);
        }
    }

    private static class ShockRing {
        final double x, y, z;
        final float startRadius;
        final float endRadius;
        final long lifetimeMs;
        final long spawnTime;
        final Vec3 normal;

        ShockRing(double x, double y, double z, float startRadius, float endRadius,
                  long lifetimeMs, long spawnTime, Vec3 normal) {
            this.x = x; this.y = y; this.z = z;
            this.startRadius = startRadius;
            this.endRadius = endRadius;
            this.lifetimeMs = lifetimeMs;
            this.spawnTime = spawnTime;
            this.normal = normal;
        }
    }
}
