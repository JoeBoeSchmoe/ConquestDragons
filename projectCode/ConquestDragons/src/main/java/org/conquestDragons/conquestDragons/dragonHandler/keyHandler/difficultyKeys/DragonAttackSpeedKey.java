package org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys;

import java.util.Locale;

public enum DragonAttackSpeedKey {
    TURTLE,
    SLOW,
    MEDIUM,
    FAST,
    WHYNOT; // Insane attack speed

    // ---------------------------------------------------
    // Parse from config safely
    // ---------------------------------------------------
    public static DragonAttackSpeedKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonAttackSpeedKey string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonAttackSpeedKey.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable, lowercase config ID
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Comparison helpers (optional but extremely useful)
    // ---------------------------------------------------
    public boolean isSlowerThan(DragonAttackSpeedKey other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isFasterThan(DragonAttackSpeedKey other) {
        return this.ordinal() > other.ordinal();
    }
}
