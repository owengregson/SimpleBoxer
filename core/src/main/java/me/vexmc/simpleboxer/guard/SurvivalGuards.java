package me.vexmc.simpleboxer.guard;

import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.BoxerService;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps boxers player-identical while making them tireless test fixtures.
 *
 * <p>The invincibility contract is strict: damage events are NEVER
 * cancelled and amounts NEVER altered — cancelling would suppress
 * knockback, altering would corrupt the era difference-rule and immunity
 * windows combat plugins implement. The boxer takes the hit completely
 * (knockback, hurt animation, noDamageTicks, lastDamage) and the health is
 * restored one tick later. A one-shot beyond max health still dies; the
 * death intercept respawns in place immediately.</p>
 */
public final class SurvivalGuards implements Listener {

    private final BoxerService boxers;
    private final Scheduling scheduling;

    public SurvivalGuards(@NotNull BoxerService boxers, @NotNull Scheduling scheduling) {
        this.boxers = boxers;
        this.scheduling = scheduling;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !boxers.isBoxer(player.getUniqueId())) {
            return;
        }
        Boxer boxer = boxers.byUuid(player.getUniqueId()).orElse(null);
        if (boxer == null || !boxer.settings().invincible()) {
            return;
        }
        // After vanilla applies the damage (next tick), top the health back
        // up. The hit itself — knockback, immunity window, lastDamage — has
        // already happened exactly as it would to a real player.
        scheduling.runOn(player, () -> {
            if (player.isOnline() && !player.isDead()) {
                player.setHealth(maxHealth(player));
            }
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!boxers.isBoxer(player.getUniqueId())) {
            return;
        }
        // Boxers never litter the arena or lose their kit.
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);

        Location deathSpot = player.getLocation().clone();
        // runGlobal, not runOn: a DEAD entity fails runOn's validity gate
        // and the respawn would silently never dispatch.
        scheduling.runGlobal(() -> {
            if (!player.isOnline()) {
                return;
            }
            // spigot().respawn() is the API form of the client's respawn
            // button — the same PlayerRespawnEvent path a real player takes.
            player.spigot().respawn();
            scheduling.runGlobal(() -> {
                if (player.isOnline()) {
                    player.teleport(deathSpot);
                    player.setHealth(maxHealth(player));
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !boxers.isBoxer(player.getUniqueId())) {
            return;
        }
        Boxer boxer = boxers.byUuid(player.getUniqueId()).orElse(null);
        if (boxer == null || !boxer.settings().feedHunger()) {
            return;
        }
        // Always full: sprint stays legal, no exhaustion noise, no regen
        // wobble from saturation healing mid-fight (saturation pins too).
        event.setCancelled(true);
        scheduling.runOn(player, () -> {
            if (player.isOnline()) {
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
            }
        }, () -> {});
    }

    @SuppressWarnings("deprecation") // GENERIC_MAX_HEALTH rename across the range
    private static double maxHealth(Player player) {
        for (Attribute attribute : Attribute.values()) {
            if (attribute.name().contains("MAX_HEALTH")) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    return instance.getValue();
                }
            }
        }
        return 20.0;
    }
}
