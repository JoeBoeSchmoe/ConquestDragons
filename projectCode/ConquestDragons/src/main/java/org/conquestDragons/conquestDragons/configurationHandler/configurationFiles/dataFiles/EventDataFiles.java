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
 *  - Resolve arena, belly trigger, schedule, stages, etc.
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
        // Arena region
        // -------------------------
        ConfigurationSection arenaSec = root.getConfigurationSection("arena");
        if (arenaSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' missing 'arena' section. Skipping this event.");
            return null;
        }

        String worldName = arenaSec.getString("world");
        if (worldName == null || worldName.isBlank()) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' arena missing 'world'. Skipping this event.");
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' references world '" + worldName +
                    "' which is not loaded. Skipping this event.");
            return null;
        }

        ConfigurationSection cornerASec = arenaSec.getConfigurationSection("corner-a");
        ConfigurationSection cornerBSec = arenaSec.getConfigurationSection("corner-b");
        if (cornerASec == null || cornerBSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' arena missing 'corner-a' or 'corner-b'. Skipping.");
            return null;
        }

        Location cornerA = parseLocation(world, cornerASec);
        Location cornerB = parseLocation(world, cornerBSec);

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

        // -------------------------
        // Schedule (repeat + time-of-day)
        // -------------------------
        ConfigurationSection scheduleSec = root.getConfigurationSection("schedule");
        EventModel.EventSchedule schedule = parseSchedule(plugin, id, scheduleSec);

        // -------------------------
        // Stages
        // -------------------------
        List<EventStageModel> stages = parseStages(plugin, id, root);
        if (stages.isEmpty()) {
            plugin.getLogger().warning("⚠️  Event '" + id + "' has no stages defined. Skipping this event.");
            return null;
        }

        // -------------------------
        // Construct model
        // -------------------------
        try {
            return new EventModel(
                    id,
                    displayName,
                    dragonIds,
                    bossDragonId,
                    cornerA,
                    cornerB,
                    bellyTriggerHealthFraction,
                    maxDuration,
                    joinWindowLength,
                    joinReminderInterval,
                    schedule,
                    stages
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

    private static Location parseLocation(World world, ConfigurationSection sec) {
        double x = sec.getDouble("x");
        double y = sec.getDouble("y");
        double z = sec.getDouble("z");
        return new Location(world, x, y, z);
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

        if (scheduleSec == null) {
            plugin.getLogger().warning("⚠️  Event '" + eventId + "' missing 'schedule' section. " +
                    "Defaulting to DAILY at 00:00:00.");
            return new EventModel.EventSchedule(repeatType, timeOfDay, dayOfWeek, dayOfMonth);
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

        return new EventModel.EventSchedule(repeatType, timeOfDay, dayOfWeek, dayOfMonth);
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
            List<String> endCommands = toStringList(stageMap.get("end-commands"));

            List<EventStageModel.TimedCommandSpec> timedCommands =
                    parseTimedCommands(plugin, eventId, stageKey, stageMap.get("timed-commands"));

            EventStageModel stageModel = new EventStageModel(
                    stageKey,
                    startCommands,
                    timedCommands,
                    endCommands
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
    // Helpers: ensure default file on disk
    // ---------------------------------------------------------------------

    private static void ensureDefaultEventOnDisk(ConquestDragons plugin, File dir) {
        File[] existing = dir.listFiles();
        if (existing != null && existing.length > 0) {
            return; // Something already there, don't overwrite
        }

        try {
            plugin.saveResource("EventData/defaultEvent.yml", false);
            plugin.getLogger().info("✅  Placed default EventData/defaultEvent.yml onto disk.");
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
