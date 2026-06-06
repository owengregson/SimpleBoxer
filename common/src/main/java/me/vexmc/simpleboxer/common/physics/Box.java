package me.vexmc.simpleboxer.common.physics;

/**
 * Axis-aligned box with the sweep primitives vanilla collision needs.
 * Mirrors {@code net.minecraft.world.phys.AABB} semantics where the
 * integrator depends on them (directional expansion, epsilon overlap).
 */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** The standing player box: 0.6 wide, {@code height} tall, feet at (x,y,z). */
    public static Box player(double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        return new Box(x - half, y, z - half, x + half, y + height, z + half);
    }

    public Box offset(double dx, double dy, double dz) {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Vanilla {@code expandTowards}: grow only in the direction of motion. */
    public Box expandTowards(double dx, double dy, double dz) {
        double nMinX = minX + Math.min(0.0, dx);
        double nMinY = minY + Math.min(0.0, dy);
        double nMinZ = minZ + Math.min(0.0, dz);
        double nMaxX = maxX + Math.max(0.0, dx);
        double nMaxY = maxY + Math.max(0.0, dy);
        double nMaxZ = maxZ + Math.max(0.0, dz);
        return new Box(nMinX, nMinY, nMinZ, nMaxX, nMaxY, nMaxZ);
    }

    public boolean intersects(Box other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    double min(int axis) {
        return switch (axis) {
            case 0 -> minX;
            case 1 -> minY;
            default -> minZ;
        };
    }

    double max(int axis) {
        return switch (axis) {
            case 0 -> maxX;
            case 1 -> maxY;
            default -> maxZ;
        };
    }

    /** Overlap on both axes other than {@code axis}, with vanilla's epsilon. */
    boolean overlapsPerpendicular(Box other, int axis, double epsilon) {
        for (int other1 = 0; other1 < 3; other1++) {
            if (other1 == axis) {
                continue;
            }
            if (max(other1) - epsilon <= other.min(other1)
                    || min(other1) + epsilon >= other.max(other1)) {
                return false;
            }
        }
        return true;
    }
}
