package org.conquestDragons.conquestDragons.cooldownHandler;

import org.conquestClans.conquestclans.ConquestClans;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 🛡️ GUICooldownManager
 * Handles cooldowns for GUI click actions to prevent spam clicking.
 *
 * Configurable via config.yml at:
 * cooldowns.gui-action-cooldown-ms (default: 250ms)
 */
public final class GUICooldownManager {

    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private GUICooldownManager() {
        // Utility class
    }

    /**
     * Checks if the player is on cooldown for GUI actions.
     *
     * @param uuid UUID of the player
     * @return true if still within cooldown
     */
    public static boolean isOnCooldown(UUID uuid) {
        long now = System.currentTimeMillis();
        long lastClick = cooldowns.getOrDefault(uuid, 0L);
        return (now - lastClick) < getConfiguredCooldownMs();
    }

    /**
     * Marks a GUI interaction as performed for the player.
     *
     * @param uuid UUID of the player
     */
    public static void mark(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
    }

    /**
     * Returns the remaining cooldown time in milliseconds.
     *
     * @param uuid UUID of the player
     * @return remaining cooldown
     */
    public static long getRemaining(UUID uuid) {
        long now = System.currentTimeMillis();
        long lastClick = cooldowns.getOrDefault(uuid, 0L);
        return Math.max(0, getConfiguredCooldownMs() - (now - lastClick));
    }

    /**
     * Clears all active GUI cooldowns (e.g., on plugin reload).
     */
    public static void clear() {
        cooldowns.clear();
    }

    /**
     * Retrieves GUI cooldown duration from config.
     *
     * @return cooldown time in milliseconds
     */
    private static long getConfiguredCooldownMs() {
        return Math.max(0, ConquestClans.getInstance()
                .getConfigurationManager()
                .getConfig()
                .getLong("cooldowns.gui-action-cooldown-ms", 250));
    }
}
