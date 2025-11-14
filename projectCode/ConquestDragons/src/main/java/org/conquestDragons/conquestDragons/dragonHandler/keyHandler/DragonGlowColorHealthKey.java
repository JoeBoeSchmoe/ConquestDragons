package org.conquestDragons.conquestDragons.dragonHandler.keyHandler;

import java.util.Locale;

/**
 * Health-based glow profile key.
 *
 * SIMPLE  = coarse bands (full/ok/low/critical)
 * DETAILED = finer granularity (your 5–10% dark red, 100% green, etc.)
 *
 * Actual ranges + colors are defined in defaultDragonHealthColors.yml.
 */
public enum DragonGlowColorHealthKey {
    SIMPLE,
    DETAILED;

    // ---------------------------------------------------
    // Parse from config (case-insensitive, spaces/hyphens allowed)
    // ---------------------------------------------------
    public static DragonGlowColorHealthKey fromConfig(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DragonGlowColorHealthKey string is null");
        }

        String cleaned = raw
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return DragonGlowColorHealthKey.valueOf(cleaned);
    }

    // ---------------------------------------------------
    // Stable lowercase ID for saving to YAML/config
    // ---------------------------------------------------
    public String configId() {
        return name().toLowerCase(Locale.ROOT);
    }
}
