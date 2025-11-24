package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.conquestDragons.conquestDragons.ConquestDragons;

import java.util.*;
import java.util.logging.Logger;

/**
 * 💨 ParticleResponseManager (ConquestDragons)
 *
 * FIXED:
 *  - Skips particles that require extra data (REDSTONE, DUST, DUST_COLOR_TRANSITION, etc.)
 *  - Prevents "missing required data class java.lang.Float" crashes
 *  - Never throws during scheduler/messaging
 */
public class ParticleResponseManager {

    private static final Logger log = ConquestDragons.getInstance().getLogger();

    /**
     * Particles that **cannot** be spawned using the simple signature:
     *
     *   player.spawnParticle(particle, loc, count, ox, oy, oz, speed)
     *
     * because they REQUIRE special data objects such as DustOptions,
     * DustTransition, BlockData, ItemStack, etc.
     */
    private static final Set<Particle> DATA_REQUIRED = EnumSet.of(
            // Color-based particles
            Particle.DUST,
            Particle.DUST_COLOR_TRANSITION,

            // Block/item data required
            Particle.ITEM,
            Particle.BLOCK_MARKER,
            Particle.FALLING_DUST,

            // Misc requiring payload
            Particle.EFFECT,
            Particle.INSTANT_EFFECT,
            Particle.WITCH
    );

    /**
     * Spawns all particles defined under `particles:` in a message config section.
     */
    public static void play(Player player, ConfigurationSection section) {
        if (player == null || section == null || !section.isList("particles")) return;

        List<Map<?, ?>> list = section.getMapList("particles");
        if (list == null || list.isEmpty()) return;

        for (Map<?, ?> entry : list) {
            safeSpawnParticle(player, entry);
        }
    }

    /**
     * Wraps spawner in try/catch so broken entries never crash tasks.
     */
    private static void safeSpawnParticle(Player player, Map<?, ?> data) {
        try {
            spawnParticle(player, data);
        } catch (Throwable t) {
            //log.warning("[ConquestDragons] Skipped particle: " + t.getMessage());
        }
    }

    /**
     * Attempts to spawn the particle. Skips data-required types.
     */
    private static void spawnParticle(Player player, Map<?, ?> data) {
        if (player == null || data == null) return;

        Object typeObj = data.get("type");
        if (!(typeObj instanceof String)) {
            log.warning("⚠️ Missing particle 'type' in config.");
            return;
        }

        String typeString = typeObj.toString().trim().toUpperCase(Locale.ROOT);

        final Particle particle;
        try {
            particle = Particle.valueOf(typeString);
        } catch (IllegalArgumentException e) {
            log.warning("⚠️ Invalid particle type: '" + typeString + "'");
            return;
        }

        // 🚫 Skip unsupported data-requiring particles (fixes crash)
        if (DATA_REQUIRED.contains(particle)) {
            // Use fine() so console doesn't spam
            log.fine("[ConquestDragons] Skipped data-backed particle: " + particle.name());
            return;
        }

        int count = clampMin(parseInt(data.get("count"), 1));
        double speed = parseDouble(data.get("speed"), 0.01D);
        Vector offset = parseOffset(data.get("offset"));

        player.spawnParticle(
                particle,
                aboveHead(player),
                count,
                offset.getX(), offset.getY(), offset.getZ(),
                speed
        );
    }

    /* -------------------------------------------------------- */
    /* Helpers                                                   */
    /* -------------------------------------------------------- */

    private static Vector parseOffset(Object raw) {
        if (raw instanceof List<?> list && list.size() == 3) {
            try {
                double x = Double.parseDouble(String.valueOf(list.get(0)));
                double y = Double.parseDouble(String.valueOf(list.get(1)));
                double z = Double.parseDouble(String.valueOf(list.get(2)));
                return new Vector(x, y, z);
            } catch (Exception ignored) {}
        }
        return new Vector(0, 0, 0);
    }

    private static int parseInt(Object value, int def) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return def; }
    }

    private static double parseDouble(Object value, double def) {
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return def; }
    }

    private static int clampMin(int val) {
        return Math.max(val, 0);
    }

    private static org.bukkit.Location aboveHead(Player player) {
        return player.getLocation().add(0, 1.0, 0);
    }
}
