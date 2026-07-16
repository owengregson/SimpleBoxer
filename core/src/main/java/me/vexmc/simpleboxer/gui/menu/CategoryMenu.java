package me.vexmc.simpleboxer.gui.menu;

import java.util.List;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every knob in one {@link SettingCategory}, auto-laid-out through the shared
 * {@link PaginatedMenu} — no magic slot numbers, the grid grows to fit whatever
 * {@link SettingsRegistry} declares. Each cell is a generic
 * {@link DescriptorButton} bound to this menu, so a click retunes/persists the
 * {@link SettingsTarget} and re-renders in place. The Items page adds one
 * footer door: the hotbar-layout editor, which owns the role→slot assignment
 * the registry deliberately does not describe as knobs.
 */
final class CategoryMenu extends PaginatedMenu<SettingDescriptor> {

    private final SettingsTarget target;
    private final SettingCategory category;

    CategoryMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull SettingsTarget target,
            @NotNull SettingCategory category) {
        super(gui, parent, "§8" + category.title() + " · " + target.label());
        this.target = target;
        this.category = category;
    }

    @Override
    protected @NotNull List<SettingDescriptor> items() {
        return SettingsRegistry.byCategory(category);
    }

    @Override
    protected @NotNull Button render(@NotNull SettingDescriptor descriptor) {
        return DescriptorButton.build(gui(), this, target, descriptor);
    }

    @Override
    protected @NotNull ItemStack header(int itemCount) {
        return Icon.of(category.icon()).glow()
                .name("§6§l" + category.title())
                .lore(category.summary(),
                        "§8" + itemCount + " setting(s) · scope: §f" + target.label(),
                        target.persistent()
                                ? "§8Saved to config.yml"
                                : "§8Applied to this boxer instantly")
                .build();
    }

    @Override
    protected void footer() {
        // The hotbar's role→slot assignment is one picture, not five integer
        // knobs — it gets its own screen off the Items page.
        if (category == SettingCategory.ITEMS) {
            set(47, Button.of(Icon.of(Material.DIAMOND_SWORD).clean()
                            .name("§b§lHotbar layout")
                            .lore("§7Which hotbar slot carries the weapon,",
                                    "§7rod, potions, food and blocks.",
                                    "",
                                    "§eClick to arrange").build(),
                    click -> new HotbarLayoutMenu(gui(), this, target).open(click.player())));
        }
    }
}
