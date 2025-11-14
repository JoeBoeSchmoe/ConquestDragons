package org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys;

import java.util.Locale;

/**
 * Tiered summon-speed keys.
 *
 * (SLOW_RITUAL < RITUAL < STANDARD < FAST < INSTANT)
 */
public enum DragonSummonSpeedKey {
    TURTLE,
    SLOW,
    MEDIUM,
    FAST,
    WHYNOT;

    // ---------------------------------------------------
    // Parse from config (case-insensitive, spaces/hyphens allowed)
    // ---------------------------------------------------
    public static DragonSummonSpeedKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonSummonSpeedKey string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonSummonSpeedKey.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable ID for YAML
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Ordering helpers
    // (SLOW_RITUAL < RITUAL < STANDARD < FAST < INSTANT)
    // ---------------------------------------------------
    public boolean isSlowerThan(DragonSummonSpeedKey other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isFasterThan(DragonSummonSpeedKey other) {
        return this.ordinal() > other.ordinal();
    }
}
