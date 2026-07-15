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
