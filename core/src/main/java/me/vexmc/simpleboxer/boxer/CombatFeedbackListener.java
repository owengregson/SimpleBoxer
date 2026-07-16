package me.vexmc.simpleboxer.boxer;

import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Feeds server-side combat confirmations back into boxer brains: landed hits
 * (the w-tap trigger) and confirmed potion launches (the heal routine's
 * throw-counting truth). Server-side events, not packet guesses — a real
 * player reacts to their hit registering and sees their own pot fly too.
 */
public final class CombatFeedbackListener implements Listener {

    private final BoxerManager manager;

    public CombatFeedbackListener(@NotNull BoxerManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitLanded(EntityDamageByEntityEvent event) {
        if (event.getDamage() <= 0.0 || !(event.getDamager() instanceof Player damager)) {
            return;
        }
        manager.byUuidInternal(damager.getUniqueId()).ifPresent(BoxerImpl::onHitLanded);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionLaunched(ProjectileLaunchEvent event) {
        // A cancelled launch never flew: it must read as a non-throw so the
        // heal routine retries instead of counting a phantom pot.
        if (!(event.getEntity() instanceof ThrownPotion potion)
                || !(potion.getShooter() instanceof Player thrower)) {
            return;
        }
        manager.byUuidInternal(thrower.getUniqueId()).ifPresent(BoxerImpl::onPotLaunched);
    }
}
