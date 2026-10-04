package com.baranhan123.supersonicflight.util;

import com.baranhan123.supersonicflight.config.SupersonicConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's single terrain-destruction rule, shared by takeoff, sonic impacts and the heavy punch.
 *
 * <p>Before this existed, takeoff and impact popped only the topmost block of each column and flung
 * it as a falling-block entity, while the punch dug out a shaped volume with particles and drop
 * rolls. Those read as two different mods. Everything now goes through {@link #shatterBlock}, so
 * the hardness gate, the drop chance and the debris particles behave identically everywhere.
 */
public final class TerrainDestruction {

    /** Hardness of obsidian, used as the documented reference point for the config default. */
    public static final float OBSIDIAN_HARDNESS = 50.0f;

    /**
     * Upper bound on blocks removed by a single crater.
     *
     * <p>A crater is a hemisphere, so the block count grows with the cube of the radius: radius 8
     * (the default) is ~1000 blocks, but radius 50 would be half a million in a single tick and
     * would lock the server. This is a backstop against a mis-set {@code destructionRadius}.
     */
    public static final int MAX_BLOCKS_PER_CRATER = 4096;

    /**
     * Fraction of destroyed blocks that emit a debris burst.
     *
     * <p>A crater is a thousand blocks; a full burst on each would be >10k particles in one tick.
     * The punch keeps emitting on every block it breaks — this only throttles the flight craters.
     */
    private static final float CRATER_PARTICLE_FRACTION = 0.25f;

    private TerrainDestruction() {
    }

    /** True when this block is something the abilities are allowed to destroy. */
    public static boolean isBreakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        // Never chew through fluids; it just deletes oceans and looks wrong.
        if (!state.getFluidState().isEmpty()) return false;

        float hardness = state.getDestroySpeed(level, pos);
        // Negative hardness is unbreakable (bedrock, barrier, portal frame).
        return hardness >= 0.0f && hardness < SupersonicConfig.INSTANCE.destructionMaxHardness;
    }

    /**
     * Destroys a single block with the shared rules.
     *
     * @param dropChance 0..1 probability that the block also drops its item
     * @param breaker    player to attribute the break to (may be null)
     * @return true if a block was actually destroyed
     */
    public static boolean shatterBlock(ServerLevel level, BlockPos pos, float dropChance,
                                       @Nullable Entity breaker, boolean spawnParticles) {
        BlockState state = level.getBlockState(pos);
        if (!isBreakable(level, pos, state)) return false;

        if (level.random.nextFloat() < dropChance) {
            Block.dropResources(state, level, pos);
        }

        // setBlock rather than destroyBlock: destroyBlock runs its own drop logic, which would
        // either duplicate the rolled drop or force drops we deliberately suppressed.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        if (spawnParticles) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    12, 0.3, 0.3, 0.3, 0.05);
        }
        return true;
    }

    /**
     * Digs a crater for takeoff and sonic impacts: a sphere centred on the impact point, clipped
     * vertically so it hollows out one side only.
     *
     * <p>The clip matters because the impact direction differs. Hitting the ground carves a bowl
     * <em>downward</em> (clipped just above the feet, so it does not also delete the air the player
     * is standing in); ramming a ceiling carves a dome <em>upward</em> (clipped just below the head,
     * so it does not eat the floor far beneath). A symmetric sphere would do both and look wrong.
     *
     * @param minY lowest block centre to destroy, inclusive
     * @param maxY highest block centre to destroy, inclusive
     * @return the number of blocks destroyed
     */
    public static int shatterCrater(ServerLevel level, Vec3 center, double radius,
                                    double minY, double maxY,
                                    float dropChance, @Nullable Entity breaker) {
        if (radius <= 0.0) return 0;

        int searchRadius = (int) Math.ceil(radius);
        int yLo = (int) Math.floor(Math.max(center.y - searchRadius, minY));
        int yHi = (int) Math.floor(Math.min(center.y + searchRadius, maxY));
        if (yHi < yLo) return 0;

        BlockPos min = BlockPos.containing(center.x - searchRadius, yLo, center.z - searchRadius);
        BlockPos max = BlockPos.containing(center.x + searchRadius, yHi, center.z + searchRadius);

        double radiusSq = radius * radius;
        int broken = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    // Test from the block centre so the crater is a clean bowl, not a jagged cube.
                    double dx = x + 0.5 - center.x;
                    double dy = y + 0.5 - center.y;
                    double dz = z + 0.5 - center.z;
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;

                    mutablePos.set(x, y, z);
                    boolean emitParticles = level.random.nextFloat() < CRATER_PARTICLE_FRACTION;
                    if (shatterBlock(level, mutablePos, dropChance, breaker, emitParticles)) {
                        broken++;
                        if (broken >= MAX_BLOCKS_PER_CRATER) return broken;
                    }
                }
            }
        }
        return broken;
    }

    /** Config drop chance, as a 0..1 fraction. */
    public static float dropChanceFraction(float percent) {
        return percent / 100.0f;
    }
}
