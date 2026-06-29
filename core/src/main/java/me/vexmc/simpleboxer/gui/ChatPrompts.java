package me.vexmc.simpleboxer.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * In-chat text capture for the menus — the one input a grid of clickable icons
 * can't express (a boxer's name, an account name for a skin). A prompt closes
 * the open menu so chat is readable, captures the next line that player types
 * (cancelling it so it never broadcasts), and hands it back on the player's own
 * owning thread; typing {@code cancel} aborts.
 *
 * <p>It listens on Paper's {@link AsyncChatEvent}, the canonical chat event on
 * every supported version (1.17.1 → 26.x) — unlike the legacy
 * {@code AsyncPlayerChatEvent}, which modern Paper no longer guarantees to
 * fire. The message Component is flattened to plain text, so colour or
 * decoration a client sends is stripped down to the bare answer.</p>
 */
public final class ChatPrompts implements Listener {

    private record Pending(@NotNull Consumer<String> onInput, @NotNull Runnable onCancel) {}

    private final Scheduling scheduling;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompts(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /** Whether this player is mid-prompt (used to suppress menu re-opens). */
    public boolean awaiting(@NotNull UUID playerId) {
        return pending.containsKey(playerId);
    }

    public void prompt(@NotNull Player player, @NotNull String promptLine,
            @NotNull Consumer<String> onInput) {
        prompt(player, promptLine, onInput, () -> {});
    }

    public void prompt(@NotNull Player player, @NotNull String promptLine,
            @NotNull Consumer<String> onInput, @NotNull Runnable onCancel) {
        pending.put(player.getUniqueId(), new Pending(onInput, onCancel));
        // Close next tick rather than inside a click handler — mutating the
        // view mid-InventoryClick is fragile on older servers.
        scheduling.runLaterOn(player, 1L, player::closeInventory, () -> {});
        player.sendMessage("§6§l» §eSimpleBoxer");
        player.sendMessage("§7" + promptLine);
        player.sendMessage("§7Type your answer in chat, or §fcancel §7to go back.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending request = pending.remove(player.getUniqueId());
        if (request == null) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        // Hop off the async chat thread back onto the player's owning thread —
        // every callback opens a menu or spawns a boxer, all main/region work.
        scheduling.runOn(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (message.equalsIgnoreCase("cancel") || message.isEmpty()) {
                request.onCancel().run();
            } else {
                request.onInput().accept(message);
            }
        }, () -> {});
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
