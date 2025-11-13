package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.conquestClans.conquestclans.ConquestClans;
import org.conquestClans.conquestclans.responseHandler.ComponentSerializerManager;

import java.util.Map;

/**
 * ✨ ActionBarResponseManager
 * Re-sends the action bar periodically to approximate a "duration".
 */
public class ActionBarResponseManager {

    /**
     * Sends an actionbar to the player if defined in the configuration.
     *
     * Expected section:
     * actionbar:
     *   text: "<gray>Try /clans help</gray>"
     *   duration: 60  # ticks; 20 ticks = ~1s
     */
    public static void send(Player player, ConfigurationSection section, Map<String, String> placeholders) {
        if (player == null || section == null) return;
        ConfigurationSection actionbar = section.getConfigurationSection("actionbar");
        if (actionbar == null) return;

        String raw = actionbar.getString("text", "");
        int durationTicks = Math.max(1, actionbar.getInt("duration", 60)); // clamp ≥1 tick
        if (raw.isBlank()) return;

        Component component = ComponentSerializerManager.format(player, raw, placeholders);

        // If < 20 ticks, just send once and return
        if (durationTicks < 20) {
            if (player.isOnline()) player.sendActionBar(component);
            return;
        }

        // Re-send once per second until duration elapses (or player logs out)
        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (!player.isOnline() || elapsed >= durationTicks) {
                    cancel();
                    return;
                }
                player.sendActionBar(component);
                elapsed += 20; // one second per tick cycle
            }
        }.runTaskTimer(ConquestClans.getInstance(), 0L, 20L);
    }
}
