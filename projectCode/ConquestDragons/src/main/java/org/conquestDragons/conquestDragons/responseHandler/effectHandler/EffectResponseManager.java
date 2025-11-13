package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.conquestClans.conquestclans.ConquestClans;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 🧪 EffectResponseManager
 * Handles sending Potion Effects from response models.
 */
public class EffectResponseManager {

    private static final Logger log = ConquestClans.getInstance().getLogger();

    public static void send(Player player, ConfigurationSection section, Map<String, String> placeholders) {
        if (player == null || section == null) return;
        if (!section.isList("effects")) return;

        List<Map<?, ?>> effects = section.getMapList("effects");
        for (Map<?, ?> rawEffect : effects) {
            try {
                String typeRaw = String.valueOf(rawEffect.get("type"));

                int amplifier = parseInt(rawEffect.get("amplifier"), 0);
                int duration = parseInt(rawEffect.get("duration"), 100);
                boolean ambient = parseBoolean(rawEffect.get("ambient"), true);
                boolean showParticles = parseBoolean(rawEffect.get("particles"), true);

                PotionEffectType type = PotionEffectType.getByName(typeRaw.toUpperCase());
                if (type == null) {
                    log.warning("⚠️ Unknown potion effect type '" + typeRaw + "' for player " + player.getName());
                    continue;
                }

                PotionEffect potionEffect = new PotionEffect(type, duration, amplifier, ambient, showParticles);
                player.addPotionEffect(potionEffect);

            } catch (Exception e) {
                log.warning("⚠️ Failed to apply effect to player " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    // Utilities for parsing safely
    private static int parseInt(Object obj, int defaultValue) {
        if (obj instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(obj.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(Object obj, boolean defaultValue) {
        if (obj instanceof Boolean bool) {
            return bool;
        }
        try {
            return Boolean.parseBoolean(obj.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

}
