package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Anime-style speed lines that trail the player during SONIC flight and instant takeoff.
 *
 * Ported from the original ViltrumiteCore {@code DashVFXManager}: thin white additive quads
 * that spawn around the player, stretch backwards along the movement direction, and fade out
 * within ~200ms. Rendered on top of the level so they read clearly against any background.
 */
@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class SpeedLineManager {

    private static final long LINE_LIFETIME_MS = 200L;
    private static final int LINES_PER_TICK = 4;
    private static final float LINE_WIDTH = 0.12f;
    private static final int MAX_LINES = 400;

    private static final List<SpeedLine> LINES = new ArrayList<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            LINES.clear();
            return;
        }

        long now = System.currentTimeMillis();
        var random = mc.level.random;

        for (AbstractClientPlayer player : mc.level.players()) {
            if (!(player instanceof SupersonicFlightPlayer fp)) continue;
            FlightState state = fp.getFlightState();
            if (state != FlightState.SONIC && state != FlightState.LAUNCH) continue;

            // Lines trail along the reverse of travel: backward for SONIC, downward for LAUNCH.
            Vec3 dir;
            if (state == FlightState.LAUNCH) {
                dir = new Vec3(0, -1, 0);
            } else {
                Vec3 look = player.getLookAngle();
                dir = new Vec3(-look.x, -look.y, -look.z);
            }

            Vec3 base = player.position();
            for (int i = 0; i < LINES_PER_TICK; i++) {
                double ox = (random.nextDouble() - 0.5) * 4.0;
                double oy = player.getEyeHeight() * (random.nextDouble() * 1.2 - 0.1);
                double oz = (random.nextDouble() - 0.5) * 4.0;
                float length = (float) (3.0 + random.nextDouble() * 6.0);
                LINES.add(new SpeedLine(base.add(ox, oy, oz), dir, now, length));
            }
        }

        LINES.removeIf(l -> now - l.spawnTime > LINE_LIFETIME_MS);
        if (LINES.size() > MAX_LINES) {
            LINES.subList(0, LINES.size() - MAX_LINES).clear();
        }
    }

    @SubscribeEvent
    public static void onWorldRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (LINES.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 camLook = new Vec3(camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
        // AFTER_LEVEL passes an identity PoseStack and a rotation-only model-view matrix. Draw in
        // camera-relative world coords transformed by that rotation so lines stay put in the world.
        Matrix4f view = event.getModelViewMatrix();

        var mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();

        var buf = mc.renderBuffers().bufferSource().getBuffer(ShockwaveRenderTypes.additiveQuad());
        for (SpeedLine line : LINES) {
            long age = now - line.spawnTime;
            if (age < 0 || age > LINE_LIFETIME_MS) continue;
            float progress = (float) age / LINE_LIFETIME_MS;
            int alpha = Math.round(180.0f * (1.0f - progress));
            renderLine(buf, line, view, camPos, camLook, alpha);
        }

        mc.renderBuffers().bufferSource().endBatch(ShockwaveRenderTypes.additiveQuad());

        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** Draw a thin additive strip along the line's direction, facing the camera. */
    private static void renderLine(VertexConsumer buf, SpeedLine line, Matrix4f view, Vec3 camPos, Vec3 camLook, int alpha) {
        Vec3 axis = line.direction.normalize();
        Vec3 right = axis.cross(camLook);
        if (right.lengthSqr() < 1e-6) {
            // Line is parallel to the camera ray — pick any perpendicular that is not parallel too.
            right = new Vec3(1, 0, 0).cross(axis);
            if (right.lengthSqr() < 1e-6) {
                right = new Vec3(0, 1, 0).cross(axis);
                if (right.lengthSqr() < 1e-6) right = new Vec3(0, 0, 1);
            }
        }
        right = right.normalize().scale(LINE_WIDTH);

        // Camera-relative base/tip; the view matrix (camera rotation) rotates them into view space.
        Vec3 base = new Vec3(line.x - camPos.x, line.y - camPos.y, line.z - camPos.z);
        Vec3 tip = base.add(axis.scale(line.length));

        float bx = (float) base.x, by = (float) base.y, bz = (float) base.z;
        float tx = (float) tip.x, ty = (float) tip.y, tz = (float) tip.z;
        float rx = (float) right.x, ry = (float) right.y, rz = (float) right.z;

        buf.addVertex(view, bx + rx, by + ry, bz + rz).setColor(255, 255, 255, alpha);
        buf.addVertex(view, tx + rx, ty + ry, tz + rz).setColor(255, 255, 255, 0);
        buf.addVertex(view, tx - rx, ty - ry, tz - rz).setColor(255, 255, 255, 0);
        buf.addVertex(view, bx - rx, by - ry, bz - rz).setColor(255, 255, 255, alpha);
    }

    private static class SpeedLine {
        final double x, y, z;
        final Vec3 direction;
        final long spawnTime;
        final float length;

        SpeedLine(Vec3 pos, Vec3 direction, long spawnTime, float length) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.direction = direction;
            this.spawnTime = spawnTime;
            this.length = length;
        }
    }
}
