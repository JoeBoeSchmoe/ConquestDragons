package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.conquestClans.conquestclans.ConquestClans;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 💨 ParticleResponseManager
 * Handles spawning particles defined in message config sections (ConquestClans).
 *
 * Expected YAML shape (example):
 * particles:
 *   - { type: ENCHANTMENT_TABLE, count: 5, offset: [0.2, 0.3, 0.2], speed: 0.02 }
 */
public class ParticleResponseManager {

    private static final Logger log = ConquestClans.getInstance().getLogger();

    /**
     * Spawns all particles defined in a message section for the player.
     *
     * @param player  Player to display particles to
     * @param section Section containing a `particles:` list
     */
    public static void play(Player player, ConfigurationSection section) {
        if (player == null || section == null || !section.isList("particles")) return;

        List<Map<?, ?>> particles = section.getMapList("particles");
        if (particles.isEmpty()) return;

        for (Map<?, ?> particleData : particles) {
            spawnParticle(player, particleData);
        }
    }

    private static void spawnParticle(Player player, Map<?, ?> data) {
        if (data == null) return;

        Object typeObj = data.get("type");
        if (!(typeObj instanceof String)) {
            log.warning("⚠️ Missing or invalid particle 'type' in config.");
            return;
        }

        String typeString = typeObj.toString().trim().toUpperCase(Locale.ROOT);
        Particle particle;
        try {
            particle = Particle.valueOf(typeString);
        } catch (IllegalArgumentException e) {
            log.warning("⚠️ Invalid particle type in config: '" + typeString + "'");
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

    private static Vector parseOffset(Object raw) {
        if (raw instanceof List<?> list && list.size() == 3) {
            try {
                double x = Double.parseDouble(String.valueOf(list.get(0)));
                double y = Double.parseDouble(String.valueOf(list.get(1)));
                double z = Double.parseDouble(String.valueOf(list.get(2)));
                return new Vector(x, y, z);
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        return new Vector(0, 0, 0);
    }

    private static int parseInt(Object value, int def) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static double parseDouble(Object value, double def) {
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static int clampMin(int val) {
        return Math.max(val, 0);
    }

    private static org.bukkit.Location aboveHead(Player player) {
        return player.getLocation().add(0, 1.0, 0);
    }
}
