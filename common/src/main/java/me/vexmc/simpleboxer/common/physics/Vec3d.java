package me.vexmc.simpleboxer.common.physics;

/** Immutable double vector — the emulator's motion/displacement currency. */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public Vec3d scale(double factor) {
        return new Vec3d(x * factor, y * factor, z * factor);
    }

    public Vec3d withY(double newY) {
        return new Vec3d(x, newY, z);
    }

    public double dot(Vec3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Unit vector, or {@link #ZERO} if this is (near) zero-length. */
    public Vec3d normalized() {
        double len = length();
        return len < 1.0E-8 ? ZERO : new Vec3d(x / len, y / len, z / len);
    }

    /** Unit vector in the XZ plane (y forced to 0), or {@link #ZERO} if flat-zero. */
    public Vec3d horizontalNormalized() {
        double len = horizontalLength();
        return len < 1.0E-8 ? ZERO : new Vec3d(x / len, 0.0, z / len);
    }
}
