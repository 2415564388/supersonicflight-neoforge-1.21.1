package com.baranhan123.supersonicflight.client.vfx;

import com.baranhan123.supersonicflight.client.ShockwaveRenderTypes;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The heavy punch's shockwave ring.
 *
 * <p>Ported from ViltrumiteCore's {@code PunchVFXManager}. The ring is not a smooth band but a grid
 * of "paint pixels" of decreasing world size, which is what gives the effect its chunky, manga-style
 * look. It is drawn as one quad per lit grid cell, additive-blended, in the plane perpendicular to
 * the punch direction.
 *
 * <p>Drawing goes through {@link ShockwaveRenderTypes#additiveQuad()} on
 * {@link RenderLevelStageEvent.Stage#AFTER_LEVEL} — the same path {@code MachDiskManager} uses —
 * rather than the reference's raw {@code Tesselator}/{@code BufferBuilder} setup, which is written
 * against an older GL state API.
 */
@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PunchVFXManager {

    /** Ring band geometry, in grid cells: lit from just inside the edge outwards. */
    private static final float BAND_WIDTH = 2.0f;
    private static final float BAND_FEATHER = 0.3f;
    /** Ring tint — a hot white-blue, matching the reference. */
    private static final int RING_R = 240;
    private static final int RING_G = 250;
    private static final int RING_B = 255;

    private PunchVFXManager() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Matrix4f view = event.getModelViewMatrix();

        VertexConsumer buffer = null;
        for (Player player : mc.level.players()) {
            if (!(player instanceof SupersonicFlightPlayer core)) continue;

            int punchTicks = core.getPunchTicks();
            if (punchTicks <= 0) continue;

            // The punch animation runs 20 ticks; the shockwave only exists for a slice of it.
            float time = Mth.clamp((20.0f - (punchTicks - partialTick)) / 20.0f, 0.0f, 1.0f);
            if (time < 0.25f || time > 0.65f) continue;

            float progress = (time - 0.25f) / (0.4f / 1.5f);
            if (progress > 1.0f) continue;

            if (buffer == null) {
                buffer = mc.renderBuffers().bufferSource().getBuffer(ShockwaveRenderTypes.additiveQuad());
            }
            drawRing(buffer, view, camPos, player, partialTick, progress);
        }

        if (buffer != null) {
            mc.renderBuffers().bufferSource().endBatch(ShockwaveRenderTypes.additiveQuad());
        }
    }

    private static void drawRing(VertexConsumer buffer, Matrix4f view, Vec3 camPos,
                                 Player player, float partialTick, float progress) {
        float radius = Mth.lerp(progress, 0.5f, 6.5f);
        int alpha = (int) (255.0f * (1.0f - progress));

        // Impact point: mid-body, pushed out along the look direction to the fist.
        double px = Mth.lerp(partialTick, player.xo, player.getX());
        double py = Mth.lerp(partialTick, player.yo, player.getY());
        double pz = Mth.lerp(partialTick, player.zo, player.getZ());
        Vec3 look = player.getViewVector(partialTick);
        Vec3 impactCenter = new Vec3(px, py + player.getEyeHeight(), pz).add(look.scale(1.5));

        // The event's model-view matrix already carries the camera's pitch/yaw rotation, so only
        // the ring's own orientation and the translation to the impact point are left to apply.
        Matrix4f matrix = new Matrix4f(view);
        matrix.translate(
                (float) (impactCenter.x - camPos.x),
                (float) (impactCenter.y - camPos.y),
                (float) (impactCenter.z - camPos.z));
        matrix.rotate(Axis.YP.rotationDegrees(-player.getViewYRot(partialTick)));
        matrix.rotate(Axis.XP.rotationDegrees(-player.getViewXRot(partialTick)));

        // Grid resolution ramps up as the ring expands, so cells shrink and the ring reads as
        // "painterly" rather than as a fixed-resolution sprite being scaled up.
        float gridResolution = Math.max(1.0f, Mth.lerp(progress, 4.0f, 32.0f));
        float pixelWorldSize = radius / gridResolution;
        float outerRadius = gridResolution;
        float innerRadius = Math.max(0.0f, gridResolution - BAND_WIDTH);
        int loopRadius = (int) gridResolution + 1;

        for (int x = 0; x <= loopRadius; x++) {
            for (int y = 0; y <= loopRadius; y++) {
                float dist = (float) Math.sqrt(x * x + y * y);
                if (dist > outerRadius + BAND_FEATHER || dist < innerRadius - BAND_FEATHER) continue;

                drawPaintPixel(matrix, buffer, x, y, pixelWorldSize, alpha);
                if (x != 0) drawPaintPixel(matrix, buffer, -x, y, pixelWorldSize, alpha);
                if (y != 0) drawPaintPixel(matrix, buffer, x, -y, pixelWorldSize, alpha);
                if (x != 0 && y != 0) drawPaintPixel(matrix, buffer, -x, -y, pixelWorldSize, alpha);
            }
        }
    }

    /** One grid cell of the ring, drawn as a flat quad in the ring's local XY plane. */
    private static void drawPaintPixel(Matrix4f matrix, VertexConsumer buffer, int gridX, int gridY,
                                       float pixelScale, int alpha) {
        float cx = gridX * pixelScale;
        float cy = gridY * pixelScale;
        float half = pixelScale * 0.5f;

        buffer.addVertex(matrix, cx - half, cy - half, 0.0f).setColor(RING_R, RING_G, RING_B, alpha);
        buffer.addVertex(matrix, cx + half, cy - half, 0.0f).setColor(RING_R, RING_G, RING_B, alpha);
        buffer.addVertex(matrix, cx + half, cy + half, 0.0f).setColor(RING_R, RING_G, RING_B, alpha);
        buffer.addVertex(matrix, cx - half, cy + half, 0.0f).setColor(RING_R, RING_G, RING_B, alpha);
    }
}
