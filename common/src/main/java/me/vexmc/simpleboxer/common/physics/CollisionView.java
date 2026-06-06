package me.vexmc.simpleboxer.common.physics;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The world geometry the client emulator collides against. Core implements
 * this over Bukkit block collision shapes; tests use synthetic floors and
 * walls — the integrator itself never touches a live server type.
 */
public interface CollisionView {

    /** Block collision boxes intersecting {@code region}, world coordinates. */
    @NotNull List<Box> collidingBoxes(@NotNull Box region);

    /**
     * Slipperiness of the block at the given block position — the block
     * UNDER the feet decides ground drag (0.6 default, 0.98 ice, 0.8 slime).
     */
    double slipperiness(int blockX, int blockY, int blockZ);
}
