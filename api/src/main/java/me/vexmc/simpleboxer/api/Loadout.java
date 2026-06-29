package me.vexmc.simpleboxer.api;

import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A boxer's worn kit — its virtual inventory. Six equipment slots (the four
 * armor pieces plus both hands) that the brain applies to the boxer's REAL
 * {@code EntityEquipment}. Because a boxer is a genuine {@code ServerPlayer},
 * whatever the items carry — vanilla armor and weapon attributes, vanilla
 * enchants, and the custom enchants other plugins (StarEnchants and the like)
 * read off equipped gear — applies to the boxer exactly as it would to a real
 * player wearing the same pieces.
 *
 * <p>Immutable and defensively cloned on the way in and out: an {@code
 * ItemStack} handed in can be mutated by its owner afterwards without
 * disturbing the loadout, and a slot read back is always a fresh copy. Empty
 * and {@link Material#AIR AIR} are both normalised to {@code null} so "no
 * item" has one representation.</p>
 */
public final class Loadout {

    /** Nothing equipped. */
    public static final Loadout EMPTY = new Loadout(null, null, null, null, null, null);

    private final @Nullable ItemStack helmet;
    private final @Nullable ItemStack chestplate;
    private final @Nullable ItemStack leggings;
    private final @Nullable ItemStack boots;
    private final @Nullable ItemStack mainHand;
    private final @Nullable ItemStack offHand;

    public Loadout(
            @Nullable ItemStack helmet,
            @Nullable ItemStack chestplate,
            @Nullable ItemStack leggings,
            @Nullable ItemStack boots,
            @Nullable ItemStack mainHand,
            @Nullable ItemStack offHand) {
        this.helmet = normalise(helmet);
        this.chestplate = normalise(chestplate);
        this.leggings = normalise(leggings);
        this.boots = normalise(boots);
        this.mainHand = normalise(mainHand);
        this.offHand = normalise(offHand);
    }

    /** The six addressable equipment slots, ordered head-to-foot then hands. */
    public enum Slot {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS, MAIN_HAND, OFF_HAND
    }

    public @Nullable ItemStack helmet() {
        return clone(helmet);
    }

    public @Nullable ItemStack chestplate() {
        return clone(chestplate);
    }

    public @Nullable ItemStack leggings() {
        return clone(leggings);
    }

    public @Nullable ItemStack boots() {
        return clone(boots);
    }

    public @Nullable ItemStack mainHand() {
        return clone(mainHand);
    }

    public @Nullable ItemStack offHand() {
        return clone(offHand);
    }

    /** The item in a slot by enum, a fresh copy or {@code null}. */
    public @Nullable ItemStack get(@NotNull Slot slot) {
        return switch (slot) {
            case HELMET -> helmet();
            case CHESTPLATE -> chestplate();
            case LEGGINGS -> leggings();
            case BOOTS -> boots();
            case MAIN_HAND -> mainHand();
            case OFF_HAND -> offHand();
        };
    }

    /** A copy of this loadout with one slot replaced. */
    @Contract(pure = true)
    public @NotNull Loadout with(@NotNull Slot slot, @Nullable ItemStack item) {
        return new Loadout(
                slot == Slot.HELMET ? item : helmet,
                slot == Slot.CHESTPLATE ? item : chestplate,
                slot == Slot.LEGGINGS ? item : leggings,
                slot == Slot.BOOTS ? item : boots,
                slot == Slot.MAIN_HAND ? item : mainHand,
                slot == Slot.OFF_HAND ? item : offHand);
    }

    /** True when every slot is empty. */
    public boolean isEmpty() {
        return helmet == null && chestplate == null && leggings == null
                && boots == null && mainHand == null && offHand == null;
    }

    /** How many slots carry an item. */
    public int filledSlots() {
        int count = 0;
        for (Slot slot : Slot.values()) {
            if (rawSlot(slot) != null) {
                count++;
            }
        }
        return count;
    }

    private @Nullable ItemStack rawSlot(@NotNull Slot slot) {
        return switch (slot) {
            case HELMET -> helmet;
            case CHESTPLATE -> chestplate;
            case LEGGINGS -> leggings;
            case BOOTS -> boots;
            case MAIN_HAND -> mainHand;
            case OFF_HAND -> offHand;
        };
    }

    private static @Nullable ItemStack normalise(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }

    @Contract("null -> null; !null -> !null")
    private static @Nullable ItemStack clone(@Nullable ItemStack item) {
        return item == null ? null : item.clone();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Loadout that)) {
            return false;
        }
        return Objects.equals(helmet, that.helmet)
                && Objects.equals(chestplate, that.chestplate)
                && Objects.equals(leggings, that.leggings)
                && Objects.equals(boots, that.boots)
                && Objects.equals(mainHand, that.mainHand)
                && Objects.equals(offHand, that.offHand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(helmet, chestplate, leggings, boots, mainHand, offHand);
    }

    @Override
    public @NotNull String toString() {
        return "Loadout[" + filledSlots() + " slots]";
    }
}
