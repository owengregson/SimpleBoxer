package me.vexmc.simpleboxer.boxer;

import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Hunger policy. By default a boxer's food is pinned full — sprint stays legal
 * and no exhaustion noise leaks into a fight. When {@code hunger.natural} is set,
 * vanilla exhaustion runs so the boxer actually gets hungry and the brain's eat
 * routine has an honest trigger.
 */
public final class HungerGuard implements Listener {

    private final BoxerManager manager;
    private final Scheduling scheduling;

    public HungerGuard(@NotNull BoxerManager manager, @NotNull Scheduling scheduling) {
        this.manager = manager;
        this.scheduling = scheduling;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        manager.byUuidInternal(player.getUniqueId()).ifPresent(boxer -> {
            BoxerSettings settings = boxer.settings();
            if (settings.feedHunger() && !settings.hunger().natural()) {
                event.setCancelled(true);
                scheduling.runOn(player, () -> {
                    if (player.isOnline()) {
                        player.setFoodLevel(20);
                        player.setSaturation(20.0f);
                    }
                }, () -> {});
            }
            // natural hunger: let vanilla run — the eat routine handles refuelling.
        });
    }
}
