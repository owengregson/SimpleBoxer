package me.vexmc.simpleboxer.nms;

import org.jetbrains.annotations.Nullable;

/**
 * The clientbound packets a boxer's brain reacts to, decoded into plain
 * data. Everything else the server writes (chunks, sounds, entity moves) is
 * captured and dropped — the brain perceives the world through server-side
 * snapshots, not by reconstructing it from packets.
 */
public sealed interface Inbound {

    /** {@code ClientboundSetEntityMotionPacket} — REPLACES client motion. */
    record Velocity(int entityId, double vx, double vy, double vz) implements Inbound {}

    /**
     * {@code ClientboundPlayerPositionPacket} — a teleport the client must
     * confirm. Relative axes add to current position; absolute axes set it
     * and (legacy semantics) zero that axis' velocity. Modern packets may
     * carry an explicit velocity change instead.
     */
    record PositionSync(
            int teleportId,
            double x, double y, double z,
            float yaw, float pitch,
            boolean relativeX, boolean relativeY, boolean relativeZ,
            boolean relativeYaw, boolean relativePitch,
            @Nullable Motion velocity) implements Inbound {

        public record Motion(double x, double y, double z,
                boolean deltaX, boolean deltaY, boolean deltaZ) {}
    }

    /** {@code ClientboundExplodePacket} — knockback ADDS to client motion. */
    record Explosion(double kx, double ky, double kz) implements Inbound {}
}
