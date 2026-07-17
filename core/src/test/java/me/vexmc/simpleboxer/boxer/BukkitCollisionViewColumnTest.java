package me.vexmc.simpleboxer.boxer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import me.vexmc.simpleboxer.common.brain.NavGeometry;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link CollisionView} contract on the LIVE Bukkit implementation:
 * {@code collidingBoxes} must serve the nav layer's deep-ground column — up to
 * {@code SAFE_DROP_CAP + 1} blocks tall — without truncating its top.
 *
 * <p>The regression this pins: the defensive per-axis clamp (8 blocks) was also
 * applied to Y, keeping only the BOTTOM eight rows of a 17-block ground-scan
 * column. The standing surface sits at the TOP of that column, so over any
 * platform with air beneath (every flat test arena is a floating stone pad in a
 * superflat world) the deep scan found nothing, {@code deepGroundHeight}
 * returned NaN, {@code ledgeAhead} reported a ledge in EVERY direction, and the
 * ledge guard released all movement keys — a fully frozen boxer on every
 * version at once, with zero exceptions to trace.</p>
 *
 * <p>The world is a JDK proxy serving exactly the calls
 * {@link BukkitCollisionView} makes (min/max height, chunk-loaded, block at) —
 * no server, no mocking framework.</p>
 */
class BukkitCollisionViewColumnTest {

    /** The pad the arenas build: stone at y = 80, air everywhere else. */
    private static final int FLOOR_Y = 80;
    /** Feet on the pad. */
    private static final double FEET_Y = FLOOR_Y + 1.0;
    /** The no-gear, 20-max-health pursuit drop budget: 3 + floor(10/1.0). */
    private static final double PURSUIT_BUDGET = 13.0;

    @Test
    void deepScanColumnIsServedUnTruncated() {
        BukkitCollisionView view = new BukkitCollisionView(flatWorld(FLOOR_Y));
        // The exact column deepGroundHeight issues at the hard cap (16 + 1 tall).
        Box column = new Box(40.2, FEET_Y - 16.0 - 1.0, 40.2, 40.8, FEET_Y, 40.8);
        List<Box> boxes = view.collidingBoxes(column);
        assertTrue(boxes.stream().anyMatch(b -> b.maxY() == FEET_Y),
                "the floor top at the COLUMN'S TOP must survive the defensive clamp");
    }

    @Test
    void flatGroundDoesNotReadLedgewardAtThePursuitBudget() {
        BukkitCollisionView view = new BukkitCollisionView(flatWorld(FLOOR_Y));
        Box body = NavGeometry.playerBox(40.5, FEET_Y, 40.5);
        // The exact probe LedgeKeyGuard/ContextSteering issue while pursuing:
        // with the truncating clamp this read TRUE in every direction and froze
        // every movement key.
        assertFalse(NavGeometry.ledgeAhead(view, body, new Vec3d(0.0, 0.0, 1.0),
                NavGeometry.LOOK_AHEAD, PURSUIT_BUDGET),
                "flat ground must not read as a ledge at the pursuit drop budget");
        assertEquals(FEET_Y, NavGeometry.deepGroundHeight(view, 40.5, 40.5, FEET_Y, 16.0), 1.0E-9,
                "the deep scan must find the surface directly below the feet");
    }

    @Test
    void aTrueVoidStillReadsLedgeward() {
        // No floor anywhere: the guard's positive case must survive the fix.
        BukkitCollisionView view = new BukkitCollisionView(flatWorld(Integer.MIN_VALUE));
        Box body = NavGeometry.playerBox(40.5, FEET_Y, 40.5);
        assertTrue(NavGeometry.ledgeAhead(view, body, new Vec3d(0.0, 0.0, 1.0),
                NavGeometry.LOOK_AHEAD, PURSUIT_BUDGET),
                "a genuine bottomless drop must still read ledge-ward");
    }

    /** A world of air with an infinite stone layer at {@code floorY}. */
    private static World flatWorld(int floorY) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMinHeight":
                            return 0;
                        case "getMaxHeight":
                            return 256;
                        case "isChunkLoaded":
                            return true;
                        case "getBlockAt":
                            return block(((Integer) args[1]) == floorY);
                        case "toString":
                            return "FlatProxyWorld";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "unexpected World call: " + method.getName());
                    }
                });
    }

    private static Block block(boolean stone) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class<?>[] {Block.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getType":
                            return stone ? Material.STONE : Material.AIR;
                        case "getCollisionShape":
                            return fullCube();
                        case "toString":
                            return stone ? "stone" : "air";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "unexpected Block call: " + method.getName());
                    }
                });
    }

    private static VoxelShape fullCube() {
        return (VoxelShape) Proxy.newProxyInstance(VoxelShape.class.getClassLoader(),
                new Class<?>[] {VoxelShape.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBoundingBoxes":
                            return List.of(new BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
                        case "toString":
                            return "full cube";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "unexpected VoxelShape call: " + method.getName());
                    }
                });
    }
}
