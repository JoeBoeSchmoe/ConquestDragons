package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.dragonHandler.DragonDifficultyModel;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.*;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Static loader for DefaultValuesConfiguration/defaultDifficultyValues.yml.
 *
 * Source of truth for default difficulty presets and their tuning keys:
 *  - speed, attack-speed, summon-speed
 *  - scale-strength, barrier-strength, summon-strength
 *  - ai
 *
 * Produces immutable DragonDifficultyModel instances keyed by DragonDifficultyKey.
 */
public final class DefaultDifficultyValuesFile {

    private static final String RESOURCE_PATH = "DefaultValuesConfiguration/defaultDifficultyValues.yml";
    private static final String DISK_DIR = "DefaultValuesConfiguration";
    private static final String DISK_NAME = "defaultDifficultyValues.yml";

    /** Immutable in-memory view of default difficulties keyed by difficulty key. */
    private static Map<DragonDifficultyKey, DragonDifficultyModel> DEFAULT_DIFFICULTIES = Collections.emptyMap();

    private DefaultDifficultyValuesFile() { }

    /** Create/refresh from jar (if missing) and load into memory. */
    public static void load() {
        ConquestDragons plugin = ConquestDragons.getInstance();
        File file = new File(plugin.getDataFolder(), DISK_DIR + File.separator + DISK_NAME);

        ensureOnDisk(plugin, file);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection difficultiesSec = cfg.getConfigurationSection("difficulties");
        if (difficultiesSec == null) {
            plugin.getLogger().warning("⚠️  defaultDifficultyValues.yml missing 'difficulties' section. Using empty map.");
            DEFAULT_DIFFICULTIES = Collections.emptyMap();
            return;
        }

        Map<DragonDifficultyKey, DragonDifficultyModel> working =
                new EnumMap<>(DragonDifficultyKey.class);

        for (String diffKeyName : difficultiesSec.getKeys(false)) {
            ConfigurationSection diffSec = difficultiesSec.getConfigurationSection(diffKeyName);
            if (diffSec == null) {
                String msg = "⚠️  Difficulty '" + diffKeyName + "' is not a section. Skipping.";
                plugin.getLogger().warning(msg);
                continue;
            }

            DragonDifficultyKey difficultyKey;
            try {
                difficultyKey = DragonDifficultyKey.valueOf(diffKeyName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                String msg = "⚠️  Unknown DragonDifficultyKey '" + diffKeyName +
                        "' in defaultDifficultyValues.yml. Skipping.";
                plugin.getLogger().warning(msg);
                continue;
            }

            // Basic fields
            String id = diffSec.getString("id", diffKeyName.toLowerCase(Locale.ROOT));
            String displayName = diffSec.getString("display-name", diffKeyName);

            // Tuning enums with safe parsing + fallbacks
            DragonSpeedKey speedKey = parseSpeedKey(plugin, diffSec, diffKeyName);
            DragonAttackSpeedKey attackSpeedKey = parseAttackSpeedKey(plugin, diffSec, diffKeyName);
            DragonSummonSpeedKey summonSpeedKey = parseSummonSpeedKey(plugin, diffSec, diffKeyName);

            DragonScaleStrength scaleStrengthKey = parseScaleStrength(plugin, diffSec, diffKeyName);
            DragonBarrierStrengthKey barrierStrengthKey = parseBarrierStrength(plugin, diffSec, diffKeyName);
            DragonSummonStrengthKey summonStrengthKey = parseSummonStrength(plugin, diffSec, diffKeyName);

            DragonAIKey aiKey = parseAIKey(plugin, diffSec, diffKeyName);

            DragonDifficultyModel model = new DragonDifficultyModel(
                    id,
                    displayName,
                    difficultyKey,
                    speedKey,
                    attackSpeedKey,
                    scaleStrengthKey,
                    barrierStrengthKey,
                    summonSpeedKey,
                    summonStrengthKey,
                    aiKey
            );

            working.put(difficultyKey, model);
        }

        DEFAULT_DIFFICULTIES = Collections.unmodifiableMap(working);
        plugin.getLogger().info("✅  Loaded " + DEFAULT_DIFFICULTIES.size() + " default difficulty preset(s).");
    }

    /** Snapshot of defaults keyed by DragonDifficultyKey. */
    public static Map<DragonDifficultyKey, DragonDifficultyModel> getDefaultDifficulties() {
        return DEFAULT_DIFFICULTIES;
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
                        empty.createSection("difficulties");
                        empty.save(file);
                    }
                } catch (IOException io) {
                    plugin.getLogger().log(Level.SEVERE, "⚠️  Failed to create " + file.getPath(), io);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: enum parsing with logging + sane fallbacks
    // ---------------------------------------------------------------------

    private static DragonSpeedKey parseSpeedKey(ConquestDragons plugin,
                                                ConfigurationSection sec,
                                                String difficultyName) {
        String raw = sec.getString("speed");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'speed'. Defaulting to MEDIUM.");
            return DragonSpeedKey.MEDIUM;
        }
        try {
            return DragonSpeedKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'speed' value '" + raw +
                            "'. Defaulting to MEDIUM.");
            return DragonSpeedKey.MEDIUM;
        }
    }

    private static DragonAttackSpeedKey parseAttackSpeedKey(ConquestDragons plugin,
                                                            ConfigurationSection sec,
                                                            String difficultyName) {
        String raw = sec.getString("attack-speed");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'attack-speed'. Defaulting to MEDIUM.");
            return DragonAttackSpeedKey.MEDIUM;
        }
        try {
            return DragonAttackSpeedKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'attack-speed' value '" + raw +
                            "'. Defaulting to MEDIUM.");
            return DragonAttackSpeedKey.MEDIUM;
        }
    }

    private static DragonSummonSpeedKey parseSummonSpeedKey(ConquestDragons plugin,
                                                            ConfigurationSection sec,
                                                            String difficultyName) {
        String raw = sec.getString("summon-speed");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'summon-speed'. Defaulting to MEDIUM.");
            return DragonSummonSpeedKey.MEDIUM;
        }
        try {
            return DragonSummonSpeedKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'summon-speed' value '" + raw +
                            "'. Defaulting to MEDIUM.");
            return DragonSummonSpeedKey.MEDIUM;
        }
    }

    private static DragonScaleStrength parseScaleStrength(ConquestDragons plugin,
                                                          ConfigurationSection sec,
                                                          String difficultyName) {
        String raw = sec.getString("scale-strength");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'scale-strength'. Defaulting to AVERAGE.");
            return DragonScaleStrength.AVERAGE;
        }
        try {
            return DragonScaleStrength.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'scale-strength' value '" + raw +
                            "'. Defaulting to AVERAGE.");
            return DragonScaleStrength.AVERAGE;
        }
    }

    private static DragonBarrierStrengthKey parseBarrierStrength(ConquestDragons plugin,
                                                                 ConfigurationSection sec,
                                                                 String difficultyName) {
        String raw = sec.getString("barrier-strength");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'barrier-strength'. Defaulting to AVERAGE.");
            return DragonBarrierStrengthKey.AVERAGE;
        }
        try {
            return DragonBarrierStrengthKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'barrier-strength' value '" + raw +
                            "'. Defaulting to AVERAGE.");
            return DragonBarrierStrengthKey.AVERAGE;
        }
    }

    private static DragonSummonStrengthKey parseSummonStrength(ConquestDragons plugin,
                                                               ConfigurationSection sec,
                                                               String difficultyName) {
        String raw = sec.getString("summon-strength");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'summon-strength'. Defaulting to AVERAGE.");
            return DragonSummonStrengthKey.AVERAGE;
        }
        try {
            return DragonSummonStrengthKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'summon-strength' value '" + raw +
                            "'. Defaulting to AVERAGE.");
            return DragonSummonStrengthKey.AVERAGE;
        }
    }

    private static DragonAIKey parseAIKey(ConquestDragons plugin,
                                          ConfigurationSection sec,
                                          String difficultyName) {
        String raw = sec.getString("ai");
        if (raw == null) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' missing 'ai'. Defaulting to AVERAGE.");
            return DragonAIKey.AVERAGE;
        }
        try {
            return DragonAIKey.fromConfig(raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(() ->
                    "⚠️  Difficulty '" + difficultyName + "' has invalid 'ai' value '" + raw +
                            "'. Defaulting to AVERAGE.");
            return DragonAIKey.AVERAGE;
        }
    }
}
