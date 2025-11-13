package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import org.bukkit.Sound;
import org.conquestClans.conquestclans.ConquestClans;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 🎵 SoundCompatModel
 * Safely matches sounds across Minecraft versions and remap differences.
 * Supports runtime compatibility for older versions even when compiled on newer APIs.
 */
public class SoundCompatModel {

    private static final Logger log = ConquestClans.getInstance().getLogger();

    /**
     * Aliases: remap legacy → modern enum format.
     * Keys are matched case-insensitively after normalization.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            // Legacy and shorthand
            Map.entry("click", "ui_button_click"),
            Map.entry("note_bass", "block_note_block_bass"),
            Map.entry("note_hat", "block_note_block_hat"),
            Map.entry("note_pling", "block_note_block_pling"),
            Map.entry("chest_close", "block_chest_close"),
            Map.entry("chest_open", "block_chest_open"),

            // Legacy Bukkit → Mojang enum remaps
            Map.entry("block.note_block.bass", "block_note_block_bass"),
            Map.entry("BLOCK_NOTE_BLOCK_BASS", "block_note_block_bass"),
            Map.entry("block_note_block_bass", "block_note_block_bass"),
            Map.entry("block_note_block_hat", "block_note_block_hat"),
            Map.entry("BLOCK_NOTE_BLOCK_HAT", "block_note_block_hat"),
            Map.entry("block_note_block_pling", "block_note_block_pling"),
            Map.entry("BLOCK_NOTE_BLOCK_PLING", "block_note_block_pling"),

            // Removed/renamed enum → valid alternative
            Map.entry("entity_experience_orb_pickup", "entity_player_levelup"),
            Map.entry("ENTITY_EXPERIENCE_ORB_PICKUP", "entity_player_levelup")
    );

    /**
     * Attempts to match a sound string safely across mappings and formats.
     *
     * @param input The sound name from config (case-insensitive)
     * @return Optional of matching Sound, or empty if not resolvable
     */
    public static Optional<Sound> match(String input) {
        if (input == null || input.isBlank()) return Optional.empty();

        String raw = input.trim().toLowerCase(Locale.ROOT);
        String normalized = normalize(raw);

        // 1) Direct normalized lookup
        Optional<Sound> direct = tryValueOf(normalized);
        if (direct.isPresent()) return direct;

        // 2) Alias remap → lookup
        String alias = ALIASES.get(raw);
        if (alias != null) {
            Optional<Sound> aliasMatch = tryValueOf(normalize(alias));
            if (aliasMatch.isPresent()) return aliasMatch;
        }

        // 3) Suffix fallback (e.g., "pling" → "..._PLING")
        for (Sound sound : Sound.values()) {
            if (normalize(sound.name()).endsWith(normalized)) {
                return Optional.of(sound);
            }
        }

        // 4) Failure
        log.warning("[ConquestClans] ⚠️ Unknown or unsupported sound: '" + input + "'");
        return Optional.empty();
    }

    /**
     * Normalizes a sound string to lowercase underscore format.
     * E.g., "BLOCK.NOTE_BLOCK.BASS" → "block_note_block_bass"
     */
    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace(".", "_")
                .replace(" ", "")
                .trim();
    }

    /**
     * Runtime-safe attempt to resolve a Sound enum.
     * Avoids NoSuchFieldError on older servers.
     */
    private static Optional<Sound> tryValueOf(String enumName) {
        try {
            Sound sound = Sound.valueOf(enumName.toUpperCase(Locale.ROOT));
            return Optional.of(sound);
        } catch (IllegalArgumentException | NoSuchFieldError | ExceptionInInitializerError e) {
            return Optional.empty();
        }
    }
}
