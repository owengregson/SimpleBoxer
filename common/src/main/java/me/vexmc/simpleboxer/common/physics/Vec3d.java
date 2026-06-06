package me.vexmc.simpleboxer.common.physics;

/** Immutable double vector — the emulator's motion/displacement currency. */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }
}
