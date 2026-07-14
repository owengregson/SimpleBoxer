package me.vexmc.simpleboxer.boxer;

import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;

/**
 * What happens when a mortal boxer dies. Under {@code MANUAL} death it drops its
 * items (if configured) and stays down until {@code Boxer.respawn()} is called;
 * under {@code AUTO_RESPAWN} it pops back up in place — the classic fixture
 * behaviour. Invincible boxers never reach this handler ({@link InvincibilityGuard}
 * keeps them alive), so a boxer here is always mortal.
 */
public final class DeathPolicyGuard implements Listener {

    private final BoxerManager manager;

    public DeathPolicyGuard(@NotNull BoxerManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        manager.byUuidInternal(player.getUniqueId()).ifPresent(boxer -> {
            BoxerSettings.Death death = boxer.settings().death();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            if (death.dropItemsOnDeath()) {
                // Leave the real inventory contents in the world (like a player).
                event.setKeepInventory(false);
            } else {
                event.getDrops().clear();
                event.setKeepInventory(true);
            }

            Location deathSpot = player.getLocation().clone();
            // Auto-respawn if configured — OR if the boxer is nominally invincible
            // but a health-set (setHealth(0), some /kill paths) bypassed the damage
            // cap and killed it anyway: such a boxer must never be left stranded dead.
            boolean autoRespawn = death.mode() == BoxerSettings.Death.Mode.AUTO_RESPAWN
                    || boxer.settings().invincible();
            boxer.markAwaitingRespawn(deathSpot, autoRespawn);
            // MANUAL (and mortal): stay dead; the boxer's keep-alive tick holds the
            // connection until respawn() (the GUI/API) fires.
        });
    }
}
