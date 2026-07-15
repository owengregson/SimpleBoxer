package me.vexmc.simpleboxer.boxer;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The live world as the client emulator collides with it. Owning-thread
 * only — the brain tick is the single caller. Slipperiness is matched by
 * material NAME so the table survives every version's Material set
 * (Mental's GroundFriction convention, decompile-cited there).
 */
final class BukkitCollisionView implements CollisionView {

    /** Queries are clamped to this many blocks per axis — a runaway motion
     * vector must not iterate half the world. */
    private static final int MAX_EXTENT = 8;

    private final World world;

    BukkitCollisionView(@NotNull World world) {
        this.world = world;
    }

    @Override
    public @NotNull List<Box> collidingBoxes(@NotNull Box region) {
        int minX = floor(region.minX()) - 1;
        int minY = floor(region.minY()) - 1;
        int minZ = floor(region.minZ()) - 1;
        int maxX = floor(region.maxX()) + 1;
        int maxY = floor(region.maxY()) + 1;
        int maxZ = floor(region.maxZ()) + 1;
        maxX = Math.min(maxX, minX + MAX_EXTENT);
        maxY = Math.min(maxY, minY + MAX_EXTENT);
        maxZ = Math.min(maxZ, minZ + MAX_EXTENT);

        List<Box> boxes = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // A cell we cannot read on this region's thread (cross-region /
                    // unloaded chunk near a seam, or outside the buildable column) is
                    // treated as a SOLID cube — the same conservative "unreadable = wall"
                    // convention the nav layer uses. Skipping it instead (as this did)
                    // would silently drop a floor/wall the server actually enforces, so
                    // the sim would fall through a surface the server holds and get
                    // "moved-wrongly"-corrected back up into wall glue.
                    if (!isReadable(x, y, z)) {
                        Box cube = new Box(x, y, z, x + 1, y + 1, z + 1);
                        if (cube.intersects(region)) {
                            boxes.add(cube);
                        }
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    for (BoundingBox bound : block.getCollisionShape().getBoundingBoxes()) {
                        Box candidate = new Box(
                                x + bound.getMinX(), y + bound.getMinY(), z + bound.getMinZ(),
                                x + bound.getMaxX(), y + bound.getMaxY(), z + bound.getMaxZ());
                        if (candidate.intersects(region)) {
                            boxes.add(candidate);
                        }
                    }
                }
            }
        }
        return boxes;
    }

    @Override
    public boolean isReadable(int blockX, int blockY, int blockZ) {
        // Folia/region safety: only a cell whose chunk is loaded ON THIS region's
        // thread may be read. World#isChunkLoaded is a cheap state query that never
        // pulls a chunk in from another region — a chunk owned by another region (or
        // simply not loaded) is not loaded from here, so it reads as false. That is the
        // conservative direction the planner wants: unreadable = no edge = a wall, so
        // the search stops at the loaded/region frontier instead of probing a block a
        // cross-region getBlockAt would have to service. A cell outside the buildable
        // Y column is likewise unreadable so a fall/head probe can't run off the world.
        if (blockY < world.getMinHeight() || blockY >= world.getMaxHeight()) {
            return false;
        }
        return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    @Override
    public double slipperiness(int blockX, int blockY, int blockZ) {
        Material material = world.getBlockAt(blockX, blockY, blockZ).getType();
        return switch (material.name()) {
            case "ICE", "PACKED_ICE", "FROSTED_ICE" -> 0.98;
            case "BLUE_ICE" -> 0.989;
            case "SLIME_BLOCK" -> 0.8;
            default -> 0.6;
        };
    }

    @Override
    public @Nullable Vec3d stuckMultiplier(int blockX, int blockY, int blockZ) {
        // Matched by material NAME (survives every version's Material set),
        // mirroring the vanilla Entity.makeStuckInBlock stamps: a cobweb has
        // no collision box, so the integrator only sees it through this.
        Material material = world.getBlockAt(blockX, blockY, blockZ).getType();
        return switch (material.name()) {
            case "COBWEB", "WEB" -> new Vec3d(0.25, 0.05, 0.25);
            case "SWEET_BERRY_BUSH" -> new Vec3d(0.8, 0.75, 0.8);
            default -> null;
        };
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
