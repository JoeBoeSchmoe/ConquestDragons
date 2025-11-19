package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles;

import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Static loader for DefaultValuesConfiguration/defaultBossbarSettings.yml.
 *
 * Source of truth for default bossbar presets:
 *  - Global defaults
 *  - Normal dragon bossbar
 *  - IN_BELLY survival bossbar
 *  - Final dragon bossbar
 *
 * Produces immutable BossbarConfig instances for DragonBossbarManager.
 *
 * NOTE:
 *  BossBar COLOR is intentionally NOT configured here.
 *  DragonBossbarManager should derive color dynamically (e.g. by health).
 */
public final class DefaultBossbarSettingsFile {

    private static final String RESOURCE_PATH = "DefaultValuesConfiguration/defaultBossbarSettings.yml";
    private static final String DISK_DIR = "DefaultValuesConfiguration";
    private static final String DISK_NAME = "defaultBossbarSettings.yml";

    private static BossbarConfig GLOBAL_DEFAULTS = BossbarConfig.disabledFallback();
    private static BossbarConfig DRAGON_DEFAULTS = BossbarConfig.disabledFallback();
    private static BossbarConfig IN_BELLY_DEFAULTS = BossbarConfig.disabledFallback();
    private static BossbarConfig FINAL_DRAGON_DEFAULTS = BossbarConfig.disabledFallback();

    private DefaultBossbarSettingsFile() {
    }

    /** Create/refresh from jar (if missing) and load into memory. */
    public static void load() {
        ConquestDragons plugin = ConquestDragons.getInstance();
        File file = new File(plugin.getDataFolder(), DISK_DIR + File.separator + DISK_NAME);

        ensureOnDisk(plugin, file);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = cfg.getConfigurationSection("bossbars");
        if (root == null) {
            plugin.getLogger().warning("⚠️  defaultBossbarSettings.yml missing 'bossbars' section. Using disabled bossbars.");
            GLOBAL_DEFAULTS = BossbarConfig.disabledFallback();
            DRAGON_DEFAULTS = BossbarConfig.disabledFallback();
            IN_BELLY_DEFAULTS = BossbarConfig.disabledFallback();
            FINAL_DRAGON_DEFAULTS = BossbarConfig.disabledFallback();
            return;
        }

        // Global defaults (used as a base / fallback in your manager)
        GLOBAL_DEFAULTS = parseBossbarSection(plugin, root.getConfigurationSection("defaults"),
                "bossbars.defaults",
                BossbarConfig.globalDefaultsFallback());

        // Normal dragon bossbar
        DRAGON_DEFAULTS = parseBossbarSection(plugin, root.getConfigurationSection("dragon"),
                "bossbars.dragon",
                BossbarConfig.dragonFallback());

        // IN_BELLY survival bossbar
        IN_BELLY_DEFAULTS = parseBossbarSection(plugin, root.getConfigurationSection("in-belly"),
                "bossbars.in-belly",
                BossbarConfig.inBellyFallback());

        // Final dragon bossbar
        FINAL_DRAGON_DEFAULTS = parseBossbarSection(plugin, root.getConfigurationSection("final-dragon"),
                "bossbars.final-dragon",
                BossbarConfig.finalDragonFallback());

        plugin.getLogger().info("✅  Loaded default bossbar settings (global, dragon, in-belly, final-dragon).");
    }

    // ---------------------------------------------------------------------
    // Public accessors
    // ---------------------------------------------------------------------

    /** Global bossbar defaults (base configuration). */
    public static BossbarConfig getGlobalDefaults() {
        return GLOBAL_DEFAULTS;
    }

    /** Default config for normal dragon bossbars. */
    public static BossbarConfig getDragonDefaults() {
        return DRAGON_DEFAULTS;
    }

    /** Default config for IN_BELLY bossbars. */
    public static BossbarConfig getInBellyDefaults() {
        return IN_BELLY_DEFAULTS;
    }

    /** Default config for final dragon bossbars. */
    public static BossbarConfig getFinalDragonDefaults() {
        return FINAL_DRAGON_DEFAULTS;
    }

    // ---------------------------------------------------------------------
    // Helpers: parse a bossbar section → BossbarConfig
    // ---------------------------------------------------------------------

    private static BossbarConfig parseBossbarSection(ConquestDragons plugin,
                                                     ConfigurationSection sec,
                                                     String path,
                                                     BossbarConfig fallback) {
        if (sec == null) {
            plugin.getLogger().warning("⚠️  defaultBossbarSettings.yml missing section '" + path +
                    "'. Using fallback.");
            return fallback;
        }

        boolean enabled = sec.getBoolean("enabled", fallback.enabled());

        String title = sec.getString("title", fallback.title());

        String styleRaw = sec.getString("style", fallback.style().name());
        BarStyle style = parseBarStyle(plugin, styleRaw, path, fallback.style());

        boolean participantsOnly = sec.getBoolean("participants-only", fallback.participantsOnly());
        boolean darkenScreen = sec.getBoolean("darken-screen", fallback.darkenScreen());
        boolean createFog = sec.getBoolean("create-fog", fallback.createFog());
        boolean playBossMusic = sec.getBoolean("play-boss-music", fallback.playBossMusic());

        long updateIntervalTicks = sec.getLong("update-interval-ticks", fallback.updateIntervalTicks());
        if (updateIntervalTicks < 1L) {
            // We'll allow 0 in YAML, but clamp to 1 for safety
            updateIntervalTicks = 1L;
        }

        return new BossbarConfig(
                enabled,
                title,
                style,
                participantsOnly,
                darkenScreen,
                createFog,
                playBossMusic,
                updateIntervalTicks
        );
    }

    private static BarStyle parseBarStyle(ConquestDragons plugin,
                                          String raw,
                                          String path,
                                          BarStyle fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BarStyle.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("⚠️  defaultBossbarSettings.yml '" + path +
                    "' has invalid style '" + raw + "'. Using " + fallback.name() + " instead.");
            return fallback;
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
                        "⚠️  Missing bundled resource '" + RESOURCE_PATH + "'. Creating minimal placeholder.", ex);
                try {
                    if (file.createNewFile()) {
                        YamlConfiguration yaml = new YamlConfiguration();
                        // Minimal skeleton so reloads don't explode
                        ConfigurationSection root = yaml.createSection("bossbars");
                        root.createSection("defaults");
                        root.createSection("dragon");
                        root.createSection("in-belly");
                        root.createSection("final-dragon");
                        yaml.save(file);
                    }
                } catch (IOException io) {
                    plugin.getLogger().log(Level.SEVERE,
                            "⚠️  Failed to create " + file.getPath(), io);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Value object: BossbarConfig
    // ---------------------------------------------------------------------

    /**
     * Immutable bossbar configuration for use by DragonBossbarManager.
     *
     * NOTE: Color is intentionally NOT stored here; it's derived at runtime.
     */
    public static final class BossbarConfig {

        private final boolean enabled;
        private final String title;
        private final BarStyle style;
        private final boolean participantsOnly;
        private final boolean darkenScreen;
        private final boolean createFog;
        private final boolean playBossMusic;
        private final long updateIntervalTicks;

        public BossbarConfig(boolean enabled,
                             String title,
                             BarStyle style,
                             boolean participantsOnly,
                             boolean darkenScreen,
                             boolean createFog,
                             boolean playBossMusic,
                             long updateIntervalTicks) {

            this.enabled = enabled;
            this.title = (title == null ? "" : title);
            this.style = (style == null ? BarStyle.SEGMENTED_10 : style);
            this.participantsOnly = participantsOnly;
            this.darkenScreen = darkenScreen;
            this.createFog = createFog;
            this.playBossMusic = playBossMusic;
            this.updateIntervalTicks = updateIntervalTicks <= 0L ? 1L : updateIntervalTicks;
        }

        public boolean enabled() {
            return enabled;
        }

        public String title() {
            return title;
        }

        public BarStyle style() {
            return style;
        }

        public boolean participantsOnly() {
            return participantsOnly;
        }

        public boolean darkenScreen() {
            return darkenScreen;
        }

        public boolean createFog() {
            return createFog;
        }

        public boolean playBossMusic() {
            return playBossMusic;
        }

        public long updateIntervalTicks() {
            return updateIntervalTicks;
        }

        // ---- sensible fallbacks for each logical type -------------------

        /** Completely disabled fallback (used when section is missing). */
        public static BossbarConfig disabledFallback() {
            return new BossbarConfig(
                    false,
                    "",
                    BarStyle.SEGMENTED_10,
                    true,
                    false,
                    false,
                    false,
                    20L
            );
        }

        /** Fallback for bossbars.defaults. */
        public static BossbarConfig globalDefaultsFallback() {
            return new BossbarConfig(
                    true,
                    "<gold><bold>{dragon_name}</bold></gold> <gray>-</gray> <red>{health_percent}%</red>",
                    BarStyle.SEGMENTED_10,
                    true,
                    false,
                    false,
                    false,
                    10L
            );
        }

        /** Fallback for bossbars.dragon. */
        public static BossbarConfig dragonFallback() {
            return new BossbarConfig(
                    true,
                    "<gold><bold>{dragon_name}</bold></gold> <gray>-</gray> <red>{health_percent}%</red>",
                    BarStyle.SEGMENTED_10,
                    true,
                    false,
                    false,
                    false,
                    10L
            );
        }

        /** Fallback for bossbars.in-belly. */
        public static BossbarConfig inBellyFallback() {
            return new BossbarConfig(
                    true,
                    "<dark_red><bold>Inside the Maw</bold></dark_red> <gray>- Survive <yellow>{time_remaining}</yellow></gray>",
                    BarStyle.SOLID,
                    true,
                    true,
                    true,
                    false,
                    5L
            );
        }

        /** Fallback for bossbars.final-dragon. */
        public static BossbarConfig finalDragonFallback() {
            return new BossbarConfig(
                    true,
                    "<dark_purple><bold>Apex Devourer</bold></dark_purple> <gray>-</gray> <red>{health_percent}%</red>",
                    BarStyle.SEGMENTED_20,
                    true,
                    true,
                    true,
                    true,
                    5L
            );
        }

        @Override
        public String toString() {
            return "BossbarConfig{" +
                    "enabled=" + enabled +
                    ", title='" + title + '\'' +
                    ", style=" + style +
                    ", participantsOnly=" + participantsOnly +
                    ", darkenScreen=" + darkenScreen +
                    ", createFog=" + createFog +
                    ", playBossMusic=" + playBossMusic +
                    ", updateIntervalTicks=" + updateIntervalTicks +
                    '}';
        }
    }
}
