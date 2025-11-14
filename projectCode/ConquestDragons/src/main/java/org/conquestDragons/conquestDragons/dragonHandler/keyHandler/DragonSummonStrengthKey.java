package org.conquestDragons.conquestDragons.dragonHandler.keyHandler;

import java.util.Locale;

public enum DragonSummonStrengthKey {
    FRAGILE,
    WEAK,
    AVERAGE,
    STRONG,
    OVERWHELMING;

    // ---------------------------------------------------
    // Parse from YAML / config safely
    // Accepts: "fragile", " Fragile ", "FRAGILE", "fra-gile"
    // ---------------------------------------------------
    public static DragonSummonStrengthKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonSummonStrengthKey string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonSummonStrengthKey.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable lowercase config ID
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Comparison helpers (ordinal-based)
    // FRAGILE < WEAK < AVERAGE < STRONG < OVERWHELMING
    // ---------------------------------------------------
    public boolean isWeakerThan(DragonSummonStrengthKey other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isStrongerThan(DragonSummonStrengthKey other) {
        return this.ordinal() > other.ordinal();
    }
}
