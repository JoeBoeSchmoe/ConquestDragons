package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.conquestDragons.conquestDragons.ConquestDragons;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * 🔊 SoundResponseManager
 * Handles playing configured sound effects from message sections (ConquestDragons).
 *
 * Expected YAML shape:
 * sound:
 *   type: UI_BUTTON_CLICK
 *   volume: 1.0
 *   pitch: 1.0
 */
public class SoundResponseManager {

    private static final Logger log = ConquestDragons.getInstance().getLogger();

    /**
     * Plays a sound to a player from the given configuration section.
     *
     * @param player  Player who will hear the sound
     * @param section Configuration section containing the `sound` node
     */
    public static void play(Player player, ConfigurationSection section) {
        if (player == null || section == null) return;

        ConfigurationSection soundSection = section.getConfigurationSection("sound");
        if (soundSection == null) return;

        String typeString = soundSection.getString("type", "").trim();
        if (typeString.isEmpty()) return;

        Optional<Sound> optionalSound = SoundCompatModel.match(typeString);
        if (optionalSound.isEmpty()) {
            log.warning("⚠️ Unknown sound type in config: '" + typeString + "'");
            return;
        }

        Sound sound = optionalSound.get();

        float volume = (float) soundSection.getDouble("volume", 1.0);
        float pitch = (float) soundSection.getDouble("pitch", 1.0);

        try {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        } catch (Exception e) {
            log.warning("⚠️ Failed to play sound '" + sound + "' for " + player.getName() + ": " + e.getMessage());
        }
    }
}
