package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonGlowColorHealthKey;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Static loader for DefaultValuesConfiguration/defaultDragonHealthColors.yml.
 *
 * Source of truth for health-percentage → color bands for:
 *  - Glow colors        (glow-profiles)
 *  - Bossbar colors     (bossbar-profiles)
 *
 * Schema:
 *
 * glow-profiles:
 *   SIMPLE:
 *     id: "simple_glow"
 *     display-name: "<green>Simple Health Glow</green>"
 *     bands:
 *       - min-percent: 70
 *         max-percent: 100
 *         color: GREEN
 *       ...
 *
 * bossbar-profiles:
 *   SIMPLE:
 *     id: "simple_bossbar"
 *     display-name: "<green>Simple Bossbar Colors</green>"
 *     bands:
 *       - min-percent: 70
 *         max-percent: 100
 *         color: GREEN
 *       ...
 *
 * This loader does NOT resolve the color string to NamedTextColor/ChatColor;
 * it keeps them as raw strings. A separate resolver can convert colorName -> actual color.
 */
public final class DefaultDragonHealthColorsFile {

    private static final String RESOURCE_PATH = "DefaultValuesConfiguration/defaultDragonHealthColors.yml";
    private static final String DISK_DIR = "DefaultValuesConfiguration";
    private static final String DISK_NAME = "defaultDragonHealthColors.yml";

    /**
     * Immutable in-memory views of profiles keyed by DragonGlowColorHealthKey.
     */
    private static Map<DragonGlowColorHealthKey, ProfileSpec> GLOW_PROFILES = Collections.emptyMap();
    private static Map<DragonGlowColorHealthKey, ProfileSpec> BOSSBAR_PROFILES = Collections.emptyMap();

    private DefaultDragonHealthColorsFile() { }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** Create/refresh from jar (if missing) and load into memory. */
    public static void load() {
        ConquestDragons plugin = ConquestDragons.getInstance();
        File file = new File(plugin.getDataFolder(), DISK_DIR + File.separator + DISK_NAME);

        ensureOnDisk(plugin, file);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection glowSec = cfg.getConfigurationSection("glow-profiles");
        ConfigurationSection bossbarSec = cfg.getConfigurationSection("bossbar-profiles");

        if (glowSec == null) {
            plugin.getLogger().warning("⚠️  defaultDragonHealthColors.yml missing 'glow-profiles' section. Using empty glow map.");
            GLOW_PROFILES = Collections.emptyMap();
        } else {
            GLOW_PROFILES = Collections.unmodifiableMap(parseProfileSection(plugin, glowSec, "glow-profiles"));
        }

        if (bossbarSec == null) {
            plugin.getLogger().warning("⚠️  defaultDragonHealthColors.yml missing 'bossbar-profiles' section. Using empty bossbar map.");
            BOSSBAR_PROFILES = Collections.emptyMap();
        } else {
            BOSSBAR_PROFILES = Collections.unmodifiableMap(parseProfileSection(plugin, bossbarSec, "bossbar-profiles"));
        }

        plugin.getLogger().info("✅  Loaded " + GLOW_PROFILES.size() + " glow profile(s) and "
                + BOSSBAR_PROFILES.size() + " bossbar profile(s) for dragon health colors.");
    }

    /** Snapshot of glow profiles keyed by DragonGlowColorHealthKey. */
    public static Map<DragonGlowColorHealthKey, ProfileSpec> getGlowProfiles() {
        return GLOW_PROFILES;
    }

    /** Snapshot of bossbar profiles keyed by DragonGlowColorHealthKey. */
    public static Map<DragonGlowColorHealthKey, ProfileSpec> getBossbarProfiles() {
        return BOSSBAR_PROFILES;
    }

    /** Convenience accessor for a single glow profile. */
    public static Optional<ProfileSpec> getGlowProfile(DragonGlowColorHealthKey key) {
        return Optional.ofNullable(GLOW_PROFILES.get(key));
    }

    /** Convenience accessor for a single bossbar profile. */
    public static Optional<ProfileSpec> getBossbarProfile(DragonGlowColorHealthKey key) {
        return Optional.ofNullable(BOSSBAR_PROFILES.get(key));
    }

    // ---------------------------------------------------------------------
    // Data classes
    // ---------------------------------------------------------------------

    /**
     * Immutable spec for a profile: id, display name, and a list of health bands.
     */
    public static final class ProfileSpec {
        private final String id;
        private final String displayName; // MiniMessage
        private final List<HealthColorBand> bands;

        public ProfileSpec(String id, String displayName, List<HealthColorBand> bands) {
            this.id = Objects.requireNonNull(id, "id");
            this.displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
            this.bands = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(bands, "bands")));
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public List<HealthColorBand> bands() {
            return bands;
        }
    }

    /**
     * Immutable health color band: [minPercent, maxPercent] and a color name.
     * Percent is expected in [0,100].
     */
    public static final class HealthColorBand {
        private final double minPercent;
        private final double maxPercent;
        private final String colorName; // e.g. "GREEN", "DARK_RED"

        public HealthColorBand(double minPercent, double maxPercent, String colorName) {
            if (minPercent < 0.0 || maxPercent > 100.0 || minPercent > maxPercent) {
                throw new IllegalArgumentException("Invalid percent range [" + minPercent + ", " + maxPercent + "]");
            }
            this.minPercent = minPercent;
            this.maxPercent = maxPercent;
            this.colorName = Objects.requireNonNull(colorName, "colorName");
        }

        public double minPercent() {
            return minPercent;
        }

        public double maxPercent() {
            return maxPercent;
        }

        public String colorName() {
            return colorName;
        }

        /** Returns true if the given percent falls within this band (inclusive). */
        public boolean matches(double percent) {
            return percent >= minPercent && percent <= maxPercent;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: file placement
    // ---------------------------------------------------------------------

    private static void ensureOnDisk(ConquestDragons plugin, File file) {
        File dir = new File(plugin.getDataFolder(), DISK_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("⚠️  Could not create " + dir.getPath());
        }
        if (!file.exists()) {
            try {
                plugin.saveResource(RESOURCE_PATH, false);
                plugin.getLogger().info("✅  Placed default " + RESOURCE_PATH + " onto disk.");
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "⚠️  Missing bundled resource '" + RESOURCE_PATH + "'. Creating empty file.", ex);
                try {
                    if (file.createNewFile()) {
                        YamlConfiguration empty = new YamlConfiguration();
                        empty.createSection("glow-profiles");
                        empty.createSection("bossbar-profiles");
                        empty.save(file);
                    }
                } catch (IOException io) {
                    plugin.getLogger().log(Level.SEVERE, "⚠️  Failed to create " + file.getPath(), io);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: profile & band parsing
    // ---------------------------------------------------------------------

    private static Map<DragonGlowColorHealthKey, ProfileSpec> parseProfileSection(ConquestDragons plugin,
                                                                                  ConfigurationSection sectionRoot,
                                                                                  String sectionName) {
        Map<DragonGlowColorHealthKey, ProfileSpec> result = new EnumMap<>(DragonGlowColorHealthKey.class);

        for (String profileKeyName : sectionRoot.getKeys(false)) {
            ConfigurationSection profileSec = sectionRoot.getConfigurationSection(profileKeyName);
            if (profileSec == null) {
                final String keyCopy = profileKeyName;
                plugin.getLogger().warning(() ->
                        "⚠️  Health color profile '" + keyCopy + "' in '" + sectionName +
                                "' is not a section. Skipping.");
                continue;
            }

            DragonGlowColorHealthKey profileKey;
            try {
                profileKey = DragonGlowColorHealthKey.valueOf(profileKeyName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                final String keyCopy = profileKeyName;
                plugin.getLogger().warning(() ->
                        "⚠️  Unknown DragonGlowColorHealthKey '" + keyCopy +
                                "' in " + sectionName + ". Skipping.");
                continue;
            }

            String id = profileSec.getString("id", profileKeyName.toLowerCase(Locale.ROOT));
            String displayName = profileSec.getString("display-name", profileKeyName);

            List<HealthColorBand> bands = parseBands(plugin, profileSec, profileKeyName, sectionName);

            if (bands.isEmpty()) {
                final String keyCopy = profileKeyName;
                plugin.getLogger().warning(() ->
                        "⚠️  Health color profile '" + keyCopy +
                                "' in '" + sectionName + "' has no 'bands'. Skipping this profile.");
                continue;
            }

            // Sort bands by descending minPercent so "first match" logic is predictable.
            bands.sort(Comparator.comparingDouble(HealthColorBand::minPercent).reversed());

            ProfileSpec spec = new ProfileSpec(id, displayName, bands);
            result.put(profileKey, spec);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<HealthColorBand> parseBands(ConquestDragons plugin,
                                                    ConfigurationSection profileSec,
                                                    String profileName,
                                                    String sectionName) {
        List<HealthColorBand> bands = new ArrayList<>();

        // YAML list of sections -> List<Map<?,?>>
        List<Map<?, ?>> rawBands = profileSec.getMapList("bands");
        if (rawBands == null || rawBands.isEmpty()) {
            final String profileCopy = profileName;
            plugin.getLogger().warning(() ->
                    "⚠️  Health color profile '" + profileCopy + "' in '" + sectionName +
                            "' has no 'bands' list.");
            return bands;
        }

        for (int i = 0; i < rawBands.size(); i++) {
            final int index = i;
            Map<?, ?> entry = rawBands.get(i);
            if (entry == null || entry.isEmpty()) {
                final String profileCopy = profileName;
                plugin.getLogger().warning(() ->
                        "⚠️  Profile '" + profileCopy + "' in '" + sectionName +
                                "' band #" + index + " is empty. Skipping.");
                continue;
            }

            double min = getDouble(entry.get("min-percent"), 0.0);
            double max = getDouble(entry.get("max-percent"), 100.0);
            Object colorObj = entry.get("color");

            if (colorObj == null) {
                final String profileCopy = profileName;
                plugin.getLogger().warning(() ->
                        "⚠️  Profile '" + profileCopy + "' in '" + sectionName +
                                "' band #" + index + " missing 'color'. Skipping this band.");
                continue;
            }

            String colorName = String.valueOf(colorObj).trim();
            if (colorName.isEmpty()) {
                final String profileCopy = profileName;
                plugin.getLogger().warning(() ->
                        "⚠️  Profile '" + profileCopy + "' in '" + sectionName +
                                "' band #" + index + " has blank 'color'. Skipping this band.");
                continue;
            }

            try {
                HealthColorBand band = new HealthColorBand(min, max, colorName);
                bands.add(band);
            } catch (IllegalArgumentException ex) {
                final String profileCopy = profileName;
                plugin.getLogger().warning(() ->
                        "⚠️  Profile '" + profileCopy + "' in '" + sectionName +
                                "' band #" + index +
                                " has invalid range [" + min + ", " + max + "]. Skipping this band.");
            }
        }

        return bands;
    }

    private static double getDouble(Object value, double def) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        try {
            return value != null ? Double.parseDouble(value.toString()) : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
