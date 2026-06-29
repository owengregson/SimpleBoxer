package me.vexmc.simpleboxer.gui.menu;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.Loadout;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import me.vexmc.simpleboxer.gui.MenuClick;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The virtual-inventory editor — six equipment slots (four armor pieces, both
 * hands) the boxer wears. Whatever you place here, custom-enchanted StarEnchants
 * gear included, equips onto the boxer's real {@code ServerPlayer} and behaves
 * exactly as it would on a player.
 *
 * <p>It never moves your real items. Every click is cancelled and the model is
 * mutated by hand: hold an item and click a slot to <i>copy</i> it in (you keep
 * yours — paint as many boxers as you like from one item in creative);
 * empty-handed click a slot to clear it; shift-click a piece from your own
 * inventory to drop it into the matching slot; or hit "Copy my gear" to mirror
 * what you're wearing. So nothing is ever consumed, duplicated, or lost.</p>
 */
final class LoadoutMenu extends Menu {

    /** Where this editor reads and writes its loadout — a live boxer or a draft. */
    private final Supplier<Loadout> get;
    private final Consumer<Loadout> set;
    private final String label;

    private static final int HELMET = 11;
    private static final int CHEST = 20;
    private static final int LEGS = 29;
    private static final int BOOTS = 38;
    private static final int MAIN_HAND = 15;
    private static final int OFF_HAND = 24;

    private LoadoutMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull String label,
            @NotNull Supplier<Loadout> get, @NotNull Consumer<Loadout> set) {
        super(gui, parent, 6, "§8Loadout · " + label);
        this.label = label;
        this.get = get;
        this.set = set;
    }

    static @NotNull LoadoutMenu forBoxer(@NotNull Gui gui, @Nullable Menu parent,
            @NotNull Boxer boxer) {
        return new LoadoutMenu(gui, parent, boxer.name(), boxer::loadout, boxer::equip);
    }

    static @NotNull LoadoutMenu forDraft(@NotNull Gui gui, @Nullable Menu parent,
            @NotNull SpawnDraft draft) {
        return new LoadoutMenu(gui, parent, "new boxer", draft::loadout, draft::setLoadout);
    }

    @Override
    protected void build() {
        Loadout kit = get.get();

        set(4, Button.display(Icon.of(Material.ARMOR_STAND).glow()
                .name("§6§lLoadout · " + label)
                .lore("§7" + kit.filledSlots() + "/6 slots filled",
                        "§8Hold an item and click a slot to equip it.",
                        "§8Empty-handed click a slot to clear it.",
                        "§8Shift-click your own gear to add it.",
                        "§8Or use §7Copy my gear §8below.").build()));

        slot(HELMET, Loadout.Slot.HELMET, kit, "Helmet");
        slot(CHEST, Loadout.Slot.CHESTPLATE, kit, "Chestplate");
        slot(LEGS, Loadout.Slot.LEGGINGS, kit, "Leggings");
        slot(BOOTS, Loadout.Slot.BOOTS, kit, "Boots");
        slot(MAIN_HAND, Loadout.Slot.MAIN_HAND, kit, "Main hand");
        slot(OFF_HAND, Loadout.Slot.OFF_HAND, kit, "Off hand");

        set(33, Button.of(Icon.of(Material.HOPPER)
                        .name("§e§lCopy my gear")
                        .lore("§7Fill every slot from what §fyou §7are wearing.",
                                "",
                                "§eClick to copy").build(),
                click -> {
                    set.accept(fromPlayer(click.player()));
                    click.player().sendMessage("§aCopied your gear onto the loadout.");
                    click.refresh();
                }));

        set(42, Button.of(Icon.of(Material.LAVA_BUCKET)
                        .name("§c§lClear all")
                        .lore("§7Strip every slot back to empty.",
                                "",
                                "§eClick to clear").build(),
                click -> {
                    set.accept(Loadout.EMPTY);
                    click.refresh();
                }));

        set(45, Button.of(MenuParts.back(), click -> back(click.player())));
        set(53, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }

    private void slot(int index, @NotNull Loadout.Slot role, @NotNull Loadout kit,
            @NotNull String roleName) {
        ItemStack present = kit.get(role);
        if (present != null) {
            // Show the real item; clicking edits the model (handled below).
            set(index, Button.of(present, click -> editSlot(role, click)));
        } else {
            set(index, Button.of(Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                            .name("§7" + roleName + " §8— empty")
                            .lore("§8Hold an item and click to equip it here.").build(),
                    click -> editSlot(role, click)));
        }
    }

    /**
     * Equip-from-held / clear for one slot. A menu cancels every click, so the
     * server-side cursor is always empty inside it — the copy is therefore taken
     * from the item the operator is HOLDING (main hand), which survives the
     * cancel. An empty hand clears the slot. Only plain left/right clicks act,
     * so a stray number-key, drop or hotbar-swap can never wipe a slot.
     */
    private void editSlot(@NotNull Loadout.Slot role, @NotNull MenuClick click) {
        ClickType type = click.click();
        if (type != ClickType.LEFT && type != ClickType.RIGHT) {
            return;
        }
        Loadout kit = get.get();
        ItemStack held = click.player().getInventory().getItemInMainHand();
        if (held != null && held.getType() != Material.AIR) {
            set.accept(kit.with(role, held.clone()));
        } else {
            set.accept(kit.with(role, null));
        }
        refresh();
    }

    @Override
    protected void onBottomClick(@NotNull InventoryClickEvent event) {
        // Shift-click a piece from your own inventory → copy it into its slot.
        if (!event.getClick().isShiftClick()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        Loadout kit = get.get();
        set.accept(kit.with(slotFor(clicked.getType(), kit), clicked.clone()));
        refresh();
    }

    /** Where a shift-clicked item lands: its armor slot, else a free hand. */
    private static @NotNull Loadout.Slot slotFor(@NotNull Material type, @NotNull Loadout kit) {
        String name = type.name().toUpperCase(Locale.ROOT);
        if (name.endsWith("_HELMET") || type == Material.CARVED_PUMPKIN
                || type == Material.PLAYER_HEAD || name.endsWith("_HEAD")
                || name.endsWith("_SKULL")) {
            return Loadout.Slot.HELMET;
        }
        if (name.endsWith("_CHESTPLATE") || type == Material.ELYTRA) {
            return Loadout.Slot.CHESTPLATE;
        }
        if (name.endsWith("_LEGGINGS")) {
            return Loadout.Slot.LEGGINGS;
        }
        if (name.endsWith("_BOOTS")) {
            return Loadout.Slot.BOOTS;
        }
        return kit.mainHand() == null ? Loadout.Slot.MAIN_HAND : Loadout.Slot.OFF_HAND;
    }

    private static @NotNull Loadout fromPlayer(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        return new Loadout(
                inventory.getHelmet(),
                inventory.getChestplate(),
                inventory.getLeggings(),
                inventory.getBoots(),
                inventory.getItemInMainHand(),
                inventory.getItemInOffHand());
    }
}
