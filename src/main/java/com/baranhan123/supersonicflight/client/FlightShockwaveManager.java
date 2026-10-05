package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.client.vfx.ShockwaveGrid;
import com.baranhan123.supersonicflight.registry.ModSounds;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

/**
 * The shockwave that fires at the flight moments: takeoff, sonic entry, and a real sonic impact.
 *
 * <p>Heavily inspired by the original ViltrumiteFlight {@code MachDiskManager} (and the
 * ViltrumiteCore {@code DashVFXManager}): rather than one small fading ring, several STAGGERED waves
 * burst outward additively and fade over about a second. Waves are time-based (milliseconds) so
 * their motion is frame-rate independent.
 *
 * <p>The geometry is the heavy punch's "paint pixel" grid, shared through
 * {@link ShockwaveGrid} — this class used to draw a radial band instead, and the two looked like
 * different effects. Which wave lands where is unchanged; only what a wave looks like is.
 */
@EventBusSubscriber(modid = "supersonicflight", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class FlightShockwaveManager {

    /**
     * Cells across the ring's radius, and how many of them the lit band spans.
     *
     * <p>Both are FIXED — deliberately not ramped with progress and not derived from the radius.
     *
     * <p>The punch ramps its own count 4→32, which is fine for a 6-block effect: by the time it
     * matters there are enough cells to read as a circle. Copying that ramp here made a wave at
     * RADIUS 1 use only 4–6 cells, and six squares do not make a circle — the first 200–300 ms of
     * every wave rendered as a crooked blob, so the ring only "arrived" once it had grown enough for
     * the grid to resolve it. That is what reads as a delayed big ring appearing after a small one.
     *
     * <p>The horizontal waves get a thicker band on purpose. The third-person camera looks at them
     * at a shallow angle, which foreshortens the band into a hairline — at 3 cells the takeoff ring
     * was effectively invisible while the camera-facing sonic rings looked fine, so a single shared
     * value cannot serve both. Raise these for a thicker line, raise {@code GRID_RESOLUTION} for
     * finer pixels.
     */
    private static final float GRID_RESOLUTION = 64.0f;
    private static final float BAND_CELLS = 5.0f;

    /** Tint — a hot white-blue. */
    private static final int RING_R = 235;
    private static final int RING_G = 248;
    private static final int RING_B = 255;

    private static final List<ShockRing> RINGS = new ArrayList<>();
    private static final Map<UUID, FlightState> PREV_STATE = new HashMap<>();
    /** Per-player time of the last sonic-entry effect, so terrain scraping cannot spam it. */
    private static final Map<UUID, Long> LAST_SONIC_ENTRY_FX = new HashMap<>();
    /** Per-player last seen value of the synced impact countdown, for edge detection. */
    private static final Map<UUID, Integer> PREV_IMPACT_FX = new HashMap<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            RINGS.clear();
            PREV_STATE.clear();
            LAST_SONIC_ENTRY_FX.clear();
            PREV_IMPACT_FX.clear();
            return;
        }

        long now = System.currentTimeMillis();

        for (AbstractClientPlayer player : mc.level.players()) {
            if (!(player instanceof SupersonicFlightPlayer fp)) continue;
            UUID uuid = player.getUUID();
            FlightState prev = PREV_STATE.getOrDefault(uuid, FlightState.NONE);
            FlightState cur = fp.getFlightState();
            Vec3 pos = player.position();

            // A real sonic impact — a big horizontal shockwave burst.
            //
            // Driven by the synced countdown the server sets in PlayerEntityMixin.sonicGroundImpact.
            // This used to key off `cur == NONE && prev == SONIC && player.onGround()`, which fires
            // when the player double-taps space to CANCEL flight while standing and never on an
            // actual impact, because collisions deliberately do not change flight state.
            //
            // `impactFx > prevImpactFx` rather than `> 0 && prev == 0`: it catches the 0 -> N
            // edge as well as a first observation that already arrived mid-countdown, while a plain
            // decrement can never re-trigger.
            int impactFx = fp.getImpactFxTicks();
            int prevImpactFx = PREV_IMPACT_FX.getOrDefault(uuid, 0);
            if (impactFx > 0 && impactFx > prevImpactFx) {
                // No shockwave here on purpose: an impact gets the server's explosion and nothing
                // else. The rings used to spawn at the FEET, in a horizontal plane, and at SONIC
                // speed the player crosses that plane within a tick — so they were invisible at a
                // real collision and only ever showed up on the entry tick, where they read as a
                // second, unrelated ring trailing the sonic-entry burst.
                SonicBoomEffect.triggerImpact();
            }
            PREV_IMPACT_FX.put(uuid, impactFx);

            // Instant takeoff — a stack of 3 concentric horizontal waves at the feet.
            if (cur == FlightState.LAUNCH && prev != FlightState.LAUNCH) {
                spawnTakeoff(player);
            }

            // Sonic entry — 3 waves staggered along the look direction, each perpendicular to it.
            if (cur == FlightState.SONIC && prev != FlightState.SONIC) {
                spawnSonicEntry(player);
            }

            PREV_STATE.put(uuid, cur);
        }

        RINGS.removeIf(r -> now - r.spawnTime > r.lifetimeMs);
    }

    /**
     * Spawns the burst for a state change the server just pushed to us, and advances the edge tracker
     * so the tick loop below does not fire the same transition a second time.
     *
     * <p>Called from {@code FlightStatePingPayload}'s handler rather than from the tick loop, and that
     * is the whole point: the tick loop only looks at the state once per client tick, which quantises
     * the effect by up to 50 ms. Measured on the takeoff path, the state itself arrives 10 ms after
     * the client sends the request, but the burst did not fire until 53 ms — the difference was
     * purely the tick boundary. Spawning from the packet handler puts the shockwave within a
     * millisecond or two of the takeoff explosion, which is what it is supposed to accompany.
     *
     * <p>The tick loop still runs and still handles other players, whose state arrives through the
     * normal entity-data sync. It doubles as a fallback: if this ping never arrives, the edge is
     * still seen there.
     */
    public static void onStatePushed(Player player, FlightState state) {
        UUID uuid = player.getUUID();
        FlightState prev = PREV_STATE.getOrDefault(uuid, FlightState.NONE);
        PREV_STATE.put(uuid, state);
        if (prev == state) return;

        if (state == FlightState.LAUNCH) {
            spawnTakeoff(player);
        } else if (state == FlightState.SONIC) {
            spawnSonicEntry(player);
        }
    }

    // ------------------------------------------------------------------
    // Burst spawners
    // ------------------------------------------------------------------

    /**
     * Three CONCENTRIC horizontal waves at the feet, all born on the same frame, plus the takeoff kick.
     *
     * <p>The waves start small and expand outward — that expansion IS the effect, so the start radii
     * are deliberately near zero rather than a large fraction of the end radii.
     *
     * <p>No {@code spawnParticleRing} here. A ring of POOF particles is laid out at a fixed radius the
     * instant it spawns and never expands, so in front of the three expanding waves it read as a
     * separate, much smaller ring that "appeared but did not spread".
     */
    private static void spawnTakeoff(Player player) {
        long now = System.currentTimeMillis();
        Vec3 feet = player.position().add(0, 0.06, 0);
        RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 1.2f, 11.0f, 900L, now, new Vec3(0, 1, 0)));
        RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 0.9f, 9.0f, 850L, now + 90L, new Vec3(0, 1, 0)));
        RINGS.add(new ShockRing(feet.x, feet.y, feet.z, 0.6f, 7.0f, 800L, now + 180L, new Vec3(0, 1, 0)));
        spawnDustBurst(feet);
    }

    /**
     * 3 waves staggered along the look direction, each perpendicular to it, plus the boom.
     *
     * <p>Rate limited per player: scraping terrain lets the player flip HOVER↔SONIC freely, and a
     * full burst plus a boom on every flip is unreadable — see
     * {@code SonicBoomEffect.SONIC_ENTRY_COOLDOWN_MS}, short enough now that a person cannot
     * out-press it.
     */
    private static void spawnSonicEntry(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long lastEntryFx = LAST_SONIC_ENTRY_FX.get(uuid);
        if (lastEntryFx != null && now - lastEntryFx < SonicBoomEffect.SONIC_ENTRY_COOLDOWN_MS) return;
        LAST_SONIC_ENTRY_FX.put(uuid, now);

        Vec3 pos = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 center = pos.add(0, player.getEyeHeight() * 0.5, 0);
        // A wave's plane is perpendicular to this regardless of its sign, since the grid is radially
        // symmetric and mirrored — so the reversed vector is fine here.
        Vec3 outward = look.reverse();
        // Three waves staggered along the look direction. Note the i=0 wave's plane passes through
        // the player, so in FIRST person the camera sits in that plane and its projection is
        // degenerate — harmless in third person, and left as the original had it.
        // Three waves staggered along the look direction, all growing small to large. Fixed in the
        // world at the spot the player crossed into SONIC, which is where they are meant to stay.
        for (int i = 0; i < 3; i++) {
            Vec3 diskPos = center.add(look.scale(i * 4.0));
            RINGS.add(new ShockRing(diskPos.x, diskPos.y, diskPos.z, 1.5f, 22.0f, 1200L, now + i * 100L, outward));
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.level() != null) {
            mc.player.level().playLocalSound(pos.x, pos.y, pos.z,
                    ModSounds.SONIC_BOOM.get(), SoundSource.PLAYERS, 4.0f, 0.8f, false);
        }
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
        // Waves are placed in CAMERA-RELATIVE world coords and transformed by that rotation, so they
        // stay fixed in the world instead of tracking the camera. ModelViewMat must be identity while
        // the buffer flushes, hence the model-view stack reset.
        Matrix4f view = event.getModelViewMatrix();

        ShockwaveGrid.pushIdentityModelView();
        try {
            VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(ShockwaveRenderTypes.additiveQuad());
            for (ShockRing r : RINGS) {
                if (now < r.spawnTime) continue; // staggered start
                renderWave(buf, r, view, camPos, now);
            }
            mc.renderBuffers().bufferSource().endBatch(ShockwaveRenderTypes.additiveQuad());
        } finally {
            ShockwaveGrid.popModelView();
        }
    }

    private static void renderWave(VertexConsumer buf, ShockRing ring, Matrix4f view, Vec3 camPos, long now) {
        float progress = Mth.clamp((float) (now - ring.spawnTime) / ring.lifetimeMs, 0f, 1f);
        // Ease-out growth: bursts outward quickly, then glides to full size.
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress);
        float radius = Mth.lerp(eased, ring.startRadius, ring.endRadius);

        // Hold full brightness through the expansion and only fade at the very end.
        //
        // This used to fade linearly from the first frame, which meant the ring was already half
        // transparent by the time it was half expanded and nearly gone at three quarters. Additive
        // against the sky, the only clearly visible part of it was therefore the small, early phase:
        // the effect read as a small ring appearing and not spreading, with the actual outward
        // expansion unnoticed until the player slowed down and looked at it.
        float fade = progress < 0.75f ? 1.0f : Math.max(0.0f, (1.0f - progress) / 0.25f);
        int alpha = (int) (255.0f * fade);

        Vec3 center = new Vec3(ring.x - camPos.x, ring.y - camPos.y, ring.z - camPos.z);
        Matrix4f matrix = ShockwaveGrid.facingTransform(view, center, ring.normal);
        ShockwaveGrid.emit(buf, matrix, radius, GRID_RESOLUTION, RING_R, RING_G, RING_B, alpha, BAND_CELLS);
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

    /**
     * One wave, fixed in the world at the spot it was created.
     *
     * <p>Deliberately NOT anchored to the player: the wave marks where they were when they crossed
     * into SONIC or left the ground, and it must stay there. That does mean they fly out of it — at
     * SONIC speed within a tick — so the expansion is mostly seen after they slow down again, which
     * is intended rather than a defect.
     */
    private static class ShockRing {
        final double x, y, z;
        final float startRadius;
        final float endRadius;
        final long lifetimeMs;
        final long spawnTime;
        /** The wave's plane is perpendicular to this. Its sign does not matter. */
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
