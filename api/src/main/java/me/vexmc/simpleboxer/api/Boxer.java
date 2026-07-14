package me.vexmc.simpleboxer.api;

import java.util.Optional;
import java.util.UUID;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One live boxer. The underlying entity is a real {@link Player} — present
 * in the player list, targetable by any command or plugin, holding a real
 * inventory — driven by an in-process client brain.
 */
public interface Boxer {

    @NotNull String name();

    @NotNull UUID uuid();

    /** The live Bukkit player. Valid until {@link #remove()}. */
    @NotNull Player player();

    @NotNull BoxerSettings settings();

    /** Atomically swaps the behavior profile; takes effect next brain tick. */
    void retune(@NotNull BoxerSettings settings);

    @NotNull Optional<Player> target();

    /** Sets (or clears) the player this boxer follows and attacks. */
    void setTarget(@Nullable Player target);

    /** The boxer's currently equipped kit (its virtual inventory). */
    @NotNull Loadout loadout();

    /**
     * Equips a kit onto the boxer's real {@link Player#getInventory() inventory}
     * — armor pieces and both hands. Vanilla and custom-enchant effects apply
     * exactly as they would to a player wearing the same items. Takes effect on
     * the boxer's owning thread (next brain tick); re-applied automatically
     * after a respawn so the kit is never lost.
     */
    void equip(@NotNull Loadout loadout);

    boolean paused();

    /** Freezes the brain: no movement, no aim, no clicks — packets still flow. */
    void pause();

    void resume();

    /** A boxer's lifecycle: alive, or dead and waiting for a manual respawn. */
    enum State {
        ALIVE,
        AWAITING_RESPAWN
    }

    /**
     * {@link State#ALIVE} normally; {@link State#AWAITING_RESPAWN} after the boxer
     * died under manual-death survival mode and before {@link #respawn()} is called.
     */
    @NotNull State state();

    /**
     * Respawns a boxer that died under manual-death mode, at its death spot with
     * full health and its kit re-applied. A no-op if the boxer is alive.
     */
    void respawn();

    /** Despawns and unregisters. Idempotent. */
    void remove();
}
