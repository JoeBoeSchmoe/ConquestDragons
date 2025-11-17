package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.dataFiles;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultDifficultyValuesFile;
import org.conquestDragons.conquestDragons.dragonHandler.DragonDifficultyModel;
import org.conquestDragons.conquestDragons.dragonHandler.DragonManager;
import org.conquestDragons.conquestDragons.dragonHandler.DragonModel;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonAIKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonDifficultyKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonGlowColorHealthKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonSpeedKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonAttackSpeedKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonScaleStrength;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonBarrierStrengthKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonSummonSpeedKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonSummonStrengthKey;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/**
 * Loader for DragonData/*.yml dragon definition files.
 *
 * Responsibilities:
 *  - Scan DragonData/ for *.yml
 *  - Load each dragon definition
 *  - Resolve difficulty (preset or custom)
 *  - Resolve glow/bossbar profiles
 *  - Push all DragonModel instances into DragonManager
 *
 * This class is meant to be called from plugin startup / reload.
 */
public final class DragonDataFiles {

    private static final String DATA_DIR = "DragonData";

    private DragonDataFiles() {
        // utility
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Load all DragonData/*.yml files and register the resulting DragonModel
     * instances into DragonManager.
     */
    public static void loadAll() {
        ConquestDragons plugin = ConquestDragons.getInstance();
        File dir = new File(plugin.getDataFolder(), DATA_DIR);

        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("⚠️  Could not create DragonData directory: " + dir.getPath());
        }

        // Ensure at least one default file on first run if directory is empty
        ensureDefaultDragonOnDisk(plugin, dir);

        // Now actually list all *.yml files
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("⚠️  No DragonData/*.yml files found. DragonManager will be empty.");
            DragonManager.clear();
            return;
        }

        // Make sure default difficulties are loaded before this is called.
        Map<DragonDifficultyKey, DragonDifficultyModel> defaults =
                DefaultDifficultyValuesFile.getDefaultDifficulties();

        List<DragonModel> models = new ArrayList<>();

        for (File file : files) {
            try {
                DragonModel model = loadSingle(plugin, file, defaults);
                if (model != null) {
                    models.add(model);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "⚠️  Failed to load dragon from file: " + file.getName(), ex);
            }
        }

        DragonManager.reloadAll(models);
        plugin.getLogger().info("✅  Loaded " + models.size() + " dragon(s) into DragonManager.");
    }

    // ---------------------------------------------------------------------
    // Single file loader
    // ---------------------------------------------------------------------

    private static DragonModel loadSingle(ConquestDragons plugin,
                                          File file,
                                          Map<DragonDifficultyKey, DragonDifficultyModel> defaults) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = cfg.getConfigurationSection("dragon");
        if (root == null) {
            plugin.getLogger().warning("⚠️  File '" + file.getName() + "' missing 'dragon' root section. Skipping.");
            return null;
        }

        String fileBaseName = stripExtension(file.getName());

        // ID is ALWAYS the file base name (no id in YAML)
        String id = fileBaseName;

        // Display name is configurable, falls back to id
        String displayName = root.getString("display-name", id);

        // -------------------------
        // Difficulty
        // -------------------------
        String diffStr = root.getString("difficulty", "MEDIUM");
        final String diffStrFinal = diffStr;   // lambda-safe
        final String idFinal = id;             // lambda-safe

        DragonDifficultyKey difficultyKey;
        try {
            difficultyKey = DragonDifficultyKey.valueOf(diffStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + idFinal + "' has invalid difficulty '" + diffStrFinal +
                            "'. Defaulting to MEDIUM.");
            difficultyKey = DragonDifficultyKey.MEDIUM;
        }

        DragonDifficultyModel difficultyModel;

        if (difficultyKey == DragonDifficultyKey.CUSTOM) {
            difficultyModel = parseCustomDifficulty(plugin, root, id, defaults);
        } else {
            difficultyModel = defaults.get(difficultyKey);
            if (difficultyModel == null) {
                final DragonDifficultyKey missingKey = difficultyKey; // lambda-safe

                plugin.getLogger().warning(() ->
                        "⚠️  No default difficulty '" + missingKey +
                                "' found for dragon '" + idFinal + "'. Falling back to MEDIUM.");

                difficultyModel = defaults.getOrDefault(
                        DragonDifficultyKey.MEDIUM,
                        createFallbackMediumDifficulty()
                );
            }
        }

        // -------------------------
        // Health color profiles
        // -------------------------
        DragonGlowColorHealthKey glowKey = parseGlowProfile(plugin, root, id);
        DragonGlowColorHealthKey bossbarKey = parseBossbarProfile(plugin, root, id);

        return new DragonModel(
                id,
                displayName,
                difficultyModel,
                glowKey,
                bossbarKey
        );
    }

    // ---------------------------------------------------------------------
    // Custom difficulty parsing
    // ---------------------------------------------------------------------

    /**
     * Parse a custom difficulty block from a dragon's YAML,
     * or fall back to MEDIUM preset if missing/broken.
     *
     * Note: DragonDifficultyModel no longer has its own id; it is purely
     * a bundle of behavior knobs. The owning DragonModel carries identity.
     */
    private static DragonDifficultyModel parseCustomDifficulty(ConquestDragons plugin,
                                                               ConfigurationSection root,
                                                               String dragonId,
                                                               Map<DragonDifficultyKey, DragonDifficultyModel> defaults) {
        ConfigurationSection customSec = root.getConfigurationSection("custom-difficulty");
        if (customSec == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' has difficulty CUSTOM but no 'custom-difficulty' section. " +
                            "Falling back to MEDIUM preset.");
            DragonDifficultyModel base = defaults.getOrDefault(
                    DragonDifficultyKey.MEDIUM,
                    createFallbackMediumDifficulty()
            );
            // Retag key as CUSTOM for this dragon
            return base.withKey(DragonDifficultyKey.CUSTOM);
        }

        String diffDisplayName = customSec.getString(
                "display-name",
                "<yellow>" + dragonId + " Custom</yellow>"
        );

        DragonDifficultyKey key = DragonDifficultyKey.CUSTOM;

        // Fall back to MEDIUM's knobs if any field is missing/invalid
        DragonDifficultyModel base = defaults.getOrDefault(
                DragonDifficultyKey.MEDIUM,
                createFallbackMediumDifficulty()
        );

        DragonSpeedKey speedKey = parseSpeedKey(plugin, customSec, dragonId, base.speedKey());
        DragonAttackSpeedKey attackKey = parseAttackSpeedKey(plugin, customSec, dragonId, base.attackSpeedKey());
        DragonScaleStrength scaleKey = parseScaleStrength(plugin, customSec, dragonId, base.scaleStrengthKey());
        DragonBarrierStrengthKey barrierKey = parseBarrierStrength(plugin, customSec, dragonId, base.barrierKey());
        DragonSummonSpeedKey summonSpeedKey = parseSummonSpeedKey(plugin, customSec, dragonId, base.summonSpeedKey());
        DragonSummonStrengthKey summonStrengthKey = parseSummonStrength(plugin, customSec, dragonId, base.summonStrengthKey());
        DragonAIKey aiKey = parseAIKey(plugin, customSec, dragonId, base.aiKey());

        return new DragonDifficultyModel(
                diffDisplayName,
                key,
                speedKey,
                attackKey,
                scaleKey,
                barrierKey,
                summonSpeedKey,
                summonStrengthKey,
                aiKey
        );
    }

    // ---------------------------------------------------------------------
    // Profile key parsing
    // ---------------------------------------------------------------------

    private static DragonGlowColorHealthKey parseGlowProfile(ConquestDragons plugin,
                                                             ConfigurationSection root,
                                                             String dragonId) {
        String raw = root.getString("glow-profile", "SIMPLE");
        try {
            return DragonGlowColorHealthKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' has invalid 'glow-profile' value '" + raw +
                            "'. Defaulting to SIMPLE.");
            return DragonGlowColorHealthKey.SIMPLE;
        }
    }

    private static DragonGlowColorHealthKey parseBossbarProfile(ConquestDragons plugin,
                                                                ConfigurationSection root,
                                                                String dragonId) {
        String raw = root.getString("bossbar-profile", "SIMPLE");
        try {
            return DragonGlowColorHealthKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' has invalid 'bossbar-profile' value '" + raw +
                            "'. Defaulting to SIMPLE.");
            return DragonGlowColorHealthKey.SIMPLE;
        }
    }

    // ---------------------------------------------------------------------
    // Difficulty field parsing helpers
    // ---------------------------------------------------------------------

    private static DragonSpeedKey parseSpeedKey(ConquestDragons plugin,
                                                ConfigurationSection sec,
                                                String dragonId,
                                                DragonSpeedKey fallback) {
        String raw = sec.getString("speed");
        if (raw == null) return fallback;
        try {
            return DragonSpeedKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'speed' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    private static DragonAttackSpeedKey parseAttackSpeedKey(ConquestDragons plugin,
                                                            ConfigurationSection sec,
                                                            String dragonId,
                                                            DragonAttackSpeedKey fallback) {
        String raw = sec.getString("attack-speed");
        if (raw == null) return fallback;
        try {
            return DragonAttackSpeedKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'attack-speed' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    private static DragonScaleStrength parseScaleStrength(ConquestDragons plugin,
                                                          ConfigurationSection sec,
                                                          String dragonId,
                                                          DragonScaleStrength fallback) {
        String raw = sec.getString("scale-strength");
        if (raw == null) return fallback;
        try {
            return DragonScaleStrength.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'scale-strength' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    private static DragonBarrierStrengthKey parseBarrierStrength(ConquestDragons plugin,
                                                                 ConfigurationSection sec,
                                                                 String dragonId,
                                                                 DragonBarrierStrengthKey fallback) {
        String raw = sec.getString("barrier-strength");
        if (raw == null) return fallback;
        try {
            return DragonBarrierStrengthKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'barrier-strength' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    private static DragonSummonSpeedKey parseSummonSpeedKey(ConquestDragons plugin,
                                                            ConfigurationSection sec,
                                                            String dragonId,
                                                            DragonSummonSpeedKey fallback) {
        String raw = sec.getString("summon-speed");
        if (raw == null) return fallback;
        try {
            return DragonSummonSpeedKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'summon-speed' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    private static DragonSummonStrengthKey parseSummonStrength(ConquestDragons plugin,
                                                               ConfigurationSection sec,
                                                               String dragonId,
                                                               DragonSummonStrengthKey fallback) {
        String raw = sec.getString("summon-strength");
        if (raw == null) return fallback;
        try {
            return DragonSummonStrengthKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'summon-strength' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    private static DragonAIKey parseAIKey(ConquestDragons plugin,
                                          ConfigurationSection sec,
                                          String dragonId,
                                          DragonAIKey fallback) {
        String raw = sec.getString("ai");
        if (raw == null) return fallback;
        try {
            return DragonAIKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Dragon '" + dragonId + "' custom-difficulty has invalid 'ai' value '" + raw +
                            "'. Using fallback: " + fallback);
            return fallback;
        }
    }

    // ---------------------------------------------------------------------
    // Fallback difficulty if defaults are missing/broken
    // ---------------------------------------------------------------------

    private static DragonDifficultyModel createFallbackMediumDifficulty() {
        return new DragonDifficultyModel(
                "<yellow>Medium</yellow>",
                DragonDifficultyKey.MEDIUM,
                DragonSpeedKey.MEDIUM,
                DragonAttackSpeedKey.MEDIUM,
                DragonScaleStrength.AVERAGE,
                DragonBarrierStrengthKey.AVERAGE,
                DragonSummonSpeedKey.MEDIUM,
                DragonSummonStrengthKey.AVERAGE,
                DragonAIKey.AVERAGE
        );
    }

    // ---------------------------------------------------------------------
    // Helpers: ensure default files on disk
    // ---------------------------------------------------------------------

    private static void ensureDefaultDragonOnDisk(ConquestDragons plugin, File dir) {
        File[] existing = dir.listFiles();
        if (existing != null && existing.length > 0) {
            return; // Something already there, don't overwrite
        }

        try {
            plugin.saveResource("DragonData/defaultDragon.yml", false);
            plugin.getLogger().info("✅  Placed default DragonData/defaultDragon.yml onto disk.");
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(
                    "⚠️  Missing bundled resource 'DragonData/defaultDragon.yml' in the jar. " +
                            "No default dragons will be created.");
        }

        // Also drop the boss dragon if bundled
        try {
            plugin.saveResource("DragonData/defaultBossDragon.yml", false);
            plugin.getLogger().info("✅  Placed default DragonData/defaultBossDragon.yml onto disk.");
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(
                    "⚠️  Missing bundled resource 'DragonData/defaultBossDragon.yml' in the jar.");
        }
    }

    // ---------------------------------------------------------------------
    // Small helper
    // ---------------------------------------------------------------------

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot == -1) ? name : name.substring(0, dot);
    }
}
