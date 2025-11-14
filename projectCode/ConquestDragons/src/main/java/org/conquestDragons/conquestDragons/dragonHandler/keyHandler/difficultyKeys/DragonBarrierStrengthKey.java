package org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys;

import java.util.Locale;

public enum DragonBarrierStrengthKey {
    FRAGILE,
    WEAK,
    AVERAGE,
    THICK,
    IMPENETRABLE;

    // ---------------------------------------------------
    // Parse from config (case-insensitive, tolerates spaces and hyphens)
    // ---------------------------------------------------
    public static DragonBarrierStrengthKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonBarrierKey string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonBarrierStrengthKey.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable lowercase ID for saving to YAML/config
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Comparison helpers
    // FRAGILE < WEAK < AVERAGE < THICK < IMPENETRABLE
    // ---------------------------------------------------
    public boolean isWeakerThan(DragonBarrierStrengthKey other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isStrongerThan(DragonBarrierStrengthKey other) {
        return this.ordinal() > other.ordinal();
    }
}
