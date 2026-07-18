package me.vexmc.simpleboxer.common.brain;

/**
 * Vanilla fall-damage arithmetic, verified 1.17 → 26.x (the 1.21 data-driven
 * enchantment migration left {@code CombatRules.getDamageAfterMagicAbsorb}
 * unchanged): {@code damage = ceil((fallDistance − safeFall) × multiplier)}
 * points, then Protection reduces by {@code min(EPF, 20) / 25} (max 80%) —
 * fall damage bypasses armor points and toughness entirely. Safe fall is
 * {@code 3 + jumpBoostLevel} on every supported version (an attribute with a
 * Jump Boost modifier from 1.20.5, a hardcode before — identical observable);
 * Slow Falling zeroes {@code fallDistance} every tick, negating the damage
 * outright. {@code fallDistance} sums from the ARC APEX, which is why a
 * deliberate descent is a walk-off, never a jump-off (+~1.25 phantom blocks).
 *
 * <p>All static, all double/int math — safe on the owning thread and pinned
 * by hand-computed unit tests.</p>
 */
public final class FallDamage {

    /** Enchantment Protection Factor cap ({@code CombatRules}): 4 pieces × cap 20. */
    private static final int EPF_CAP = 20;
    /** {@code reduction = min(epf, 20) / 25} — the 1.9+ magic-absorb divisor. */
    private static final double EPF_DIVISOR = 25.0;

    /**
     * The "no finite bound" sentinel (Slow Falling, or a zeroed damage factor):
     * matches the {@link MoveCosts#MAX_FALL_BLOCKS} table cap so every consumer
     * that clamps to its own band/probe limit stays in precomputed territory.
     */
    public static final int UNBOUNDED_BLOCKS = 256;

    private FallDamage() {}

    /**
     * The combined damage factor a drop's raw points are scaled by:
     * {@code (1 − min(epf, 20)/25) × multiplier}. Feather Falling IV alone is
     * EPF 12 → 0.52; four Prot IV pieces push EPF past the cap → 0.2.
     */
    public static double damageFactor(int epf, double multiplier) {
        return (1.0 - Math.min(epf, EPF_CAP) / EPF_DIVISOR) * multiplier;
    }

    /** As {@link #damageFactor(int, double)}, zeroed outright by Slow Falling. */
    public static double damageFactor(int epf, double multiplier, boolean slowFalling) {
        return slowFalling ? 0.0 : damageFactor(epf, multiplier);
    }

    /**
     * Predicted damage points of a {@code dropBlocks} walk-off, post-reduction:
     * {@code max(0, ceil(drop − safeFall)) × damageFactor}. The multiplier is
     * folded into the factor (exact for the vanilla 1.0; documented
     * approximation otherwise — the budget inverse below uses the SAME factor,
     * so planner pricing and budget agree by construction).
     */
    public static double predictedPoints(double dropBlocks, double safeFall, int epf,
            double multiplier, boolean slowFalling) {
        if (slowFalling) {
            return 0.0;
        }
        return Math.max(0.0, Math.ceil(dropBlocks - safeFall)) * damageFactor(epf, multiplier);
    }

    /**
     * The exact inverse: the deepest whole-block walk-off whose predicted damage
     * fits {@code budgetPoints} — {@code floor(safeFall) + floor(budget/factor)}
     * (the outer floors make the forward ceil safe at every boundary). Worked
     * pins at a 20-max-health half budget (10 points), safe fall 3, ×1:
     * no gear → 3 + floor(10/1.00) = 13; Feather IV (EPF 12) → 3 + floor(10/0.52)
     * = 3 + 19 = 22; Feather IV + Prot IV×4 (EPF 28 → cap 20) → 3 + floor(10/0.2)
     * = 53. Slow Falling (or a zero factor) returns {@link #UNBOUNDED_BLOCKS};
     * callers clamp to their own probe/band cap.
     */
    public static int maxSafeFallBlocks(double budgetPoints, int epf, double safeFall,
            double multiplier, boolean slowFalling) {
        if (slowFalling) {
            return UNBOUNDED_BLOCKS;
        }
        double factor = damageFactor(epf, multiplier);
        if (factor <= 0.0) {
            return UNBOUNDED_BLOCKS;
        }
        return (int) (Math.floor(safeFall) + Math.floor(budgetPoints / factor));
    }
}
