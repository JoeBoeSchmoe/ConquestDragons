package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.messageFiles;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * 💬 UserMessagesFile
 * - JAR resource at: resources/messagesConfiguration/userMessages.yml
 * - Disk path at: <dataFolder>/messagesConfiguration/userMessages.yml
 */
public final class UserMessagesFile {

    private static final ConquestDragons plugin = ConquestDragons.getInstance();
    private static final Logger log = plugin.getLogger();

    // JAR resource path (inside your src/main/resources)
    private static final String RESOURCE_PATH = "messagesConfiguration/userMessages.yml";
    // Disk-relative path (under the plugin's data folder)
    private static final String DATA_RELATIVE_PATH = "messagesConfiguration/userMessages.yml";

    private static YamlConfiguration yaml;
    private static File file;

    private UserMessagesFile() {}

    public static void load() {
        try {
            // Correct location under the data folder
            file = new File(plugin.getDataFolder(), DATA_RELATIVE_PATH);

            // Ensure parent folder exists
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warning("⚠️  Failed to create directory: " + parent.getAbsolutePath());
            }

            // If missing on disk, copy the default from the JAR (creates parents automatically)
            if (!file.exists()) {
                if (plugin.getResource(RESOURCE_PATH) != null) {
                    plugin.saveResource(RESOURCE_PATH, false);
                    log.info("📄  Created default " + DATA_RELATIVE_PATH);
                } else {
                    // No embedded default -> create empty
                    if (file.createNewFile()) {
                        log.warning("ℹ️  Created empty " + DATA_RELATIVE_PATH + " (no defaults found in JAR).");
                    }
                }
            }

            // Load from disk
            yaml = YamlConfiguration.loadConfiguration(file);

            // Wire defaults from the JAR so get*() falls back when keys are missing
            try (InputStream defStream = plugin.getResource(RESOURCE_PATH)) {
                if (defStream != null) {
                    YamlConfiguration def = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(defStream, StandardCharsets.UTF_8));
                    yaml.setDefaults(def);
                    yaml.options().copyDefaults(true);
                }
            }

            log.info("✅  Loaded " + DATA_RELATIVE_PATH + " successfully.");
        } catch (Exception e) {
            log.severe("❌  Failed to load " + DATA_RELATIVE_PATH + ": " + e.getMessage());
        }
    }

    public static void reload() {
        if (file == null) { load(); return; }
        yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream defStream = plugin.getResource(RESOURCE_PATH)) {
            if (defStream != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                yaml.setDefaults(def);
                yaml.options().copyDefaults(true);
            }
        } catch (Exception ignored) {}
        log.info("🔁  Reloaded " + DATA_RELATIVE_PATH + ".");
    }

    public static void save() {
        if (yaml == null || file == null) return;
        try {
            yaml.save(file);
            log.info("💾  Saved " + DATA_RELATIVE_PATH + ".");
        } catch (Exception e) {
            log.severe("❌  Failed to save " + DATA_RELATIVE_PATH + ": " + e.getMessage());
        }
    }

    // -------- Accessors --------
    public static YamlConfiguration get() { return yaml; }

    public static boolean contains(String path) { return yaml != null && yaml.contains(path); }

    public static String getString(String path, String def) { return yaml != null ? yaml.getString(path, def) : def; }

    public static List<String> getStringList(String path) { return yaml != null ? yaml.getStringList(path) : Collections.emptyList(); }

    public static ConfigurationSection getSection(String path) { return yaml != null ? yaml.getConfigurationSection(path) : null; }
}
