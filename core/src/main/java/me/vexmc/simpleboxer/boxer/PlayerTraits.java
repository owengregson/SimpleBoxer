package me.vexmc.simpleboxer.boxer;

import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The boxer's own server-side movement traits, read the way a real client
 * learns them: the movement-speed attribute (Speed and Slowness potions,
 * armor and plugin modifiers — whatever the server applied) and the Jump
 * Boost amplifier. A real client receives these via
 * ClientboundUpdateAttributes / UpdateMobEffect; the brain snapshots the
 * server truth instead and ages it through the perception line.
 *
 * <p>Both Bukkit constants are resolved reflectively: the attribute was
 * {@code GENERIC_MOVEMENT_SPEED} until 1.21.2 and {@code MOVEMENT_SPEED}
 * after, the effect {@code JUMP} until the 1.20.5 registry alignment and
 * {@code JUMP_BOOST} after — a direct field reference compiled against the
 * floor API throws {@code NoSuchFieldError} on the ceiling.</p>
 */
final class PlayerTraits {

    /** One tick's worth of self-knowledge, aged like any other packet. */
    record Traits(double walkSpeed, int jumpBoostAmplifier) {}

    private static final @Nullable Attribute MOVEMENT_SPEED =
            resolve(Attribute.class, "MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
    private static final @Nullable PotionEffectType JUMP_BOOST =
            resolve(PotionEffectType.class, "JUMP_BOOST", "JUMP");

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
        return new Traits(walkSpeed, jumpBoost);
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
