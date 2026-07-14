package me.vexmc.simpleboxer.boxer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Gates item pickup. A boxer is a real survival {@code ServerPlayer}, so item
 * entities offer themselves to it server-side without any packet — but that
 * would let a fixture vacuum up arena litter it should ignore. Pickup is
 * therefore off unless {@code items.auto-pickup} is set; when it is, the item
 * enters the boxer's real inventory and the brain can select and use it.
 */
public final class PickupListener implements Listener {

    private final BoxerManager manager;

    public PickupListener(@NotNull BoxerManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        manager.byUuidInternal(player.getUniqueId()).ifPresent(boxer -> {
            if (!boxer.settings().items().autoPickup()) {
                event.setCancelled(true);
            }
        });
    }
}
