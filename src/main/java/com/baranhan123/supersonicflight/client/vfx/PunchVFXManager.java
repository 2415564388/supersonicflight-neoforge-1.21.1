package com.baranhan123.supersonicflight.client.vfx;

import com.baranhan123.supersonicflight.client.ShockwaveRenderTypes;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
 * look. The geometry itself lives in {@link ShockwaveGrid}, because the flight moments
 * ({@code FlightShockwaveManager}) draw the same thing.
 *
 * <p>Drawing goes through {@link ShockwaveRenderTypes#additiveQuad()} on
 * {@link RenderLevelStageEvent.Stage#AFTER_LEVEL} — the same path everything else in-world uses —
 * rather than the reference's raw {@code Tesselator}/{@code BufferBuilder} setup, which is written
 * against an older GL state API.
 */
@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PunchVFXManager {

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
            //
            // Do NOT "fix" the divisor below. `progress` is meant to run 0→1 across the intended
            // [0.25, 0.65] window, but 0.4f / 1.5f makes it hit 1.0 at time ≈ 0.5167 instead — so
            // the `time > 0.65f` bound is dead code and the ring finishes expanding after ~8 ticks
            // rather than ~13. That shorter, snappier expansion is the look this effect is liked
            // for; correcting the arithmetic changes the effect.
            float time = Mth.clamp((20.0f - (punchTicks - partialTick)) / 20.0f, 0.0f, 1.0f);
            if (time < 0.25f || time > 0.65f) continue;

            float progress = (time - 0.25f) / (0.4f / 1.5f);
            if (progress > 1.0f) continue;

            if (buffer == null) {
                buffer = mc.renderBuffers().bufferSource().getBuffer(ShockwaveRenderTypes.additiveQuad());
            }

            float radius = Mth.lerp(progress, 0.5f, 6.5f);
            int alpha = (int) (255.0f * (1.0f - progress));

            // Impact point: eye level, pushed out along the look direction to the fist.
            double px = Mth.lerp(partialTick, player.xo, player.getX());
            double py = Mth.lerp(partialTick, player.yo, player.getY());
            double pz = Mth.lerp(partialTick, player.zo, player.getZ());
            Vec3 look = player.getViewVector(partialTick);
            Vec3 impactCenter = new Vec3(px, py + player.getEyeHeight(), pz).add(look.scale(1.5));

            // The ring faces the player's view, built exactly the way the flight waves build theirs:
            // the plane is perpendicular to `look`, so it pitches with the camera instead of staying
            // level.
            //
            // This replaced a hand-rolled `Ry(-yaw) · Rx(-pitch)` composition whose world normal came
            // out as (-sinY, cosY·sinP, cosY·cosP) against a look of
            // (-sinY·cosP, -sinP, cosY·cosP) — the pitch was sign-flipped, so the ring only actually
            // faced the view when the player was level, and tilted the wrong way otherwise.
            Matrix4f matrix = ShockwaveGrid.facingTransform(view, impactCenter.subtract(camPos), look);

            // Grid resolution ramps up as the ring expands, so cells shrink and the ring reads as
            // "painterly" rather than as a fixed-resolution sprite being scaled up.
            float gridResolution = Math.max(1.0f, Mth.lerp(progress, 4.0f, 32.0f));
            ShockwaveGrid.emit(buffer, matrix, radius, gridResolution, RING_R, RING_G, RING_B, alpha);
        }

        if (buffer == null) return;

        // Deliberately flushed WITHOUT touching the model-view stack, unlike FlightShockwaveManager.
        // That asymmetry is the original behaviour and this effect's tilt depends on it: the two
        // managers bake the camera rotation into their vertices by different routes, and "unifying"
        // them by adding the identity push here visibly changes the punch's ring. Leave it alone.
        mc.renderBuffers().bufferSource().endBatch(ShockwaveRenderTypes.additiveQuad());
    }
}
