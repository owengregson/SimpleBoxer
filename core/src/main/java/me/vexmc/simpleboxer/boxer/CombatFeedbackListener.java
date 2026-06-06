package me.vexmc.simpleboxer.boxer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Feeds landed-hit confirmations back into boxer brains — the trigger for
 * the w-tap state machine. Server-side truth (the damage event), not a
 * packet guess: a real w-tapper reacts to their hit registering too.
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
}
