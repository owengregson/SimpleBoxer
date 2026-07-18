package me.vexmc.simpleboxer.common.physics;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The world geometry the client emulator collides against. Core implements
 * this over Bukkit block collision shapes; tests use synthetic floors and
 * walls — the integrator itself never touches a live server type.
 */
public interface CollisionView {

    /**
     * The tallest column, in block rows, an implementation must serve
     * FAITHFULLY from {@link #collidingBoxes}: the nav layer's deep ground scan
     * ({@code NavGeometry.deepGroundHeight}) issues a single column
     * {@code dropBudget + 1} blocks tall — the budget is hard-capped at
     * {@code Brain.SAFE_DROP_CAP} (16) — and a block-grid implementation walks
     * one border row past each end, so 16 + 1 + 2 = 19 rows must survive any
     * defensive clamp (pinned by {@code BrainTest}). An implementation MAY
     * clamp a runaway (pathological) region beyond this, but clamping below it
     * silently amputates the TOP of a legitimate ground-scan column — the very
     * surface the boxer stands on — so over any platform with air beneath the
     * scan reads "no ground within budget", every direction reads ledge-ward,
     * and the ledge guard releases every movement key: a fully frozen boxer
     * with zero exceptions to trace.
     */
    int DEEP_SCAN_COLUMN_BLOCKS = 24;

    /** Block collision boxes intersecting {@code region}, world coordinates. */
    @NotNull List<Box> collidingBoxes(@NotNull Box region);

    /**
     * Slipperiness of the block at the given block position — the block
     * UNDER the feet decides ground drag (0.6 default, 0.98 ice, 0.8 slime).
     */
    double slipperiness(int blockX, int blockY, int blockZ);

    /**
     * The vanilla "stuck in block" per-axis motion multiplier for the block at
     * the given position (cobweb → {@code (0.25, 0.05, 0.25)}, sweet berry bush
     * → {@code (0.8, 0.75, 0.8)}), or {@code null} when the block imposes none.
     * The integrator multiplies its motion componentwise by this before the
     * move/collide, mirroring {@code Entity.makeStuckInBlock}. Synthetic views
     * that model no stuck blocks inherit the {@code null} default.
     */
    default @Nullable Vec3d stuckMultiplier(int blockX, int blockY, int blockZ) {
        return null;
    }

    /**
     * Whether the block cell at {@code (blockX, blockY, blockZ)} can be read on the
     * <em>current</em> thread without crossing a region/loaded-chunk boundary — the
     * Folia seam the server-side pathfinder gates every neighbour cell through
     * (footprint + head + fall column). An unreadable cell is treated as a wall (no
     * edge), so the search halts cleanly at the loaded/region frontier and never pulls
     * a chunk in from another region.
     *
     * <p>Synthetic/test views have their whole geometry in memory, so the default is
     * {@code true} (everything is readable); only the live Bukkit view overrides this
     * to return {@code false} outside the readable set.</p>
     */
    default boolean isReadable(int blockX, int blockY, int blockZ) {
        return true;
    }
}
