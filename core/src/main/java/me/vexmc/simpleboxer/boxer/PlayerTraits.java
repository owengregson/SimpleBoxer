package me.vexmc.simpleboxer.boxer;

import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The boxer's own server-side traits, read the way a real client learns them:
 * the movement-speed attribute (Speed and Slowness potions, armor and plugin
 * modifiers — whatever the server applied), the Jump Boost amplifier, and the
 * fall block — max health, the fall-relevant enchantment protection off the
 * LIVE worn armor, safe-fall distance, the fall-damage multiplier, and Slow
 * Falling. A real client receives all of these via
 * ClientboundUpdateAttributes / UpdateMobEffect / container content packets;
 * the brain snapshots the server truth instead and ages it through the
 * perception line.
 *
 * <p>Every Bukkit constant is resolved reflectively against its era's field
 * name — a direct field reference compiled against the floor API throws
 * {@code NoSuchFieldError} on the ceiling (and vice versa): attributes carried
 * a {@code GENERIC_} prefix until 1.21.2; {@code JUMP} became
 * {@code JUMP_BOOST} at the 1.20.5 registry alignment, where
 * {@code PROTECTION_FALL}/{@code PROTECTION_ENVIRONMENTAL} became
 * {@code FEATHER_FALLING}/{@code PROTECTION}; {@code SAFE_FALL_DISTANCE} and
 * {@code FALL_DAMAGE_MULTIPLIER} do not exist before 1.20.5 at all, so absent
 * attributes fall back to the vanilla hardcodes ({@code 3 + jumpBoostLevel},
 * {@code ×1.0}) — the identical observable on every matrix version.</p>
 */
final class PlayerTraits {

    /** One tick's worth of self-knowledge, aged like any other packet. */
    record Traits(double walkSpeed, int jumpBoostAmplifier, double maxHealth, int fallEpf,
            double safeFallDistance, double fallDamageMultiplier, boolean slowFalling) {}

    private static final @Nullable Attribute MOVEMENT_SPEED =
            resolve(Attribute.class, "MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
    private static final @Nullable Attribute MAX_HEALTH =
            resolve(Attribute.class, "MAX_HEALTH", "GENERIC_MAX_HEALTH");
    private static final @Nullable Attribute SAFE_FALL_DISTANCE =
            resolve(Attribute.class, "SAFE_FALL_DISTANCE", "GENERIC_SAFE_FALL_DISTANCE");
    private static final @Nullable Attribute FALL_DAMAGE_MULTIPLIER =
            resolve(Attribute.class, "FALL_DAMAGE_MULTIPLIER", "GENERIC_FALL_DAMAGE_MULTIPLIER");
    private static final @Nullable PotionEffectType JUMP_BOOST =
            resolve(PotionEffectType.class, "JUMP_BOOST", "JUMP");
    private static final @Nullable PotionEffectType SLOW_FALLING =
            resolve(PotionEffectType.class, "SLOW_FALLING");
    private static final @Nullable Enchantment FEATHER_FALLING =
            resolve(Enchantment.class, "FEATHER_FALLING", "PROTECTION_FALL");
    private static final @Nullable Enchantment PROTECTION =
            resolve(Enchantment.class, "PROTECTION", "PROTECTION_ENVIRONMENTAL");

    private PlayerTraits() {}

    static @NotNull Traits read(@NotNull Player player) {
        double walkSpeed = ClientPhysics.DEFAULT_WALK_SPEED;
        if (MOVEMENT_SPEED != null) {
            AttributeInstance instance = player.getAttribute(MOVEMENT_SPEED);
            if (instance != null) {
                walkSpeed = instance.getValue();
                // The server value includes the sprint modifier whenever the
                // server believes the player sprints (setSprinting toggles
                // the flag and the modifier together). The emulator applies
                // its own ×1.3 from the brain's held sprint key — strip the
                // server's copy; MULTIPLY_TOTAL 0.3 is exactly ×1.3.
                if (player.isSprinting()) {
                    walkSpeed /= ClientPhysics.SPRINT_SPEED_MULTIPLIER;
                }
            }
        }
        int jumpBoost = -1;
        if (JUMP_BOOST != null) {
            PotionEffect effect = player.getPotionEffect(JUMP_BOOST);
            if (effect != null) {
                jumpBoost = effect.getAmplifier();
            }
        }
        double maxHealth = attributeValue(player, MAX_HEALTH, 20.0);
        // Safe fall: the attribute (1.20.5+) already carries Jump Boost's
        // +1/level modifier and any plugin modifiers; before it, vanilla
        // hardcodes 3 + level. Identical observable across the matrix.
        double safeFall = SAFE_FALL_DISTANCE != null
                ? attributeValue(player, SAFE_FALL_DISTANCE, 3.0)
                : 3.0 + (jumpBoost >= 0 ? jumpBoost + 1 : 0);
        double fallMultiplier = attributeValue(player, FALL_DAMAGE_MULTIPLIER, 1.0);
        boolean slowFalling = SLOW_FALLING != null
                && player.getPotionEffect(SLOW_FALLING) != null;
        return new Traits(walkSpeed, jumpBoost, maxHealth, fallEpf(player), safeFall,
                fallMultiplier, slowFalling);
    }

    private static double attributeValue(@NotNull Player player,
            @Nullable Attribute attribute, double fallback) {
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        return instance != null ? instance.getValue() : fallback;
    }

    /**
     * Fall-relevant Enchantment Protection Factor off the LIVE worn armor —
     * 3 per Feather Falling level plus 1 per Protection level per piece, the
     * vanilla {@code getDamageProtection} sum over the armor slots (hand-held
     * protection is unobtainable in survival and deliberately not counted).
     * The kit's {@code Loadout} record is NOT consulted: gear wears and breaks
     * vanilla, and the budget must track what is actually on the body.
     */
    private static int fallEpf(@NotNull Player player) {
        int epf = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null) {
                continue;
            }
            if (FEATHER_FALLING != null) {
                epf += 3 * piece.getEnchantmentLevel(FEATHER_FALLING);
            }
            if (PROTECTION != null) {
                epf += piece.getEnchantmentLevel(PROTECTION);
            }
        }
        return epf;
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable T resolve(@NotNull Class<T> holder, @NotNull String... names) {
        for (String name : names) {
            try {
                return (T) holder.getField(name).get(null);
            } catch (ReflectiveOperationException absent) {
                // The other era's name; try the next.
            }
        }
        return null;
    }
}
