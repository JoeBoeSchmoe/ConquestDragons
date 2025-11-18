package org.conquestDragons.conquestDragons.configurationHandler;

import org.bukkit.configuration.file.FileConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.ConfigFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.dataFiles.DragonDataFiles;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.dataFiles.EventDataFiles;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultDifficultyValuesFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultDragonHealthColorsFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles.PlaceholderAPIManager;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles.VaultManager;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles.WorldGuardManager;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.messageFiles.AdminMessagesFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.messageFiles.GenericMessagesFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.messageFiles.UserMessagesFile;


import java.util.logging.Logger;

/**
 * 🧩 ConfigurationManager
 * Hub for loading config.yml and message files (generic/user/admin),
 * validating structure, and initializing external integrations (Vault, PAPI).
 *
 * Keep this class slim: it orchestrates file loaders and integrations,
 * but does not own specific business logic.
 */
public class ConfigurationManager {

    private final ConquestDragons plugin = ConquestDragons.getInstance();
    private final Logger log = plugin.getLogger();
    private FileConfiguration mainConfig;

    /**
     * Initializes all YAML files and third-party integrations.
     */
    public void initialize() {
        try {
            log.info("📦  Loading configuration and message files...");

            // 🔃 Load YAML files (create from jar if missing)
            ConfigFile.load();              // config.yml
            GenericMessagesFile.load();     // genericMessages.yml
            UserMessagesFile.load();        // userMessages.yml
            AdminMessagesFile.load();       // adminMessages.yml

            this.mainConfig = ConfigFile.getConfig();

            // ✅ Validate minimal structure (foundation keys only)
            checkAll();

            // 🔌 Integrations
            setupVault();
            setupPlaceholderAPI();
            setupWorldGuard();

            // Default Values
            DefaultDifficultyValuesFile.load();
            DefaultDragonHealthColorsFile.load();

            // Data Files
            DragonDataFiles.loadAll();
            EventDataFiles.loadAll();

            log.info("✅  Configuration loading complete.");
        } catch (Exception e) {
            log.severe("❌  Failed to load configuration: " + e.getMessage());
        }
    }

    /**
     * Validates the presence of expected top-level keys from the current base config.yml.
     * (Only foundation keys for now; expand as feature land.)
     */
    private void checkAll() {
        log.info("🔍  Validating config.yml structure...");
        // Global prefix
        check("chat-prefix");

        // Integrations
        check("integrations.economy.use-vault");
        check("integrations.placeholders.use-placeholderapi");
        check("integrations.worldguard.enable-hook");

        // Command aliases
        check("command-aliases");

        // Cooldowns (foundation naming)
        check("cooldowns.command-cooldown-ms");
        check("cooldowns.gui-action-cooldown-ms");
        check("cooldowns.interaction-cooldown-ms");

        // GUI
        check("gui.timeout-seconds");

        // Timezone
        check("time.timezone");

        // Storage
        check("storage.autosave-seconds");
        check("storage.save-on-shutdown");
    }

    /**
     * Warn if a required key is missing.
     */
    private void check(String path) {
        if (!mainConfig.contains(path)) {
            log.warning("⚠️  Missing config.yml key: '" + path + "'");
        }
    }

    /**
     * Initializes Vault if enabled (foundation toggle).
     */
    private void setupVault() {
        boolean isEnabled = mainConfig.getBoolean("integrations.economy.use-vault", true);
        VaultManager.initialize(isEnabled);
    }

    /**
     * Initializes PlaceholderAPI if enabled (foundation toggle).
     */
    private void setupPlaceholderAPI() {
        boolean isEnabled = mainConfig.getBoolean("integrations.placeholders.use-placeholderapi", true);
        PlaceholderAPIManager.initialize(isEnabled);
    }

    /**
     * Initializes WorldGuard if enabled (foundation toggle).
     */
    private void setupWorldGuard() {
        boolean isEnabled = mainConfig.getBoolean("integrations.worldguard.enable-hook");
        WorldGuardManager.initialize(isEnabled);
    }

    public FileConfiguration getConfig() {
        return mainConfig;
    }
}
