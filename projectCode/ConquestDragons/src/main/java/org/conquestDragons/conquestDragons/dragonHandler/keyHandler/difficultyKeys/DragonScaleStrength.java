package org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys;

import java.util.Locale;

public enum DragonScaleStrength {
    FRAGILE,
    WEAK,
    AVERAGE,
    THICK,
    IMPENETRABLE;

    // ---------------------------------------------------
    // Parse from YAML / config safely
    // Accepts: "fragile", " Fragile ", "FRAGILE", "fra-gile"
    // ---------------------------------------------------
    public static DragonScaleStrength fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonScaleStrength string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonScaleStrength.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable lowercase config ID
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Comparison helpers (ordinal-based)
    // FRAGILE < WEAK < AVERAGE < THICK < IMPENETRABLE
    // ---------------------------------------------------
    public boolean isWeakerThan(DragonScaleStrength other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isStrongerThan(DragonScaleStrength other) {
        return this.ordinal() > other.ordinal();
    }
}
