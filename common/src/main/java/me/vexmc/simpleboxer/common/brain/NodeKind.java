package me.vexmc.simpleboxer.common.brain;

/**
 * How a would-be standing cell relates to the cell a mover is coming from,
 * classified by the local path planner over the collision view:
 *
 * <ul>
 *   <li>{@code STAND} — same level, walkable.</li>
 *   <li>{@code STEP} — up to the vanilla 0.6 step height; auto-stepped, no jump.</li>
 *   <li>{@code JUMP} — a full block up (0.6 &lt; rise ≤ ~1.2); needs a jump with
 *       momentum, which is why the reactive "jump when blocked" never cleared it.</li>
 *   <li>{@code BLOCKED} — a wall too tall to clear, or no floor to land on.</li>
 * </ul>
 */
public enum NodeKind {
    STAND,
    STEP,
    JUMP,
    BLOCKED
}
