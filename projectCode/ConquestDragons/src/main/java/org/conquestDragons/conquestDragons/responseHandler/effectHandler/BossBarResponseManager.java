package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.conquestClans.conquestclans.ConquestClans;
import org.conquestClans.conquestclans.responseHandler.ComponentSerializerManager;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 🔩 BossBarResponseManager
 * Handles sending customizable, animated bossbars to players safely.
 */
public class BossBarResponseManager {

    private static final Logger log = ConquestClans.getInstance().getLogger();
    private static final Map<Player, BossBarEntry> activeBars = new HashMap<>();

    private record BossBarEntry(BossBar bar, BukkitTask animationTask, BukkitTask hideTask) {}

    public static void send(Player player, ConfigurationSection section, Map<String, String> placeholders) {
        if (player == null || section == null || !section.isConfigurationSection("bossbar")) return;

        ConfigurationSection bossbarSection = section.getConfigurationSection("bossbar");
        if (bossbarSection == null) return;

        try {
            String text = bossbarSection.getString("text", "").trim();
            if (text.isEmpty()) return; // ⛔ Skip if no meaningful text

            // Clear any previous bossbar
            if (activeBars.containsKey(player)) {
                BossBarEntry entry = activeBars.remove(player);
                player.hideBossBar(entry.bar());
                if (entry.animationTask() != null) entry.animationTask().cancel();
                if (entry.hideTask() != null) entry.hideTask().cancel();
            }

            // Read configuration
            String colorRaw = bossbarSection.getString("color", "WHITE");
            String overlayRaw = bossbarSection.getString("overlay", "PROGRESS");
            double time = bossbarSection.getDouble("time", 60);
            double progressStart = bossbarSection.getDouble("progressStart", bossbarSection.getDouble("progress", 1.0));
            double progressEnd = bossbarSection.getDouble("progressEnd", progressStart);
            int animationRate = bossbarSection.getInt("animationTicks", 2);

            Component title = ComponentSerializerManager.format(player, text, placeholders);
            BossBar.Color color = safeColor(colorRaw);
            BossBar.Overlay overlay = safeOverlay(overlayRaw);

            BossBar bossBar = BossBar.bossBar(title, (float) progressStart, color, overlay);
            player.showBossBar(bossBar);

            BukkitTask animationTask = null;
            if (progressStart != progressEnd) {
                float delta = (float) ((progressEnd - progressStart) / (time / animationRate));
                animationTask = player.getServer().getScheduler().runTaskTimer(
                        ConquestClans.getInstance(),
                        () -> {
                            float current = bossBar.progress();
                            float next = current + delta;
                            if ((delta > 0 && next >= progressEnd) || (delta < 0 && next <= progressEnd)) {
                                bossBar.progress((float) progressEnd);
                            } else {
                                bossBar.progress(next);
                            }
                        },
                        0L,
                        animationRate
                );
            }

            BukkitTask hideTask = player.getServer().getScheduler().runTaskLater(
                    ConquestClans.getInstance(),
                    () -> {
                        BossBarEntry currentEntry = activeBars.get(player);
                        if (currentEntry != null && currentEntry.bar() == bossBar) {
                            player.hideBossBar(bossBar);
                            if (currentEntry.animationTask() != null) currentEntry.animationTask().cancel();
                            activeBars.remove(player);
                        }
                    },
                    (long) time
            );

            activeBars.put(player, new BossBarEntry(bossBar, animationTask, hideTask));

        } catch (Exception e) {
            log.warning("⚠️ Failed to send bossbar to player " + player.getName() + ": " + e.getMessage());
        }
    }

    private static BossBar.Color safeColor(String raw) {
        try {
            return BossBar.Color.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Color.WHITE;
        }
    }

    private static BossBar.Overlay safeOverlay(String raw) {
        try {
            return BossBar.Overlay.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    /**
     * Optional: clears all active bossbars (useful on plugin disable)
     */
    public static void clearAll() {
        for (Map.Entry<Player, BossBarEntry> entry : activeBars.entrySet()) {
            Player player = entry.getKey();
            BossBarEntry barEntry = entry.getValue();
            player.hideBossBar(barEntry.bar());
            if (barEntry.animationTask() != null) barEntry.animationTask().cancel();
            if (barEntry.hideTask() != null) barEntry.hideTask().cancel();
        }
        activeBars.clear();
    }
}
