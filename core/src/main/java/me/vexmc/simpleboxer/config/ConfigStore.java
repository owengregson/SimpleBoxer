package me.vexmc.simpleboxer.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsParser;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One immutable snapshot of the whole configuration, swapped by reference —
 * Mental's atomic-config discipline. A reload parses into a fresh snapshot
 * and publishes it; no code path can read a torn mix.
 */
public final class ConfigStore {

    /** Everything the plugin reads at runtime. */
    public record Snapshot(
            boolean hideFromTab,
            @NotNull BoxerSettings defaults,
            @NotNull Map<String, BoxerSettings> presets) {

        /** Built-ins first, file entries overriding by name. */
        public @Nullable BoxerSettings preset(@NotNull String name) {
            return presets.get(name.toLowerCase(Locale.ROOT));
        }

        public @NotNull Set<String> presetNames() {
            return new TreeSet<>(presets.keySet());
        }
    }

    private final JavaPlugin plugin;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public ConfigStore(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public @NotNull Snapshot snapshot() {
        return current.get();
    }

    /** Re-reads config.yml; warns per malformed key, never fails the swap. */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration file = plugin.getConfig();

        BoxerSettings defaults = BoxerSettingsParser.parse(
                file.getConfigurationSection("defaults"), BoxerSettings.DEFAULTS,
                warning -> plugin.getLogger().warning("config.yml defaults: " + warning));

        Map<String, BoxerSettings> presets = new LinkedHashMap<>();
        DifficultyPresets.all().forEach((name, builtin) ->
                presets.put(name, rebase(builtin, defaults)));
        ConfigurationSection presetSection = file.getConfigurationSection("presets");
        if (presetSection != null) {
            for (String name : presetSection.getKeys(false)) {
                String key = name.toLowerCase(Locale.ROOT);
                presets.put(key, BoxerSettingsParser.parse(
                        presetSection.getConfigurationSection(name), defaults,
                        warning -> plugin.getLogger()
                                .warning("config.yml presets." + name + ": " + warning)));
            }
        }

        current.set(new Snapshot(file.getBoolean("hide-from-tab", true), defaults, presets));
    }

    /**
     * Built-in presets define the LADDER (ping/cps/aim/wtap/movement); the
     * operator's defaults still own the survival policy (invincibility,
     * hunger) unless a preset file entry overrides them explicitly.
     */
    private static BoxerSettings rebase(BoxerSettings builtin, BoxerSettings defaults) {
        return new BoxerSettings(builtin.pingMs(), builtin.cps(), builtin.clickJitter(),
                builtin.aim(), builtin.reach(), builtin.aimToleranceDegrees(), builtin.wtap(),
                builtin.movement(), defaults.invincible(), defaults.feedHunger());
    }
}
