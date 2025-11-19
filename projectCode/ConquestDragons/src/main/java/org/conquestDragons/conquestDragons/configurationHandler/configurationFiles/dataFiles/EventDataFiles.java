package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.dataFiles;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.eventHandler.EventManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.eventHandler.EventStageKey;
import org.conquestDragons.conquestDragons.eventHandler.EventStageModel;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.logging.Level;

/**
 * Loader for EventData/*.yml event definition files.
 *
 * Responsibilities:
 *  - Scan EventData/ for *.yml
 *  - Load each event definition (event: root)
 *  - Resolve dragon-region, dragon-spawn, playing-areas, belly trigger, schedule, stages, rewards, etc.
 *  - Push all EventModel instances into EventManager
 *
 * This class is meant to be called from plugin startup / reload.
 */
public final class EventDataFiles {

    private static final String DATA_DIR = "EventData";

    private EventDataFiles() {
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Load all EventData/*.yml files and register the resulting EventModel
     * instances into EventManager.
     */
    public static void loadAll() {
        ConquestDragons plugin = ConquestDragons.getInstance();
        File dir = new File(plugin.getDataFolder(), DATA_DIR);

        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("⚠️  Could not create EventData directory: " + dir.getPath());
        }

        // Ensure at least one default file on first run if directory is empty
        ensureDefaultEventOnDisk(plugin, dir);

        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("⚠️  No EventData/*.yml files found. EventManager will be empty.");
            EventManager.clear();
            return;
        }

        List<EventModel> models = new ArrayList<>();

        for (File file : files) {
            try {
                EventModel model = loadSingle(plugin, file);
                if (model != null) {
                    models.add(model);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "⚠️  Failed to load event from file: " + file.getName(), ex);
            }
        }

        EventManager.reloadAll(models);
        plugin.getLogger().info("✅  Loaded " + models.size() + " event(s) into EventManager.");
    }

    // ---------------------------------------------------------------------
    // Single file loader
    // ---------------------------------------------------------------------

    private static EventModel loadSingle(ConquestDragons plugin, File file) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = cfg.getConfigurationSection("event");
        if (root == null) {
            plugin.getLogger().warning("⚠️  File '" + file.getName() + "' missing 'event' root section. Skipping.");
            return null;
        }

        String fileBaseName = stripExtension(file.getName());
        String id = fileBaseName;
        String displayName = root.getString("display-name", id);

        // -------------------------
        // Enabled flag (optional, default true)
        // -------------------------
        boolean enabled = root.getBoolean("enabled", true);

        // -------------------------
        // Keep-inventory flag (optional, default false)
        // -------------------------
        // If true, players keep inventory on death during this event.
        // If false or omitted, normal drop behavior applies.
        boolean keepInventory = root.getBoolean("keep-inventory", false);

        // -------------------------
        // Dragon IDs
        // -------------------------
        List<String> dragonIds = root.getStringList("dragon-ids");
        if (dragonIds == null) dragonIds = Collections.emptyList();

        String bossDragonId = root.getString("boss-dragon-id");
        if (bossDragonId == null || bossDragonId.isBlank()) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' missing 'boss-dragon-id'. Skipping this event.");
            return null;
        }

        // -------------------------
        // Global dragon-region (axis-aligned box for dragons)
        // -------------------------
        ConfigurationSection dragonRegionSec = root.getConfigurationSection("dragon-region");
        if (dragonRegionSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' missing 'dragon-region' section. Skipping this event.");
            return null;
        }

        String worldName = dragonRegionSec.getString("world");
        if (worldName == null || worldName.isBlank()) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' dragon-region missing 'world'. Skipping this event.");
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' references world '" + worldName +
                    "' which is not loaded. Skipping this event.");
            return null;
        }

        ConfigurationSection cornerASec = dragonRegionSec.getConfigurationSection("corner-a");
        ConfigurationSection cornerBSec = dragonRegionSec.getConfigurationSection("corner-b");
        if (cornerASec == null || cornerBSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' dragon-region missing 'corner-a' or 'corner-b'. Skipping.");
            return null;
        }

        Location dragonCornerA = parseLocation(world, cornerASec);
        Location dragonCornerB = parseLocation(world, cornerBSec);

        // -------------------------
        // Optional dragon-spawn
        // -------------------------
        Location dragonSpawn = null;
        ConfigurationSection spawnSec = root.getConfigurationSection("dragon-spawn");
        if (spawnSec != null) {
            String spawnWorldName = spawnSec.getString("world", worldName);
            World spawnWorld = Bukkit.getWorld(spawnWorldName);
            if (spawnWorld == null) {
                plugin.getLogger().warning("⚠️  Event '" + id + "' dragon-spawn references world '" +
                        spawnWorldName + "' which is not loaded. Using center of dragon-region instead.");
            } else {
                dragonSpawn = parseLocationWithOrientation(spawnWorld, spawnSec);
            }
        }

        // -------------------------
        // Optional completion-spawn
        // -------------------------
        Location completionSpawn = null;
        ConfigurationSection completionSec = root.getConfigurationSection("completion-spawn");
        if (completionSec != null) {
            String completionWorldName = completionSec.getString("world", worldName);
            World completionWorld = Bukkit.getWorld(completionWorldName);
            if (completionWorld == null) {
                plugin.getLogger().warning("⚠️  Event '" + id + "' completion-spawn references world '" +
                        completionWorldName + "' which is not loaded. Falling back to dragon-spawn / region center.");
            } else {
                completionSpawn = parseLocationWithOrientation(completionWorld, completionSec);
            }
        }

        // -------------------------
        // playing-areas (per-stage EventRegion + spawn)
        // -------------------------
        Map<EventStageKey, EventModel.StageArea> playingAreas =
                parsePlayingAreas(plugin, id, root, worldName);

        // -------------------------
        // Belly trigger
        // -------------------------
        double bellyTriggerHealthFraction =
                root.getDouble("belly-trigger-health-fraction", 0.35D);
        if (bellyTriggerHealthFraction < 0.0 || bellyTriggerHealthFraction > 1.0) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' has invalid 'belly-trigger-health-fraction' (" +
                    bellyTriggerHealthFraction + "). Clamping to [0.0, 1.0].");
            bellyTriggerHealthFraction = Math.max(0.0, Math.min(1.0, bellyTriggerHealthFraction));
        }

        // -------------------------
        // Durations
        // -------------------------
        long maxDurationSeconds = root.getLong("max-duration-seconds", 1800L);
        if (maxDurationSeconds <= 0L) maxDurationSeconds = 1L;
        Duration maxDuration = Duration.ofSeconds(maxDurationSeconds);

        long joinWindowSeconds = root.getLong("join-window-length-seconds", 300L);
        if (joinWindowSeconds < 0L) joinWindowSeconds = 0L;
        Duration joinWindowLength = Duration.ofSeconds(joinWindowSeconds);

        long joinReminderSeconds = root.getLong("join-reminder-interval-seconds", 60L);
        if (joinReminderSeconds <= 0L) joinReminderSeconds = 60L;
        Duration joinReminderInterval = Duration.ofSeconds(joinReminderSeconds);

        // NEW: in-belly-stage-duration-seconds → Duration
        long inBellyStageDurationSeconds = root.getLong("in-belly-stage-duration-seconds", 90L);
        if (inBellyStageDurationSeconds <= 0L) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' has non-positive 'in-belly-stage-duration-seconds' (" +
                    inBellyStageDurationSeconds + "). Using 1 second instead.");
            inBellyStageDurationSeconds = 1L;
        }
        Duration inBellyStageDuration = Duration.ofSeconds(inBellyStageDurationSeconds);

        // NEW: dragon-spawn-interval-seconds → Duration
        long dragonSpawnIntervalSeconds = root.getLong("dragon-spawn-interval-seconds", 0L);
        if (dragonSpawnIntervalSeconds < 0L) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' has negative 'dragon-spawn-interval-seconds' (" +
                    dragonSpawnIntervalSeconds + "). Using 0 instead.");
            dragonSpawnIntervalSeconds = 0L;
        }
        Duration dragonSpawnInterval = Duration.ofSeconds(dragonSpawnIntervalSeconds);

        // -------------------------
        // Schedule (repeat + time-of-day + pre-start-reminders)
        // -------------------------
        ConfigurationSection scheduleSec = root.getConfigurationSection("schedule");
        EventModel.EventSchedule schedule = parseSchedule(plugin, id, scheduleSec);

        // -------------------------
        // Stages (logic stages, not geometry)
        // -------------------------
        List<EventStageModel> stages = parseStages(plugin, id, root);
        if (stages.isEmpty()) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' has no stages defined. Skipping this event.");
            return null;
        }

        // -------------------------
        // Rewards (completion + ranking)
        // -------------------------
        List<EventModel.RewardSpec> completionRewards =
                parseCompletionRewards(plugin, id, root);
        List<EventModel.RankingRewardSpec> rankingRewards =
                parseRankingRewards(plugin, id, root);

        // -------------------------
        // Construct model (FULL ctor with completionSpawn + playingAreas +
        // keepInventory + inBellyStageDuration + dragonSpawnInterval)
        // -------------------------
        try {
            return new EventModel(
                    id,
                    displayName,
                    enabled,
                    keepInventory,
                    dragonIds,
                    bossDragonId,
                    dragonCornerA,
                    dragonCornerB,
                    dragonSpawn,
                    completionSpawn,   // may be null → EventModel will fall back to dragonSpawn
                    playingAreas,
                    bellyTriggerHealthFraction,
                    inBellyStageDuration,   // 🔥 NEW
                    maxDuration,
                    joinWindowLength,
                    joinReminderInterval,
                    dragonSpawnInterval,
                    schedule,
                    stages,
                    completionRewards,
                    rankingRewards
            );
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "⚠️  Invalid configuration for event '" + id + "'. Skipping this event.", ex);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: locations
    // ---------------------------------------------------------------------

    /** Simple x/y/z location (no yaw/pitch). */
    private static Location parseLocation(World world, ConfigurationSection sec) {
        double x = sec.getDouble("x");
        double y = sec.getDouble("y");
        double z = sec.getDouble("z");
        return new Location(world, x, y, z);
    }

    /** Location with optional yaw/pitch. */
    private static Location parseLocationWithOrientation(World world, ConfigurationSection sec) {
        double x = sec.getDouble("x");
        double y = sec.getDouble("y");
        double z = sec.getDouble("z");
        float yaw = (float) sec.getDouble("yaw", 0.0);
        float pitch = (float) sec.getDouble("pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    // ---------------------------------------------------------------------
    // Helpers: playing-areas → Map<EventStageKey, StageArea>
    // ---------------------------------------------------------------------

    private static Map<EventStageKey, EventModel.StageArea> parsePlayingAreas(ConquestDragons plugin,
                                                                              String eventId,
                                                                              ConfigurationSection root,
                                                                              String defaultWorldName) {
        ConfigurationSection playingAreasRoot = root.getConfigurationSection("playing-areas");
        if (playingAreasRoot == null) {
            return Collections.emptyMap();
        }

        Map<EventStageKey, EventModel.StageArea> result = new EnumMap<>(EventStageKey.class);

        for (String keyName : playingAreasRoot.getKeys(false)) {
            EventStageKey stageKey;
            try {
                stageKey = EventStageKey.valueOf(keyName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas has invalid key '" +
                        keyName + "'. Skipping this playing-area.");
                continue;
            }

            ConfigurationSection stageAreaSec = playingAreasRoot.getConfigurationSection(keyName);
            if (stageAreaSec == null) {
                continue;
            }

            ConfigurationSection regionSec = stageAreaSec.getConfigurationSection("region");
            ConfigurationSection spawnSec = stageAreaSec.getConfigurationSection("spawn");
            if (regionSec == null || spawnSec == null) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas." + keyName +
                        " missing 'region' or 'spawn'. Skipping this playing-area.");
                continue;
            }

            // Region world
            String regionWorldName = regionSec.getString("world", defaultWorldName);
            World regionWorld = Bukkit.getWorld(regionWorldName);
            if (regionWorld == null) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas." + keyName +
                        " region references world '" + regionWorldName + "' which is not loaded. Skipping.");
                continue;
            }

            ConfigurationSection regionCornerASec = regionSec.getConfigurationSection("corner-a");
            ConfigurationSection regionCornerBSec = regionSec.getConfigurationSection("corner-b");
            if (regionCornerASec == null || regionCornerBSec == null) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas." + keyName +
                        " region missing 'corner-a' or 'corner-b'. Skipping.");
                continue;
            }

            Location regionCornerA = parseLocation(regionWorld, regionCornerASec);
            Location regionCornerB = parseLocation(regionWorld, regionCornerBSec);
            EventModel.EventRegion stageRegion;
            try {
                stageRegion = new EventModel.EventRegion(regionCornerA, regionCornerB);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas." + keyName +
                        " has invalid region corners. Skipping this playing-area.");
                continue;
            }

            // Spawn world (defaults to region world)
            String spawnWorldName = spawnSec.getString("world", regionWorldName);
            World spawnWorld = Bukkit.getWorld(spawnWorldName);
            if (spawnWorld == null) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas." + keyName +
                        " spawn references world '" + spawnWorldName + "' which is not loaded. Skipping.");
                continue;
            }

            Location spawnLoc = parseLocationWithOrientation(spawnWorld, spawnSec);

            try {
                EventModel.StageArea stageArea = new EventModel.StageArea(stageRegion, spawnLoc);
                result.put(stageKey, stageArea);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' playing-areas." + keyName +
                        " has invalid StageArea (region/spawn mismatch). Skipping.");
            }
        }

        return result;
    }

    // ---------------------------------------------------------------------
    // Helpers: schedule → EventSchedule
    // ---------------------------------------------------------------------

    private static EventModel.EventSchedule parseSchedule(ConquestDragons plugin,
                                                          String eventId,
                                                          ConfigurationSection scheduleSec) {
        EventModel.RepeatType repeatType = EventModel.RepeatType.DAILY;
        LocalTime timeOfDay = LocalTime.MIDNIGHT;
        DayOfWeek dayOfWeek = null;
        int dayOfMonth = 0;
        List<Duration> preStartOffsets = Collections.emptyList();

        if (scheduleSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + eventId + "' missing 'schedule' section. " +
                    "Defaulting to DAILY at 00:00:00.");
            return new EventModel.EventSchedule(repeatType, timeOfDay, dayOfWeek, dayOfMonth, preStartOffsets);
        }

        // repeat
        String repeatRaw = scheduleSec.getString("repeat", "DAILY");
        try {
            repeatType = EventModel.RepeatType.valueOf(repeatRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("⚠️  Event '" + eventId + "' has invalid schedule.repeat '" +
                    repeatRaw + "'. Defaulting to DAILY.");
            repeatType = EventModel.RepeatType.DAILY;
        }

        // time
        ConfigurationSection timeSec = scheduleSec.getConfigurationSection("time");
        if (timeSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + eventId + "' schedule missing 'time' section. " +
                    "Using 00:00:00.");
        } else {
            int hour = timeSec.getInt("hour", 0);
            int minute = timeSec.getInt("minute", 0);
            int second = timeSec.getInt("second", 0);

            try {
                timeOfDay = LocalTime.of(hour, minute, second);
            } catch (Exception ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' schedule has invalid time (" +
                        hour + ":" + minute + ":" + second + "). Using 00:00:00.");
                timeOfDay = LocalTime.MIDNIGHT;
            }
        }

        // weekly / monthly extra fields
        if (repeatType == EventModel.RepeatType.WEEKLY) {
            String dowRaw = scheduleSec.getString("day-of-week");
            if (dowRaw == null || dowRaw.isBlank()) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' repeat=WEEKLY but no 'day-of-week' set. " +
                        "It will behave like DAILY until configured.");
            } else {
                try {
                    dayOfWeek = DayOfWeek.valueOf(dowRaw.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("⚠️  Event '" + eventId + "' has invalid day-of-week '" +
                            dowRaw + "'. It will behave like DAILY until corrected.");
                }
            }
        } else if (repeatType == EventModel.RepeatType.MONTHLY) {
            int dom = scheduleSec.getInt("day-of-month", 1);
            if (dom < 1 || dom > 31) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' has invalid day-of-month (" +
                        dom + "). Clamping to [1,31].");
                dom = Math.max(1, Math.min(31, dom));
            }
            dayOfMonth = dom;
        }

        // pre-start-reminders: list of "1H", "30M", "5M", "1M" etc.
        preStartOffsets = parsePreStartOffsets(plugin, eventId, scheduleSec);

        return new EventModel.EventSchedule(repeatType, timeOfDay, dayOfWeek, dayOfMonth, preStartOffsets);
    }

    private static List<Duration> parsePreStartOffsets(ConquestDragons plugin,
                                                       String eventId,
                                                       ConfigurationSection scheduleSec) {
        List<String> rawList = scheduleSec.getStringList("pre-start-reminders");
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Duration> out = new ArrayList<>();
        for (String raw : rawList) {
            if (raw == null || raw.isBlank()) continue;
            Duration d = parseSimpleDuration(raw.trim());
            if (d == null || d.isNegative() || d.isZero()) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' has invalid pre-start-reminder '" +
                        raw + "'. Expected something like '1H', '30M', '15M', '5M', '1M'. Skipping.");
                continue;
            }
            out.add(d);
        }

        // Sort largest ⇒ smallest so scheduler can consume in order if desired
        out.sort(Comparator.comparingLong(Duration::getSeconds).reversed());
        return Collections.unmodifiableList(out);
    }

    /**
     * Parse very simple duration strings like "1H", "30M", "10S".
     * Upper/lowercase allowed; no spaces.
     */
    private static Duration parseSimpleDuration(String raw) {
        if (raw.isEmpty()) return null;
        char unit = Character.toUpperCase(raw.charAt(raw.length() - 1));
        String numPart = raw.substring(0, raw.length() - 1);
        long value;
        try {
            value = Long.parseLong(numPart);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (value <= 0L) return null;
        return switch (unit) {
            case 'H' -> Duration.ofHours(value);
            case 'M' -> Duration.ofMinutes(value);
            case 'S' -> Duration.ofSeconds(value);
            default -> null;
        };
    }

    // ---------------------------------------------------------------------
    // Helpers: stages (YAML list)
    // ---------------------------------------------------------------------

    private static List<EventStageModel> parseStages(ConquestDragons plugin,
                                                     String eventId,
                                                     ConfigurationSection root) {
        // "stages" is a YAML list ( - key: INITIAL ... )
        List<Map<?, ?>> rawStages = root.getMapList("stages");
        if (rawStages == null || rawStages.isEmpty()) {
            return Collections.emptyList();
        }

        List<EventStageModel> result = new ArrayList<>();
        int index = 0;

        for (Map<?, ?> stageMap : rawStages) {
            index++;
            if (stageMap == null || stageMap.isEmpty()) {
                continue;
            }

            Object keyObj = stageMap.get("key");
            if (keyObj == null) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' stage #" + index +
                        " missing 'key'. Skipping this stage.");
                continue;
            }

            String keyRaw = String.valueOf(keyObj);
            EventStageKey stageKey;
            try {
                stageKey = EventStageKey.valueOf(keyRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' stage #" + index +
                        " has invalid key '" + keyRaw + "'. Skipping this stage.");
                continue;
            }

            List<String> startCommands = toStringList(stageMap.get("start-commands"));
            List<String> endCommands   = toStringList(stageMap.get("end-commands"));

            List<EventStageModel.TimedCommandSpec> timedCommands =
                    parseTimedCommands(plugin, eventId, stageKey, stageMap.get("timed-commands"));

            // NEW: single repeat-message spec (0 or invalid = disabled → null)
            EventStageModel.RepeatMessageSpec repeatMessage =
                    parseRepeatMessage(plugin, eventId, stageKey, stageMap.get("repeat-message"));

            EventStageModel stageModel = new EventStageModel(
                    stageKey,
                    startCommands,
                    timedCommands,
                    endCommands,
                    repeatMessage
            );
            result.add(stageModel);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<EventStageModel.TimedCommandSpec> parseTimedCommands(ConquestDragons plugin,
                                                                             String eventId,
                                                                             EventStageKey stageKey,
                                                                             Object rawTimedObj) {
        List<EventStageModel.TimedCommandSpec> result = new ArrayList<>();

        if (!(rawTimedObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return result;
        }

        int index = 0;
        for (Object o : rawList) {
            index++;
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }

            Object delayObj = raw.get("delay-ticks");
            long delayTicks;
            if (delayObj instanceof Number num) {
                delayTicks = num.longValue();
            } else {
                try {
                    delayTicks = Long.parseLong(String.valueOf(delayObj));
                } catch (Exception ex) {
                    plugin.getLogger().warning("⚠️  Event '" + eventId + "' stage '" + stageKey +
                            "' timed-commands[" + index + "] has invalid 'delay-ticks'. Skipping this batch.");
                    continue;
                }
            }

            List<String> commands = toStringList(raw.get("commands"));
            if (commands.isEmpty()) {
                continue;
            }

            try {
                result.add(new EventStageModel.TimedCommandSpec(delayTicks, commands));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' stage '" + stageKey +
                        "' timed-commands[" + index + "] has invalid delay: " + delayTicks);
            }
        }

        return result;
    }

    /**
     * Parse the per-stage repeat-message block:
     *
     *   repeat-message:
     *     delay-ticks: 300   # 0 or missing = disabled
     */
    private static EventStageModel.RepeatMessageSpec parseRepeatMessage(ConquestDragons plugin,
                                                                        String eventId,
                                                                        EventStageKey stageKey,
                                                                        Object rawRepeatObj) {
        if (!(rawRepeatObj instanceof Map<?, ?> raw)) {
            return null;
        }

        Object delayObj = raw.get("delay-ticks");
        if (delayObj == null) {
            return null;
        }

        long delayTicks;
        if (delayObj instanceof Number num) {
            delayTicks = num.longValue();
        } else {
            try {
                delayTicks = Long.parseLong(String.valueOf(delayObj));
            } catch (Exception ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' stage '" + stageKey +
                        "' repeat-message has invalid 'delay-ticks'. Skipping repeat-message.");
                return null;
            }
        }

        // 0 or negative = disabled, as per design
        if (delayTicks <= 0L) {
            return null;
        }

        try {
            return new EventStageModel.RepeatMessageSpec(delayTicks);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("⚠️  Event '" + eventId + "' stage '" + stageKey +
                    "' repeat-message has invalid delay-ticks=" + delayTicks + ". Skipping repeat-message.");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (!(obj instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------------
    // Helpers: rewards
    // ---------------------------------------------------------------------

    private static List<EventModel.RewardSpec> parseCompletionRewards(ConquestDragons plugin,
                                                                      String eventId,
                                                                      ConfigurationSection root) {
        ConfigurationSection rewardsSec = root.getConfigurationSection("rewards");
        if (rewardsSec == null) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> rawList = rewardsSec.getMapList("completion");
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<EventModel.RewardSpec> result = new ArrayList<>();
        int index = 0;

        for (Map<?, ?> map : rawList) {
            index++;
            if (map == null || map.isEmpty()) continue;

            double chancePercent;
            Object chanceObj = map.get("chance");
            if (chanceObj instanceof Number num) {
                chancePercent = num.doubleValue();
            } else {
                try {
                    chancePercent = Double.parseDouble(String.valueOf(chanceObj));
                } catch (Exception ex) {
                    plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.completion[" + index +
                            "] has invalid 'chance'. Skipping this reward.");
                    continue;
                }
            }

            List<String> commands = toStringList(map.get("commands"));
            if (commands.isEmpty()) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.completion[" + index +
                        "] has no commands. Skipping this reward.");
                continue;
            }

            try {
                result.add(new EventModel.RewardSpec(chancePercent, commands));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.completion[" + index +
                        "] is invalid: " + ex.getMessage());
            }
        }

        return Collections.unmodifiableList(result);
    }

    private static List<EventModel.RankingRewardSpec> parseRankingRewards(ConquestDragons plugin,
                                                                          String eventId,
                                                                          ConfigurationSection root) {
        ConfigurationSection rewardsSec = root.getConfigurationSection("rewards");
        if (rewardsSec == null) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> rawList = rewardsSec.getMapList("ranking");
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<EventModel.RankingRewardSpec> result = new ArrayList<>();
        int index = 0;

        for (Map<?, ?> map : rawList) {
            index++;
            if (map == null || map.isEmpty()) continue;

            int rank;
            Object rankObj = map.get("rank");
            if (rankObj instanceof Number numRank) {
                rank = numRank.intValue();
            } else {
                try {
                    rank = Integer.parseInt(String.valueOf(rankObj));
                } catch (Exception ex) {
                    plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.ranking[" + index +
                            "] has invalid 'rank'. Skipping this reward.");
                    continue;
                }
            }

            double chancePercent;
            Object chanceObj = map.get("chance");
            if (chanceObj instanceof Number numChance) {
                chancePercent = numChance.doubleValue();
            } else {
                try {
                    chancePercent = Double.parseDouble(String.valueOf(chanceObj));
                } catch (Exception ex) {
                    plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.ranking[" + index +
                            "] has invalid 'chance'. Skipping this reward.");
                    continue;
                }
            }

            List<String> commands = toStringList(map.get("commands"));
            if (commands.isEmpty()) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.ranking[" + index +
                        "] has no commands. Skipping this reward.");
                continue;
            }

            try {
                result.add(new EventModel.RankingRewardSpec(rank, chancePercent, commands));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("⚠️  Event '" + eventId + "' rewards.ranking[" + index +
                        "] is invalid: " + ex.getMessage());
            }
        }

        // Sort by rank ascending just for consistent ordering
        result.sort(Comparator.comparingInt(EventModel.RankingRewardSpec::rank));
        return Collections.unmodifiableList(result);
    }

    // ---------------------------------------------------------------------
    // Helpers: ensure default file on disk
    // ---------------------------------------------------------------------

    private static void ensureDefaultEventOnDisk(ConquestDragons plugin, File dir) {
        File[] existing = dir.listFiles();
        if (existing != null && existing.length > 0) {
            return; // Something already there, don't overwrite
        }

        try {
            plugin.saveResource("EventData/defaultEvent.yml", false);
            plugin.getLogger().info("✅  Placed EventData/defaultEvent.yml onto disk.");
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(
                    "⚠️  Missing bundled resource 'EventData/defaultEvent.yml' in the jar. " +
                            "No default events will be created.");
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
