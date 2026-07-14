package me.vexmc.simpleboxer.boxer;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Classifies a held item into the handful of categories the brain's routines
 * reach for — a weapon to combo with, a rod to poke with, a potion to heal with,
 * food to eat, a shield/sword to block with. Matched by material NAME so the
 * table survives every version's {@code Material} set (the same convention
 * {@code BukkitCollisionView} uses for slipperiness).
 */
public enum ItemCategory {
    WEAPON,
    ROD,
    POTION,
    FOOD,
    SHIELD,
    BLOCK,
    OTHER;

    public static @NotNull ItemCategory of(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return OTHER;
        }
        String name = item.getType().name();
        if (name.endsWith("_SWORD") || name.endsWith("_AXE")) {
            return WEAPON;
        }
        if (name.equals("FISHING_ROD")) {
            return ROD;
        }
        if (name.equals("SPLASH_POTION") || name.equals("LINGERING_POTION")) {
            return POTION;
        }
        if (name.equals("SHIELD")) {
            return SHIELD;
        }
        if (isEdible(item)) {
            return FOOD;
        }
        if (isBlock(item)) {
            return BLOCK;
        }
        return OTHER;
    }

    public static boolean is(@Nullable ItemStack item, @NotNull ItemCategory category) {
        return of(item) == category;
    }

    private static boolean isEdible(@NotNull ItemStack item) {
        try {
            return item.getType().isEdible();
        } catch (Throwable pre) {
            return false;
        }
    }

    private static boolean isBlock(@NotNull ItemStack item) {
        try {
            return item.getType().isBlock();
        } catch (Throwable pre) {
            return false;
        }
    }
}
