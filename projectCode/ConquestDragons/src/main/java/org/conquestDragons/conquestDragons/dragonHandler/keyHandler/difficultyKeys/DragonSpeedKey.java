package org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys;

import java.util.Locale;

public enum DragonSpeedKey {
    TURTLE,
    SLOW,
    MEDIUM,
    FAST,
    WHYNOT;  // "why not" cracked-speed tier

    /**
     * Parse from config string (case-insensitive, accepts symbols/extra spaces).
     * Example YAML: "fast", "FAST", " Fast  ".
     */
    public static DragonSpeedKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonSpeedKey string is null");
        }
        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonSpeedKey.valueOf(cleaned);
    }

    /**
     * Stable id for saving to config/YAML.
     * You can change enum name casing later without breaking config.
     */
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Simple ordering if you ever want to compare relative speeds.
     * (TURTLE < SLOW < MEDIUM < FAST < WHYNOT)
     */
    public boolean isSlowerThan(DragonSpeedKey other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isFasterThan(DragonSpeedKey other) {
        return this.ordinal() > other.ordinal();
    }
}
