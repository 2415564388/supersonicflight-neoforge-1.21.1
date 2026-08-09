package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class MachDiskManager {

    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("supersonicflight", "textures/entity/white_pixel.png");
    private static final int SEGMENTS = 36;
    // Ring geometry: inner edge at 82% of ring radius, outer at 130% — soft outer glow
    private static final float INNER_SCALE = 0.82f;
    private static final float OUTER_SCALE = 1.30f;
    private static final int PARTICLE_COUNT = 20;

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

        for (AbstractClientPlayer player : mc.level.players()) {
            if (!(player instanceof SupersonicFlightPlayer fp)) continue;
            UUID uuid = player.getUUID();
            FlightState prev = PREV_STATE.getOrDefault(uuid, FlightState.NONE);
            FlightState cur = fp.getFlightState();
            Vec3 pos = player.position();

            if (cur == FlightState.NONE && prev == FlightState.SONIC && player.onGround()) {
                RINGS.add(new ShockRing(pos.x, pos.y + 0.08, pos.z, 1.5f, 1.0f, new Vec3(0, 1, 0)));
                spawnParticleRing(pos.add(0, 0.08, 0), new Vec3(0, 1, 0), 1.5f, PARTICLE_COUNT);
                SonicBoomEffect.triggerImpact();
            }

            if (cur == FlightState.LAUNCH && prev != FlightState.LAUNCH) {
                RINGS.add(new ShockRing(pos.x, pos.y + 0.06, pos.z, 1.2f, 1.0f, new Vec3(0, 1, 0)));
                spawnParticleRing(pos.add(0, 0.06, 0), new Vec3(0, 1, 0), 1.2f, PARTICLE_COUNT);
            }

            if (cur == FlightState.SONIC && prev != FlightState.SONIC) {
                Vec3 look = player.getLookAngle();
                Vec3 ringPos = pos.subtract(look.x * 1.8, look.y * 1.8 - player.getEyeHeight(), look.z * 1.8);
                RINGS.add(new ShockRing(ringPos.x, ringPos.y, ringPos.z, 1.8f, 1.0f, look.reverse()));
                spawnParticleRing(ringPos, look.reverse(), 1.8f, PARTICLE_COUNT);
                if (mc.player != null) {
                    mc.player.level().playLocalSound(pos.x, pos.y, pos.z,
                            ModSounds.SONIC_BOOM.get(), SoundSource.PLAYERS, 4.0f, 0.8f, false);
                }
            }

            PREV_STATE.put(uuid, cur);
        }

        for (ShockRing r : RINGS) { r.life -= 0.028f; r.radius += 0.25f; }
        RINGS.removeIf(r -> r.life <= 0);
    }

    @SubscribeEvent
    public static void onWorldRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (RINGS.isEmpty()) return;

        var ps = event.getPoseStack();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        ps.pushPose();
        ps.translate(-camPos.x, -camPos.y, -camPos.z);

        var buf = Minecraft.getInstance().renderBuffers().bufferSource()
                .getBuffer(RenderType.entityTranslucent(WHITE_TEX));

        for (ShockRing r : RINGS) {
            renderRingGeometry(ps, buf, r, camera);
        }

        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        ps.popPose();
    }

    /** Draw ring as trapezoid segments around the circle — geometry defines the shape, texture is just white */
    private static void renderRingGeometry(PoseStack ps, VertexConsumer buf, ShockRing ring, Camera camera) {
        Matrix4f m = ps.last().pose();
        float alpha = Math.min(1f, ring.life);
        double cx = ring.x, cy = ring.y, cz = ring.z;
        float innerR = ring.radius * INNER_SCALE;
        float outerR = ring.radius * OUTER_SCALE;

        // Determine ring-plane axes (right, up)
        Vec3 right, up;
        boolean horizontal = ring.normal.y > 0.99;
        if (horizontal) {
            right = new Vec3(1, 0, 0);
            up    = new Vec3(0, 0, 1);
        } else {
            var lv = camera.getLookVector();
            Vec3 look = new Vec3(lv.x(), lv.y(), lv.z());
            right = cross(look, new Vec3(0, 1, 0));
            double rLen = right.length();
            if (rLen < 0.001) right = new Vec3(1, 0, 0);
            else right = new Vec3(right.x / rLen, right.y / rLen, right.z / rLen);
            up = cross(right, look);
        }

        // Emit trapezoid quads: each spans (angle_i, angle_next) × (innerR, outerR)
        for (int i = 0; i < SEGMENTS; i++) {
            double a1 = (i * 2.0 * Math.PI) / SEGMENTS;
            double a2 = ((i + 1) * 2.0 * Math.PI) / SEGMENTS;
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);

            // Inner vertices (alpha = full ring opacity — bright core)
            float ix1 = (float)(cx + right.x * c1 * innerR + up.x * s1 * innerR);
            float iy1 = (float)(cy + right.y * c1 * innerR + up.y * s1 * innerR);
            float iz1 = (float)(cz + right.z * c1 * innerR + up.z * s1 * innerR);
            float ix2 = (float)(cx + right.x * c2 * innerR + up.x * s2 * innerR);
            float iy2 = (float)(cy + right.y * c2 * innerR + up.y * s2 * innerR);
            float iz2 = (float)(cz + right.z * c2 * innerR + up.z * s2 * innerR);

            // Outer vertices (alpha = 0 — fully transparent, smooth glow edge)
            float ox1 = (float)(cx + right.x * c1 * outerR + up.x * s1 * outerR);
            float oy1 = (float)(cy + right.y * c1 * outerR + up.y * s1 * outerR);
            float oz1 = (float)(cz + right.z * c1 * outerR + up.z * s1 * outerR);
            float ox2 = (float)(cx + right.x * c2 * outerR + up.x * s2 * outerR);
            float oy2 = (float)(cy + right.y * c2 * outerR + up.y * s2 * outerR);
            float oz2 = (float)(cz + right.z * c2 * outerR + up.z * s2 * outerR);

            int coreA = Math.round(alpha * 200);  // inner: slightly translucent
            int outerA = 0;                        // outer: fully transparent = smooth fade
            float nx = horizontal ? 0 : (float) camera.getLookVector().x();
            float ny = horizontal ? 1 : (float) camera.getLookVector().y();
            float nz = horizontal ? 0 : (float) camera.getLookVector().z();

            // Quad: inner1 → outer1 → outer2 → inner2
            buf.addVertex(m, ix1, iy1, iz1).setColor(255, 255, 255, coreA).setUv(0, 0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(nx, ny, nz);
            buf.addVertex(m, ox1, oy1, oz1).setColor(255, 255, 255, outerA).setUv(0, 0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(nx, ny, nz);
            buf.addVertex(m, ox2, oy2, oz2).setColor(255, 255, 255, outerA).setUv(0, 0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(nx, ny, nz);
            buf.addVertex(m, ix2, iy2, iz2).setColor(255, 255, 255, coreA).setUv(0, 0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(nx, ny, nz);
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

    private static class ShockRing {
        final double x, y, z;
        float radius, life;
        final Vec3 normal;
        ShockRing(double x, double y, double z, float radius, float life, Vec3 normal) {
            this.x = x; this.y = y; this.z = z;
            this.radius = radius; this.life = life;
            this.normal = normal;
        }
    }
}
