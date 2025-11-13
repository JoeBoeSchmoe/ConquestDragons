package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestClans.conquestclans.ConquestClans;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

/**
 * ⚙️ ConfigFile
 * Static utility for loading and accessing /config.yml.
 * - Creates the file from the plugin jar if it doesn't exist
 * - Loads into memory as YamlConfiguration
 * - Provides simple typed getters and contains() checks
 */
public final class ConfigFile {

    private static final ConquestClans plugin = ConquestClans.getInstance();
    private static final Logger log = plugin.getLogger();

    private static YamlConfiguration yaml;
    private static File file;

    private ConfigFile() {
        // Utility class — no instances
    }

    /**
     * Creates (if missing) and loads /config.yml from the plugin data folder.
     * Also wires defaults from the embedded resource if present.
     */
    public static void load() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                log.warning("⚠️  Failed to create plugin data folder: " + dataFolder.getAbsolutePath());
            }

            file = new File(dataFolder, "config.yml");

            // Create from jar if missing
            if (!file.exists()) {
                try (InputStream in = plugin.getResource("config.yml")) {
                    if (in != null) {
                        Files.copy(in, file.toPath());
                        log.info("📄  Created default config.yml");
                    } else {
                        log.warning("⚠️  Missing embedded config.yml resource in plugin jar!");
                        // Still create an empty file to avoid NPEs later
                        if (file.createNewFile()) {
                            log.warning("ℹ️  Created empty config.yml (no defaults found in jar).");
                        }
                    }
                }
            }

            // Load actual config
            yaml = YamlConfiguration.loadConfiguration(file);

            // Wire defaults (if resource exists), so missing keys resolve to jar defaults
            try (InputStream defStream = plugin.getResource("config.yml")) {
                if (defStream != null) {
                    YamlConfiguration def = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(defStream, StandardCharsets.UTF_8));
                    yaml.setDefaults(def);
                    yaml.options().copyDefaults(true);
                }
            }

            log.info("✅  Loaded config.yml successfully.");
        } catch (Exception e) {
            log.severe("❌  Failed to load config.yml: " + e.getMessage());
        }
    }

    /**
     * Reloads /config.yml from disk.
     */
    public static void reload() {
        if (file == null) {
            load();
            return;
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        // re-attach defaults
        try (InputStream defStream = plugin.getResource("config.yml")) {
            if (defStream != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                yaml.setDefaults(def);
                yaml.options().copyDefaults(true);
            }
        } catch (Exception ignored) { }
        log.info("🔁  Reloaded config.yml.");
    }

    /**
     * Saves current in-memory values back to disk.
     */
    public static void save() {
        if (yaml == null || file == null) return;
        try {
            yaml.save(file);
            log.info("💾  Saved config.yml.");
        } catch (Exception e) {
            log.severe("❌  Failed to save config.yml: " + e.getMessage());
        }
    }

    // -------- Accessors --------

    public static YamlConfiguration getConfig() {
        return yaml;
    }

    public static boolean contains(String path) {
        return yaml != null && yaml.contains(path);
    }

    public static String getString(String path) {
        return yaml != null ? yaml.getString(path) : null;
    }

    public static boolean getBoolean(String path, boolean def) {
        return yaml != null ? yaml.getBoolean(path, def) : def;
    }

    public static int getInt(String path, int def) {
        return yaml != null ? yaml.getInt(path, def) : def;
    }

    public static double getDouble(String path, double def) {
        return yaml != null ? yaml.getDouble(path, def) : def;
    }

    public static FileConfiguration asBukkitConfig() {
        return yaml;
    }
}
