package me.vexmc.simpleboxer.api;

import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a spawn needs. {@code skinOwner} is the account name whose skin
 * the boxer wears (looked up async, cached; absent = steve/alex by UUID
 * parity). {@code targetName} pre-binds a follow target by player name.
 */
public record BoxerSpawnRequest(
        @NotNull String name,
        @NotNull Location location,
        @NotNull BoxerSettings settings,
        @Nullable String skinOwner,
        @Nullable String targetName) {

    public BoxerSpawnRequest {
        if (name.isBlank() || name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "boxer names are 1-16 chars of [A-Za-z0-9_]: '" + name + "'");
        }
    }
}
