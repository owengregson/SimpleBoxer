package me.vexmc.simpleboxer.common.brain;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import org.jetbrains.annotations.NotNull;

/**
 * A synthetic {@link CollisionView} for brain/navigation unit tests: an optional
 * infinite floor plus hand-placed full-block cubes and arbitrary boxes. Mirrors
 * the {@code FlatWorld} approach {@code ClientPhysicsTest} uses, so nav logic is
 * exercised over the same geometry the integrator would collide with — no server.
 */
public final class FakeWorld implements CollisionView {

    private final List<Box> boxes = new ArrayList<>();
    private final boolean floor;
    private final double floorTop;

    private FakeWorld(boolean floor, double floorTop) {
        this.floor = floor;
        this.floorTop = floorTop;
    }

    /** An infinite floor whose surface is at y = {@code floorTop}. */
    public static FakeWorld floorAt(double floorTop) {
        return new FakeWorld(true, floorTop);
    }

    /** No floor at all (a void). */
    public static FakeWorld empty() {
        return new FakeWorld(false, 0.0);
    }

    /** Add a unit cube occupying block (bx,by,bz). */
    public FakeWorld block(int bx, int by, int bz) {
        boxes.add(new Box(bx, by, bz, bx + 1, by + 1, bz + 1));
        return this;
    }

    /** Add a solid wall of full blocks spanning x in [x0,x1], z in [z0,z1], y in [y0,y1]. */
    public FakeWorld wall(int x0, int y0, int z0, int x1, int y1, int z1) {
        boxes.add(new Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1));
        return this;
    }

    /** Add an arbitrary axis-aligned collision box (world coordinates). */
    public FakeWorld box(Box box) {
        boxes.add(box);
        return this;
    }

    @Override
    public @NotNull List<Box> collidingBoxes(@NotNull Box region) {
        List<Box> hits = new ArrayList<>();
        if (floor) {
            Box floorBox = new Box(region.minX() - 1, floorTop - 1.0, region.minZ() - 1,
                    region.maxX() + 1, floorTop, region.maxZ() + 1);
            if (floorBox.intersects(region)) {
                hits.add(floorBox);
            }
        }
        for (Box box : boxes) {
            if (box.intersects(region)) {
                hits.add(box);
            }
        }
        return hits;
    }

    @Override
    public double slipperiness(int blockX, int blockY, int blockZ) {
        return 0.6;
    }
}
