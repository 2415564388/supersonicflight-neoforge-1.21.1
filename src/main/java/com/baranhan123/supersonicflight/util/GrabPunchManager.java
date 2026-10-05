package com.baranhan123.supersonicflight.util;

import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Server-side grab & heavy-punch logic, ported from ViltrumiteCore's
 * {@code PlayerEntityCoreMixin.tickGrab/tickPunch} and {@code PunchImpactManager}.
 *
 * <p>The flow is: right-click a hostile mob while holding the configured item to grab it
 * (the entity is pinned to the player's hand, AI off, dragged through terrain), then
 * right-click or left-click it again to throw a heavy punch that launches it away like a
 * meteor — ploughing through blocks until it hits something hard enough to explode.
 *
 * <p>All per-tick entry points are called from {@code PlayerEntityMixin}'s tick handler.
 */
public final class GrabPunchManager {

    /** Entities launched by a punch, tracked so they keep ploughing through terrain. */
    private static final Map<Entity, MeteorData> LAUNCHED_ENTITIES = new HashMap<>();

    /** Tag used to remember that an entity is (or was) held, so the failsafe mixin can clean up. */
    public static final String GRABBED_TAG = "SupersonicGrabbed";

    /**
     * How far off the crosshair a mob may sit and still be grabbable, as a fraction of
     * {@code grabReach}. Matches the half-extent of the old axis-aligned search cube, so the reach
     * feels unchanged — only the sideways corners of that cube are now excluded.
     */
    private static final double GRAB_CONE_HALF_WIDTH_FACTOR = 0.5;

    /** Cached item lookup so the registry is not hit on every tick / right-click. */
    private static Item cachedGrabItem = null;
    private static String cachedGrabItemId = null;

    private GrabPunchManager() {
    }

    /**
     * True when the player holds the configured grab item in the required hands.
     *
     * <p>Reads the hands directly rather than an event's {@code getHand()}: a two-handed hold makes
     * vanilla fire the interact event once per hand.
     */
    public static boolean isHoldingGrabItem(Player player) {
        Item item = resolveGrabItem();
        if (item == null || item == Items.AIR) return false;

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        if (SupersonicConfig.INSTANCE.requireBothHands) {
            return mainHand.is(item) && offHand.is(item);
        }
        return mainHand.is(item) || offHand.is(item);
    }

    /**
     * How far below the hand the held entity's <b>feet</b> sit — i.e. its body centre is held at the
     * hand.
     *
     * <p>ViltrumiteCore anchored the victim by the neck ({@code height * 0.85}), which for a tall mob
     * held at chest height puts its feet below the player's own feet — it reads as "dropped on the
     * floor". Centring on the body keeps every mob clear of the ground while standing, and still
     * looks right in flight.
     *
     * <p>Shared with the renderer so the drawn model and the server-side hitbox cannot drift apart.
     */
    public static double holdDrop(Entity entity) {
        return entity.getBbHeight() * 0.5;
    }

    private static Item resolveGrabItem() {
        String id = SupersonicConfig.INSTANCE.grabItem;
        if (id == null || id.isBlank()) return null;
        if (id.equals(cachedGrabItemId)) return cachedGrabItem;

        ResourceLocation key = ResourceLocation.tryParse(id);
        cachedGrabItem = key == null ? null : BuiltInRegistries.ITEM.get(key);
        cachedGrabItemId = id;
        return cachedGrabItem;
    }

    // ------------------------------------------------------------------
    // Per-tick entry point
    // ------------------------------------------------------------------

    public static void tick(SupersonicFlightPlayer core, Player player) {
        int punchTicks = core.getPunchTicks();
        if (punchTicks > 0) {
            // Pin the cape and square the body up while the arm swings. The cape fields are
            // client-side render state, so this runs on both sides.
            if (punchTicks > 3) {
                player.xCloak = player.getX();
                player.yCloak = player.getY();
                player.zCloak = player.getZ();
            }
            player.yBodyRot = player.getYRot();
            player.setYHeadRot(player.getYRot());

            if (player.level() instanceof ServerLevel serverLevel) {
                tickPunch(core, player, serverLevel, punchTicks);
            }
        }

        if (player.level() instanceof ServerLevel serverLevel) {
            tickPunchCooldown(core, player);
            tickGrab(core, player, serverLevel);
            tickLaunchedEntities(serverLevel);
        }
    }

    // ------------------------------------------------------------------
    // Grab
    // ------------------------------------------------------------------

    private static void tickGrab(SupersonicFlightPlayer core, Player player, ServerLevel level) {
        if (player.isRemoved() || !player.isAlive()) {
            releaseTarget(core, player);
            return;
        }

        // Losing the power set (or the command being revoked) drops whatever is being held.
        if (!core.isFlightEnabled()) {
            releaseTarget(core, player);
            return;
        }

        LivingEntity target = core.getGrabbedTarget();

        // Acquire phase: look for a fresh target in front of the player.
        if (target == null) {
            if (!core.isTryingToGrab()) return;

            LivingEntity found = findGrabTarget(core, player, level);
            if (found != null) {
                core.setGrabbedTarget(found);
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.IRON_GOLEM_ATTACK, SoundSource.PLAYERS, 1.0f, 0.5f);
                if (found instanceof Mob mob) {
                    mob.setNoAi(true);
                    // Held mobs must not despawn out from under the player mid-flight.
                    mob.setPersistenceRequired();
                }
                found.addTag(GRABBED_TAG);
            }
            // Clear the attempt either way: the search is instantaneous, and leaving the flag set
            // on a miss would strand the player in the grabbing arm pose forever.
            core.setTryingToGrab(false);
            return;
        }

        // Dropping the item drops the victim.
        if (!isHoldingGrabItem(player)) {
            releaseTarget(core, player);
            return;
        }

        // Hold phase: keep the target pinned to the hand.
        if (!target.isAlive() || target.isRemoved()) {
            releaseTarget(core, player);
            return;
        }

        // A grabbed entity stops flying — it is cargo now.
        if (target instanceof SupersonicFlightPlayer targetFlight
                && targetFlight.getFlightState() != FlightState.NONE) {
            targetFlight.stopFlight();
        }

        Vec3 holdPos = resolveHoldPos(core, player);
        // Hold the victim's body centre at the hold point. See holdDrop for why not the neck.
        target.setPos(holdPos.x, holdPos.y - holdDrop(target), holdPos.z);
        // Carry the target along at the player's velocity; this is what makes high-speed
        // flight drag the victim instead of leaving it behind.
        target.setDeltaMovement(player.getDeltaMovement());
        target.hasImpulse = true;
        target.fallDistance = 0.0f;

        // Face the victim back at the player (it is being held up, not running away).
        float faceYaw = player.getYRot() + 180.0f;
        target.setYRot(faceYaw);
        target.setYHeadRot(faceYaw);
        target.yBodyRot = faceYaw;

        if (SupersonicConfig.INSTANCE.grabGrindBlocks) {
            grindBlocks(core, player, target, level);
        }
    }

    /**
     * Picks the grabbable entity the crosshair is actually pointing at, inside a cone in front of
     * the player.
     *
     * <p>This used to return whichever entity {@code getEntities} happened to list first inside an
     * axis-aligned cube. That cube reached a full reach-block out to the side, so right-clicking one
     * mob could grab its neighbour, and with several candidates the choice was arbitrary. The cube is
     * still used to gather candidates cheaply, but the cone and the ray-distance ranking below decide.
     */
    private static LivingEntity findGrabTarget(SupersonicFlightPlayer core, Player player, ServerLevel level) {
        double reach = Math.max(0.5, SupersonicConfig.INSTANCE.grabReach);
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookDir = player.getLookAngle().normalize();
        Vec3 grabCenter = eyePos.add(lookDir.scale(reach * 0.5));
        double half = reach * 0.5;
        AABB grabBox = new AABB(
                grabCenter.x - half, grabCenter.y - half, grabCenter.z - half,
                grabCenter.x + half, grabCenter.y + half, grabCenter.z + half);

        double maxLateral = reach * GRAB_CONE_HALF_WIDTH_FACTOR;
        double maxLateralSq = maxLateral * maxLateral;

        LivingEntity best = null;
        double bestLateralSq = Double.MAX_VALUE;
        double bestDepth = Double.MAX_VALUE;

        for (Entity entity : level.getEntities(player, grabBox)) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            if (living == player) continue;
            // Players are never valid cargo: holding one needs a whole extra sync channel,
            // and the skill is specified as a hostile-mob move.
            if (living instanceof Player) continue;
            if (SupersonicConfig.INSTANCE.grabHostileOnly && !(living instanceof net.minecraft.world.entity.monster.Enemy)) {
                continue;
            }
            if (isGrabbedByAnyone(level, living)) continue;

            // Split the offset to the mob into "along the crosshair" and "off to the side". Only a
            // mob that is genuinely in front, and inside the cone, is a candidate.
            Vec3 toMob = living.getBoundingBox().getCenter().subtract(eyePos);
            double depth = toMob.dot(lookDir);
            if (depth <= 0.0 || depth > reach) continue;

            double lateralSq = Math.max(0.0, toMob.lengthSqr() - depth * depth);
            if (lateralSq > maxLateralSq) continue;

            // Closest to the crosshair wins; depth only breaks a tie between two mobs straddling it.
            if (lateralSq < bestLateralSq || (lateralSq == bestLateralSq && depth < bestDepth)) {
                bestLateralSq = lateralSq;
                bestDepth = depth;
                best = living;
            }
        }
        return best;
    }

    private static boolean isGrabbedByAnyone(ServerLevel level, LivingEntity entity) {
        for (Player other : level.players()) {
            if (other instanceof SupersonicFlightPlayer otherCore && otherCore.getGrabbedTarget() == entity) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the victim is held. The client sends a player-relative offset (see
     * {@code HandPosSyncPayload}) because at SONIC speeds an absolute position would be stale
     * by the time it arrived and the victim would visibly trail behind the hand.
     */
    private static Vec3 resolveHoldPos(SupersonicFlightPlayer core, Player player) {
        Vec3 offset = core.getServerHandPos();
        Vec3 handPos = offset != null
                ? player.position().add(offset)
                // Before the first sync arrives, fall back to a point just in front of the eyes.
                : player.getEyePosition().add(player.getLookAngle().normalize().scale(1.5));
        // 1.0F reproduces getEyePosition()/getLookAngle() exactly. The renderer passes the frame's
        // partial tick to the same helper instead, so its copy stays smooth.
        return centerOnAim(player, handPos, 1.0F);
    }

    /**
     * Pulls {@code point} sideways toward the eye-to-look axis by {@code grabCentering}, without
     * changing how far ahead of the player it sits.
     *
     * <p>0.0 leaves the point exactly on the hand, which is off to the holding side; 1.0 puts it on
     * the crosshair axis. Only the component perpendicular to the look vector is scaled, so the mob
     * keeps its forward distance and rises and falls with the player's view — which is what "in front
     * of the crosshair" means.
     *
     * <p>Shared with {@code GrabbedEntityPositionMixin}, which must apply the identical transform or
     * the drawn model and the server-side hitbox drift apart.
     */
    public static Vec3 centerOnAim(Player player, Vec3 point, float partialTicks) {
        double centering = Mth.clamp(SupersonicConfig.INSTANCE.grabCentering, 0.0, 1.0);
        if (centering <= 0.0) return point;

        Vec3 eye = player.getEyePosition(partialTicks);
        Vec3 look = player.getViewVector(partialTicks).normalize();
        Vec3 toPoint = point.subtract(eye);
        double depth = toPoint.dot(look);
        Vec3 lateral = toPoint.subtract(look.scale(depth));
        return eye.add(look.scale(depth)).add(lateral.scale(1.0 - centering));
    }

    /** Smashes the blocks the held entity is dragged through, hurting it as it grinds. */
    private static void grindBlocks(SupersonicFlightPlayer core, Player player, LivingEntity target, ServerLevel level) {
        AABB targetBox = target.getBoundingBox().inflate(0.1);
        BlockPos minPos = BlockPos.containing(targetBox.minX, targetBox.minY, targetBox.minZ);
        BlockPos maxPos = BlockPos.containing(targetBox.maxX, targetBox.maxY, targetBox.maxZ);

        // Don't grind the floor out from under the player's own feet when standing still.
        double playerFootY = player.getY();
        double lookY = player.getLookAngle().y;
        boolean isOnGround = player.onGround();

        int broken = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int y = minPos.getY(); y <= maxPos.getY(); y++) {
                for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                    if (isOnGround && y <= playerFootY + 0.2 && lookY <= -0.4) continue;
                    mutablePos.set(x, y, z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    float hardness = state.getDestroySpeed(level, mutablePos);
                    if (hardness > 0.0f && hardness <= 50.0f) {
                        level.destroyBlock(mutablePos, true, player);
                        broken++;
                    }
                }
            }
        }

        if (broken > 0) {
            // Once per grinding tick, not per block — scaling with the block count made a single
            // drag through stone instantly lethal.
            target.hurt(trueDamage(level, player), attackDamage(player)
                    * SupersonicConfig.INSTANCE.grabGrindDamageMultiplier);
            if (!target.isAlive()) {
                releaseTarget(core, player);
            }
        }
    }

    /** The player's melee attack damage, the baseline every ability damage value is a multiple of. */
    private static float attackDamage(Player player) {
        return (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    /**
     * True damage source: {@code GENERIC_KILL} sits in the vanilla {@code bypasses_armor} and
     * {@code bypasses_resistance} tags, so these hits ignore armor and resistance entirely.
     */
    private static DamageSource trueDamage(Level level, Player attacker) {
        return level.damageSources().source(DamageTypes.GENERIC_KILL, attacker);
    }

    /** Drops the held entity: restores its AI, clears the tag and the synced target. */
    public static void releaseTarget(SupersonicFlightPlayer core, Player player) {
        LivingEntity target = core.getGrabbedTarget();
        if (target != null) {
            if (target instanceof Mob mob) {
                mob.setNoAi(false);
            }
            target.removeTag(GRABBED_TAG);
            target.setYHeadRot(target.getYRot());
            target.yBodyRot = target.getYRot();
            target.yBodyRotO = target.getYRot();
            target.yHeadRotO = target.getYRot();
            core.setGrabbedTarget(null);
        }
        core.setTryingToGrab(false);
    }

    // ------------------------------------------------------------------
    // Heavy punch
    // ------------------------------------------------------------------

    private static void tickPunchCooldown(SupersonicFlightPlayer core, Player player) {
        if (player.level().isClientSide()) return;
        int cooldown = core.getPunchCooldown();
        if (cooldown > 0) {
            core.setPunchCooldown(cooldown - 1);
        }
    }

    /** Server-side punch countdown. The impact lands on tick 15; the client just reads the synced value. */
    private static void tickPunch(SupersonicFlightPlayer core, Player player, ServerLevel serverLevel, int currentTicks) {
        if (currentTicks == 15) {
            executePunch(core, (ServerPlayer) player, serverLevel);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.PUNCH_IMPACT.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.5f, 1.5f);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 0.6f);
        }

        core.setPunchTicks(currentTicks - 1);
    }

    /**
     * The punch itself: everything inside a forward cone (plus a guaranteed zone right in
     * front of the fist) takes heavy damage and is launched; grabbed targets are always hit.
     * Terrain in the same cone is shattered.
     */
    public static void executePunch(SupersonicFlightPlayer core, ServerPlayer player, ServerLevel world) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 direction = player.getLookAngle().normalize();
        Vec3 apex = eyePos.add(direction.scale(2.0));

        float punchStrength = core.getPunchStrength();
        LivingEntity grabbedTarget = core.getGrabbedTarget();
        // The punch throws the victim, so the grab has to end here. Leaving it held would let
        // tickGrab (which runs later in the same tick) re-pin the victim to the hand and undo
        // the launch entirely.
        if (grabbedTarget != null) {
            releaseTarget(core, player);
        }

        float baseDamage = SupersonicConfig.INSTANCE.punchBaseDamage;
        float finalDamage = baseDamage * 2.0f + punchStrength * baseDamage * 3.0f;
        float launchForce = SupersonicConfig.INSTANCE.punchLaunchForce + punchStrength * 5.5f;

        double scale = 1.0 + punchStrength * 1.5;
        double rOut = 9.0 * scale;
        double rBase = 9.0 * scale;
        double rIn = 1.0 * scale;
        double minCosTheta = rOut / Math.sqrt(rOut * rOut + rBase * rBase);

        AABB hitBox = new AABB(
                apex.x - rBase, apex.y - rBase, apex.z - rBase,
                apex.x + rBase, apex.y + rBase, apex.z + rBase);

        boolean grabbedWasHit = false;
        List<Entity> entities = world.getEntities(player, hitBox);
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity livingTarget)) continue;
            if (livingTarget == player) continue;

            if (!inPunchVolume(livingTarget.getBoundingBox().getCenter(), apex, direction,
                    rIn, rOut, minCosTheta, scale)) {
                continue;
            }

            if (livingTarget == grabbedTarget) {
                grabbedWasHit = true;
            }
            launch(livingTarget, direction, launchForce, finalDamage, player);
        }

        // The held victim is always launched, even if the cone maths missed it.
        if (grabbedTarget != null && !grabbedWasHit) {
            launch(grabbedTarget, direction, launchForce, finalDamage, player);
        }

        if (SupersonicConfig.INSTANCE.punchBreakBlocks) {
            // The *hit* volume scales with flight throttle, but the terrain removed must not: the
            // raw cone at full throttle is 22 blocks long (~12k blocks), roughly 45x the
            // destructionRadius preset. Clamp it and scale the whole cone down proportionally so
            // the shape stays the same, just shorter.
            double terrainRadius = Math.min(rOut, Math.max(1.0, SupersonicConfig.INSTANCE.punchDestructionRadius));
            double ratio = terrainRadius / rOut;
            shatterTerrain(world, apex, direction, rIn * ratio, terrainRadius, minCosTheta, scale * ratio, player);
        }
    }

    private static void launch(LivingEntity target, Vec3 direction, float launchForce,
                               float damage, ServerPlayer player) {
        if (target instanceof SupersonicFlightPlayer flightTarget
                && flightTarget.getFlightState() != FlightState.NONE) {
            flightTarget.stopFlight();
        }
        target.hurt(player.damageSources().playerAttack(player), damage);

        Vec3 launchVelocity = direction.scale(launchForce).add(0.0, 0.5, 0.0);
        target.setDeltaMovement(launchVelocity);
        target.hasImpulse = true;
        LAUNCHED_ENTITIES.put(target, new MeteorData(launchVelocity, 30, player));
    }

    /** True when {@code point} falls inside the punch cone (or the close-range guaranteed zone). */
    private static boolean inPunchVolume(Vec3 point, Vec3 apex, Vec3 direction,
                                         double rIn, double rOut, double minCosTheta, double scale) {
        Vec3 toPoint = point.subtract(apex);
        double d = toPoint.length();
        if (d < 0.0001) return true;

        double h = toPoint.dot(direction);
        double distToAxisSq = toPoint.lengthSqr() - h * h;

        boolean inMainShape = d >= rIn && d <= rOut && h / d >= minCosTheta;
        boolean inGuaranteedZone = h > 0.0 && h <= 3.5 * scale && distToAxisSq <= 2.25 * scale * scale;
        return inMainShape || inGuaranteedZone;
    }

    private static void shatterTerrain(ServerLevel world, Vec3 apex, Vec3 direction,
                                       double rIn, double rOut, double minCosTheta, double scale,
                                       ServerPlayer breaker) {
        int searchRad = (int) Math.ceil(rOut);
        BlockPos min = BlockPos.containing(apex.x - searchRad, apex.y - searchRad, apex.z - searchRad);
        BlockPos max = BlockPos.containing(apex.x + searchRad, apex.y + searchRad, apex.z + searchRad);

        float dropChance = SupersonicConfig.INSTANCE.punchBlockDropChance / 100.0f;
        boolean brokeAny = false;
        int broken = 0;

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            Vec3 blockVector = pos.getCenter().subtract(apex);
            double dSq = blockVector.lengthSqr();
            if (dSq < 0.01 || dSq > rOut * rOut) continue;

            double d = Math.sqrt(dSq);
            if (d < 0.1 || d > rOut) continue;

            double h = blockVector.dot(direction);
            double cosTheta = h / d;
            double distToAxisSq = dSq - h * h;
            boolean inMainShape = d >= rIn && cosTheta >= minCosTheta;
            boolean inTunnel = d < 5.0 * scale && distToAxisSq <= 2.5 * scale * scale && h > 0.0;
            if (!inMainShape && !inTunnel) continue;

            if (TerrainDestruction.shatterBlock(world, pos, dropChance, breaker, true)) {
                brokeAny = true;
                if (++broken >= TerrainDestruction.MAX_BLOCKS_PER_CRATER) break;
            }
        }

        if (brokeAny) {
            world.playSound(null, BlockPos.containing(apex), SoundEvents.STONE_BREAK,
                    SoundSource.BLOCKS, 2.0f, 0.5f);
        }
    }

    // ------------------------------------------------------------------
    // Launched ("meteor") physics
    // ------------------------------------------------------------------

    /**
     * Drives punched entities through terrain. Each tick the entity's travel volume is scanned;
     * a few blocks get smashed and the entity slows a little, but once it chews through more
     * blocks than its momentum allows (or meets something unbreakable) it detonates.
     */
    public static void tickLaunchedEntities(ServerLevel world) {
        if (LAUNCHED_ENTITIES.isEmpty()) return;

        float vaporizeChance = SupersonicConfig.INSTANCE.punchBlockDropChance / 100.0f;
        Iterator<Map.Entry<Entity, MeteorData>> it = LAUNCHED_ENTITIES.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<Entity, MeteorData> entry = it.next();
            Entity entity = entry.getKey();
            MeteorData data = entry.getValue();
            Vec3 velocity = data.velocity;
            double speed = velocity.length();

            if (entity instanceof Player) {
                data.ticksLeft--;
                if (data.ticksLeft <= 0) {
                    it.remove();
                    continue;
                }
            }

            // The tracker is global but the physics scan is per-level. Leave entities from other
            // dimensions alone (not remove them) — their own level's tick will drive them.
            if (entity.level() != world) {
                continue;
            }

            if (entity.isRemoved() || !world.isLoaded(entity.blockPosition()) || speed < 0.5) {
                it.remove();
                continue;
            }

            AABB travelBox = entity.getBoundingBox().expandTowards(velocity).inflate(0.2);
            int minX = Mth.floor(travelBox.minX);
            int minY = Mth.floor(travelBox.minY);
            int minZ = Mth.floor(travelBox.minZ);
            int maxX = Mth.floor(travelBox.maxX);
            int maxY = Mth.floor(travelBox.maxY);
            int maxZ = Mth.floor(travelBox.maxZ);

            int blocksBroken = 0;
            boolean hitUnbreakable = false;

            if (SupersonicConfig.INSTANCE.launchBreakBlocks) {
                BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            mutablePos.set(x, y, z);
                            BlockState state = world.getBlockState(mutablePos);
                            if (state.isAir() || !state.getFluidState().isEmpty()) continue;

                            float hardness = state.getDestroySpeed(world, mutablePos);
                            if (hardness >= 0.0f && hardness < SupersonicConfig.INSTANCE.destructionMaxHardness) {
                                boolean drop = world.random.nextFloat() >= vaporizeChance;
                                world.destroyBlock(mutablePos, drop);
                                blocksBroken++;
                            } else if (hardness < 0.0f) {
                                hitUnbreakable = true;
                            }
                        }
                    }
                }
            } else {
                // Ploughing disabled: the entity still stops against terrain, it just does not eat
                // through it. Detect the block it ran into so the explosion still triggers.
                hitUnbreakable = !world.getBlockState(entity.blockPosition()).isAir();
            }

            // How many blocks this entity can plough through scales with its momentum and
            // cross-section; falling entities get a bonus so a downward slam digs deep.
            double crossSectionArea = entity.getBbWidth() * entity.getBbHeight();
            double baseThreshold = speed * Math.max(4.0, Math.min(crossSectionArea * 3.0, 32.0));
            double downwardRatio = velocity.y < -0.5 ? Math.abs(velocity.y) / speed : 0.0;
            double dynamicThreshold = baseThreshold * (1.0 - downwardRatio * 0.6);

            if (hitUnbreakable || blocksBroken > dynamicThreshold) {
                // Power comes from config, not from the meteor's speed. Deriving it from speed made
                // a fast punch (velocity ~10, i.e. 2.5x TNT) blast a crater many times the size of
                // destructionRadius, with no way to tune it.
                float explosionPower = SupersonicConfig.INSTANCE.launchExplosionPower;
                // Honour the terrain toggle: with ploughing off, the blast should not carve a
                // crater either, or the switch would be misleading.
                Level.ExplosionInteraction interaction = SupersonicConfig.INSTANCE.launchBreakBlocks
                        ? Level.ExplosionInteraction.BLOCK
                        : Level.ExplosionInteraction.NONE;
                world.explode(entity, entity.getX(), entity.getY(), entity.getZ(),
                        explosionPower, interaction);
                hurtFromMeteor(world, entity, data, SupersonicConfig.INSTANCE.launchExplosionDamageMultiplier);
                it.remove();
            } else if (blocksBroken > 0) {
                // Only on the tick the meteor first bites into terrain, so grinding through a hill
                // does not tick damage every single tick.
                if (!data.braking) {
                    hurtFromMeteor(world, entity, data, SupersonicConfig.INSTANCE.launchImpactDamageMultiplier);
                }
                data.braking = true;

                double brakeForce = velocity.y < -0.5 ? 0.7 : 0.85;
                Vec3 newVelocity = velocity.scale(brakeForce);
                entity.setDeltaMovement(newVelocity);
                entity.hasImpulse = true;
                data.velocity = newVelocity;
            } else {
                data.braking = false;
                Vec3 newVelocity = new Vec3(velocity.x * 0.98, velocity.y * 0.98 - 0.08, velocity.z * 0.98);
                entity.setDeltaMovement(newVelocity);
                entity.hasImpulse = true;
                data.velocity = newVelocity;
            }
        }
    }

    /** Applies a launched entity's collision damage, as a multiple of the thrower's attack damage. */
    private static void hurtFromMeteor(ServerLevel world, Entity entity, MeteorData data, float multiplier) {
        if (data.owner == null || multiplier <= 0.0f) return;
        entity.hurt(trueDamage(world, data.owner), attackDamage(data.owner) * multiplier);
    }

    private static final class MeteorData {
        Vec3 velocity;
        int ticksLeft;
        /** Whoever threw the punch — their attack damage scales every hit this meteor lands. */
        final ServerPlayer owner;
        /** Tracks terrain contact so the impact damage fires once per collision, not every tick. */
        boolean braking;

        MeteorData(Vec3 velocity, int ticksLeft, ServerPlayer owner) {
            this.velocity = velocity;
            this.ticksLeft = ticksLeft;
            this.owner = owner;
        }
    }
}
