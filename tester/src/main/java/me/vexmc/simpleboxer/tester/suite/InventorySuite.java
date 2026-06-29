package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import java.util.concurrent.TimeUnit;
import me.vexmc.simpleboxer.SimpleBoxerPlugin;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.BoxerSpawnRequest;
import me.vexmc.simpleboxer.api.Loadout;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.menu.MainMenu;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The virtual-inventory feature, proven on a live server: a kit equipped
 * through the API lands on the boxer's real {@code ServerPlayer} (the held
 * weapon, the worn armor, and the armor attribute the gear confers), survives
 * a death-and-respawn, and the menu icons build on whatever version is booting
 * — the one thing that varies most across 1.17.1 → 26.x.
 */
public final class InventorySuite {

    private InventorySuite() {}

    public static @NotNull List<TestCase> tests(@NotNull SimpleBoxerPlugin plugin) {
        return List.of(
                new TestCase("inventory: API equip dresses the boxer and grants armor", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 160, 130));
                    Boxer boxer = Arenas.spawn("Kitted", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        double bareArmor = context.sync(() -> armor(boxer.player()));

                        Loadout kit = new Loadout(
                                new ItemStack(Material.DIAMOND_HELMET),
                                new ItemStack(Material.DIAMOND_CHESTPLATE),
                                new ItemStack(Material.IRON_LEGGINGS),
                                new ItemStack(Material.IRON_BOOTS),
                                new ItemStack(Material.DIAMOND_SWORD),
                                new ItemStack(Material.SHIELD));
                        context.syncRun(() -> boxer.equip(kit));

                        // Applied on the boxer's owning thread (next brain tick).
                        context.awaitUntil(() -> {
                            try {
                                ItemStack helmet = boxer.player().getInventory().getHelmet();
                                return helmet != null && helmet.getType() == Material.DIAMOND_HELMET;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 20, "the kit to apply to the real inventory");

                        context.syncRun(() -> {
                            var inventory = boxer.player().getInventory();
                            context.expect(inventory.getChestplate() != null
                                    && inventory.getChestplate().getType() == Material.DIAMOND_CHESTPLATE,
                                    "chestplate equipped");
                            context.expect(inventory.getLeggings() != null
                                    && inventory.getLeggings().getType() == Material.IRON_LEGGINGS,
                                    "leggings equipped");
                            context.expect(inventory.getBoots() != null
                                    && inventory.getBoots().getType() == Material.IRON_BOOTS,
                                    "boots equipped");
                            context.expect(inventory.getItemInMainHand().getType() == Material.DIAMOND_SWORD,
                                    "sword in main hand");
                            context.expect(inventory.getItemInOffHand().getType() == Material.SHIELD,
                                    "shield in off hand");
                        });

                        context.expect(boxer.loadout().filledSlots() == 6,
                                "the boxer reports all six slots filled");

                        double dressedArmor = context.sync(() -> armor(boxer.player()));
                        context.expect(dressedArmor > bareArmor,
                                "armor attribute rose with the gear ("
                                        + bareArmor + " → " + dressedArmor + ")");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("inventory: a boxer spawns already wearing its kit", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 190, 130));
                    Loadout kit = new Loadout(new ItemStack(Material.NETHERITE_HELMET),
                            null, null, null, new ItemStack(Material.IRON_AXE), null);
                    Boxer boxer = plugin.boxers().spawn(new BoxerSpawnRequest(
                                    "BornArmed", center, DifficultyPresets.DUMMY, null, null, kit))
                            .get(10, TimeUnit.SECONDS);
                    try {
                        context.awaitUntil(() -> {
                            try {
                                ItemStack helmet = boxer.player().getInventory().getHelmet();
                                return helmet != null
                                        && helmet.getType() == Material.NETHERITE_HELMET;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 30, "the spawn-time kit to be worn");
                        context.expect(context.sync(() -> boxer.player().getInventory()
                                        .getItemInMainHand().getType()) == Material.IRON_AXE,
                                "spawn-time weapon in hand");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("inventory: the kit survives a death and respawn", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 220, 130));
                    Boxer boxer = Arenas.spawn("Phoenix", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> boxer.equip(new Loadout(
                                new ItemStack(Material.DIAMOND_HELMET), null, null, null,
                                new ItemStack(Material.DIAMOND_SWORD), null)));
                        context.awaitUntil(() -> worn(boxer, Material.DIAMOND_HELMET),
                                20, "the kit to apply before the kill");

                        // A one-shot drives the death → in-place respawn intercept.
                        context.syncRun(() -> boxer.player().damage(1000.0));
                        context.awaitUntil(() -> {
                            try {
                                return !boxer.player().isDead()
                                        && boxer.player().getHealth() > 19.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 60, "the boxer to respawn");

                        context.awaitUntil(() -> worn(boxer, Material.DIAMOND_HELMET),
                                40, "the kit to be re-applied after respawn");
                        context.expect(context.sync(() -> boxer.player().getInventory()
                                        .getItemInMainHand().getType()) == Material.DIAMOND_SWORD,
                                "the weapon is back in hand after respawn");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("gui: menus build with valid cross-version icons", context -> {
                    context.syncRun(() -> {
                        // The root hub: exercises createInventory(title) and a
                        // dozen Icon builds on whatever version is running.
                        MainMenu main = new MainMenu(plugin.gui(), null);
                        main.refresh();
                        context.expect(main.getInventory().getSize() == 45,
                                "main menu is five rows");
                        context.expect(main.getInventory().getItem(4) != null,
                                "main menu header rendered");
                        context.expect(main.getInventory().getItem(20) != null,
                                "main menu spawn button rendered");

                        // Every material the deeper menus reference must exist on
                        // this version (a removed constant would throw here).
                        for (Material material : PALETTE) {
                            ItemStack icon = Icon.of(material).glow().clean()
                                    .name("§atest").lore("§7a", "§7b").build();
                            context.expect(icon != null && icon.getType() == material,
                                    "icon builds for " + material);
                        }
                    });
                }));
    }

    /** Materials used across the settings/loadout/list menus — the version-risky set. */
    private static final Material[] PALETTE = {
        Material.NETHER_STAR, Material.ARMOR_STAND, Material.PLAYER_HEAD, Material.ENCHANTED_BOOK,
        Material.WRITABLE_BOOK, Material.COMPARATOR, Material.BARRIER, Material.ARROW,
        Material.SPECTRAL_ARROW, Material.PAPER, Material.NAME_TAG, Material.COMPASS,
        Material.CLOCK, Material.LEVER, Material.SUGAR, Material.FISHING_ROD, Material.SPYGLASS,
        Material.ENDER_EYE, Material.FEATHER, Material.REPEATER, Material.REDSTONE,
        Material.LEATHER_BOOTS, Material.TARGET, Material.RABBIT_FOOT, Material.TOTEM_OF_UNDYING,
        Material.COOKED_BEEF, Material.IRON_CHESTPLATE, Material.HOPPER, Material.LAVA_BUCKET,
        Material.ENDER_PEARL, Material.LEAD, Material.LIME_CONCRETE, Material.RED_CONCRETE,
        Material.LIME_DYE, Material.GRAY_DYE, Material.BLACK_STAINED_GLASS_PANE,
        Material.LIGHT_GRAY_STAINED_GLASS_PANE,
    };

    private static boolean worn(@NotNull Boxer boxer, @NotNull Material material) {
        try {
            ItemStack helmet = boxer.player().getInventory().getHelmet();
            return helmet != null && helmet.getType() == material;
        } catch (Throwable gone) {
            return false;
        }
    }

    @SuppressWarnings("deprecation") // GENERIC_ARMOR rename across the range
    private static double armor(@NotNull Player player) {
        for (Attribute attribute : Attribute.values()) {
            if (attribute.name().equals("ARMOR") || attribute.name().equals("GENERIC_ARMOR")) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    return instance.getValue();
                }
            }
        }
        return 0.0;
    }
}
