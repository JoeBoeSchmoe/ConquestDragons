package org.conquestDragons.conquestDragons.cooldownHandler;

import org.conquestClans.conquestclans.ConquestClans;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ⏱️ CommandCooldownManager
 * Manages cooldown enforcement for player commands to prevent spam.
 *
 * Configurable in config.yml at:
 * cooldowns.command-delay-ms (default: 500ms)
 */
public final class CommandCooldownManager {

    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private CommandCooldownManager() {
        // Utility class
    }

    /**
     * Checks if the player is currently on cooldown.
     *
     * @param uuid UUID of the player
     * @return true if cooldown is active
     */
    public static boolean isOnCooldown(UUID uuid) {
        long now = System.currentTimeMillis();
        long lastUsed = cooldowns.getOrDefault(uuid, 0L);
        return (now - lastUsed) < getConfiguredCooldownMs();
    }

    /**
     * Marks the player as having used a command now.
     *
     * @param uuid UUID of the player
     */
    public static void mark(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
    }

    /**
     * Returns remaining cooldown time.
     *
     * @param uuid UUID of the player
     * @return milliseconds remaining
     */
    public static long getRemaining(UUID uuid) {
        long now = System.currentTimeMillis();
        long lastUsed = cooldowns.getOrDefault(uuid, 0L);
        return Math.max(0, getConfiguredCooldownMs() - (now - lastUsed));
    }

    /**
     * Clears all active cooldowns.
     */
    public static void clear() {
        cooldowns.clear();
    }

    /**
     * Gets cooldown duration from config.yml.
     *
     * @return milliseconds
     */
    private static long getConfiguredCooldownMs() {
        return Math.max(0, ConquestClans.getInstance()
                .getConfigurationManager()
                .getConfig()
                .getLong("cooldowns.command-delay-ms", 500));
    }
}
