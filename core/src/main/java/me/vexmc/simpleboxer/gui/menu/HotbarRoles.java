package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.common.settings.BoxerSettings.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The hotbar's role→slot assignment as pure logic — the single writer of the
 * five {@link Items} slot fields, driven by the {@link HotbarLayoutMenu}.
 * Clicking a slot advances it to the next role in a fixed cycle
 * (weapon → rod → potion → food → block → empty), and roles always SWAP slots
 * rather than duplicate, so every role occupies exactly one slot after every
 * click. That uniqueness is load-bearing: the consumable seeder
 * ({@code BoxerImpl.seedConsumables}) builds a {@code Set.of} over the tool
 * slots — which throws on duplicates — and a pot restock that shared a tool
 * slot would overwrite the tool. The {@link Items} record itself only checks
 * each slot is in [0,8]; the exclusivity lives here, at the one place the
 * assignment is edited.
 */
final class HotbarRoles {

    /** The five jobs a hotbar slot can hold, in the click-cycle order. */
    enum Role { WEAPON, ROD, POTION, FOOD, BLOCK }

    private HotbarRoles() {}

    /**
     * The role assigned to {@code slot}, or null for a free slot. On a
     * duplicate assignment (hand-edited config — the record allows it) the
     * first role in cycle order wins, so the read stays deterministic.
     */
    static @Nullable Role roleAt(@NotNull Items items, int slot) {
        for (Role role : Role.values()) {
            if (slotOf(items, role) == slot) {
                return role;
            }
        }
        return null;
    }

    /** Where {@code role} currently lives (a hotbar slot, 0-8). */
    static int slotOf(@NotNull Items items, @NotNull Role role) {
        return switch (role) {
            case WEAPON -> items.weaponSlot();
            case ROD -> items.rodSlot();
            case POTION -> items.potSlot();
            case FOOD -> items.foodSlot();
            case BLOCK -> items.blockSlot();
        };
    }

    /**
     * One click on hotbar {@code slot}: advance it to the next role in the
     * cycle. An empty slot acquires WEAPON (whose old slot goes free); a slot
     * holding a role swaps places with the next role's current holder; a slot
     * holding BLOCK goes empty, with BLOCK itself moving to the lowest
     * role-free slot — every role always lives somewhere, because the record
     * has no "unassigned". A duplicate produced outside this screen
     * self-heals: the displaced role lands on the lowest free slot instead of
     * stacking.
     */
    static @NotNull Items cycle(@NotNull Items items, int slot) {
        Role current = roleAt(items, slot);
        if (current == Role.BLOCK) {
            // block → empty: the clicked slot goes free; block takes the
            // lowest slot no other role occupies.
            return withSlot(items, Role.BLOCK, lowestFreeSlot(items, Role.BLOCK, slot));
        }
        Role next = current == null ? Role.WEAPON : Role.values()[current.ordinal() + 1];
        int from = slotOf(items, next);
        Items out = withSlot(items, next, slot);
        if (current != null) {
            // The displaced role takes the incoming role's old slot — a swap —
            // unless a pre-existing duplicate put them both here already, in
            // which case it heals onto the lowest free slot.
            out = withSlot(out, current,
                    from != slot ? from : lowestFreeSlot(out, current, slot));
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** The lowest slot 0-8 that is not {@code clicked} and holds no role other than {@code moving}. */
    private static int lowestFreeSlot(@NotNull Items items, @NotNull Role moving, int clicked) {
        for (int candidate = 0; candidate <= 8; candidate++) {
            if (candidate == clicked) {
                continue;
            }
            boolean taken = false;
            for (Role role : Role.values()) {
                if (role != moving && slotOf(items, role) == candidate) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
        // Unreachable: nine slots minus the clicked one minus at most four
        // other roles always leaves at least four candidates.
        throw new IllegalStateException("no free hotbar slot");
    }

    private static @NotNull Items withSlot(@NotNull Items i, @NotNull Role role, int slot) {
        return new Items(i.autoPickup(), i.lockLoadout(),
                role == Role.WEAPON ? slot : i.weaponSlot(),
                role == Role.ROD ? slot : i.rodSlot(),
                role == Role.POTION ? slot : i.potSlot(),
                role == Role.FOOD ? slot : i.foodSlot(),
                role == Role.BLOCK ? slot : i.blockSlot(),
                i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
    }
}
