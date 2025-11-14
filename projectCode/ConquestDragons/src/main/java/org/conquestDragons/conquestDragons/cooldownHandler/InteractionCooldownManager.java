package org.conquestDragons.conquestDragons.cooldownHandler;

import org.conquestDragons.conquestDragons.ConquestDragons;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 🧊 InteractionCooldownManager
 * Handles cooldown enforcement for player interaction-based actions.
 *
 * Configurable via config.yml at:
 * {@code cooldowns.interaction-cooldown-ms}, with a default of 3000ms.
 */
public final class InteractionCooldownManager {

    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private InteractionCooldownManager() {
        // Utility class
    }

    /**
     * Checks if the player is still within cooldown.
     *
     * @param uuid player's UUID
     * @return true if cooldown is active
     */
    public static boolean isOnCooldown(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(uuid, 0L);
        return (now - last) < getConfiguredCooldownMs();
    }

    /**
     * Marks the current timestamp for cooldown.
     *
     * @param uuid player's UUID
     */
    public static void mark(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
    }

    /**
     * Gets remaining cooldown time.
     *
     * @param uuid player's UUID
     * @return time remaining in ms
     */
    public static long getRemaining(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(uuid, 0L);
        return Math.max(0, getConfiguredCooldownMs() - (now - last));
    }

    /**
     * Wipes all cooldown data.
     */
    public static void clear() {
        cooldowns.clear();
    }

    /**
     * Gets the configured cooldown from config.
     *
     * @return time in ms
     */
    private static long getConfiguredCooldownMs() {
        return Math.max(0, ConquestDragons.getInstance()
                .getConfigurationManager()
                .getConfig()
                .getLong("cooldowns.interaction-cooldown-ms", 3000));
    }
}
