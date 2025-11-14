package org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys;

import java.util.Locale;

public enum DragonAIKey {
    DUMB,
    AVERAGE,
    SMART,
    ADVANCED,
    GENIUS;

    // ---------------------------------------------------
    // Parse from config (case-insensitive, hyphens/spaces allowed)
    // ---------------------------------------------------
    public static DragonAIKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonAIKey string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonAIKey.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable lowercase ID for saving to YAML/config
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Comparison helpers
    // DUMB < AVERAGE < SMART < ADVANCED < GENIUS
    // ---------------------------------------------------
    public boolean isSimplerThan(DragonAIKey other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isMoreComplexThan(DragonAIKey other) {
        return this.ordinal() > other.ordinal();
    }
}
