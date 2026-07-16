package me.vexmc.simpleboxer.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A fluent {@link ItemStack} builder for menu icons, written against the
 * common denominator of the whole supported range (1.17.1 → 26.x): legacy
 * {@code §}-coded display strings, {@code List<String>} lore, and the two
 * item flags whose names never moved ({@code HIDE_ENCHANTS},
 * {@code HIDE_ATTRIBUTES}). The decorative "glow" rides a hidden enchant
 * resolved by its stable registry KEY rather than a Bukkit constant — the
 * enchant constants were renamed wholesale in the 1.20.5 registry alignment
 * ({@code DURABILITY} → {@code UNBREAKING}), but {@code minecraft:unbreaking}
 * resolves on every version.
 */
@SuppressWarnings("deprecation") // legacy §-string display API: the cross-version common denominator
public final class Icon {

    /** The glow enchant, resolved once by registry key (see class doc). */
    private static volatile @Nullable Enchantment glintEnchant;
    private static volatile boolean glintResolved;

    private final ItemStack item;
    private final ItemMeta meta;

    private Icon(@NotNull Material material, int amount) {
        this.item = new ItemStack(material, Math.max(1, Math.min(amount, 64)));
        ItemMeta resolved = item.getItemMeta();
        // Air and a handful of unmetadatable materials yield null meta; the
        // caller's chained name/lore calls then no-op rather than throw.
        this.meta = resolved;
    }

    public static @NotNull Icon of(@NotNull Material material) {
        return new Icon(material, 1);
    }

    public static @NotNull Icon of(@NotNull Material material, int amount) {
        return new Icon(material, amount);
    }

    /**
     * A player head wearing {@code owner}'s skin. Falls back to a plain head
     * when skull meta is unavailable (it never is on a real server, but a unit
     * harness without a skin service should not explode).
     */
    public static @NotNull Icon head(@NotNull OfflinePlayer owner) {
        Icon icon = new Icon(Material.PLAYER_HEAD, 1);
        if (icon.meta instanceof SkullMeta skull) {
            try {
                skull.setOwningPlayer(owner);
            } catch (Throwable ignored) {
                // Offline/unresolvable profile — a steve head is fine.
            }
        }
        return icon;
    }

    public @NotNull Icon name(@NotNull String displayName) {
        if (meta != null) {
            meta.setDisplayName(displayName);
        }
        return this;
    }

    public @NotNull Icon lore(@NotNull String... lines) {
        return lore(Arrays.asList(lines));
    }

    public @NotNull Icon lore(@NotNull List<String> lines) {
        if (meta != null) {
            meta.setLore(new ArrayList<>(lines));
        }
        return this;
    }

    /** Appends lines to any lore already set. */
    public @NotNull Icon addLore(@NotNull String... lines) {
        if (meta != null) {
            List<String> current = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            if (current == null) {
                current = new ArrayList<>();
            }
            current.addAll(Arrays.asList(lines));
            meta.setLore(current);
        }
        return this;
    }

    public @NotNull Icon amount(int amount) {
        item.setAmount(Math.max(1, Math.min(amount, 64)));
        return this;
    }

    /** Adds the enchant glint and hides the resulting enchant line. */
    public @NotNull Icon glow() {
        return glow(true);
    }

    public @NotNull Icon glow(boolean glowing) {
        if (meta == null || !glowing) {
            return this;
        }
        Enchantment glint = glintEnchant();
        if (glint != null) {
            meta.addEnchant(glint, 1, true);
            hide(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }

    /** Hides vanilla attribute/enchant tooltips so the lore reads clean. */
    public @NotNull Icon clean() {
        hide(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        return this;
    }

    private void hide(org.bukkit.inventory.ItemFlag... flags) {
        if (meta != null) {
            try {
                meta.addItemFlags(flags);
            } catch (Throwable ignored) {
                // A flag absent on this version is purely cosmetic.
            }
        }
    }

    public @NotNull ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }

    private static @Nullable Enchantment glintEnchant() {
        if (glintResolved) {
            return glintEnchant;
        }
        glintResolved = true;
        Enchantment resolved = byKey("unbreaking");
        if (resolved == null) {
            resolved = byKey("infinity");
        }
        if (resolved == null) {
            // Last resort: any registered enchant glints just as well.
            try {
                Enchantment[] all = Enchantment.values();
                if (all != null && all.length > 0) {
                    resolved = all[0];
                }
            } catch (Throwable ignored) {
                // Leave it null — icons simply will not glow.
            }
        }
        glintEnchant = resolved;
        return resolved;
    }

    private static @Nullable Enchantment byKey(@NotNull String key) {
        try {
            return Enchantment.getByKey(NamespacedKey.minecraft(key));
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /** A blank-named filler pane — the menu border and dead space. */
    public static @NotNull ItemStack filler(@NotNull Material pane) {
        return of(pane).name(" ").build();
    }
}
