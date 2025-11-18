package org.conquestDragons.conquestDragons.eventHandler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.ConfigFile;
import org.conquestDragons.conquestDragons.responseHandler.MessageResponseManager;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.UserMessageModels;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * EventSequenceManager
 *
 * Central runtime coordinator for scheduled dragon events.
 */
public final class EventSequenceManager {

    // ---------------------------------------------------
    // Singleton
    // ---------------------------------------------------

    private static EventSequenceManager INSTANCE;

    public static EventSequenceManager getInstance() {
        return INSTANCE;
    }

    /**
     * Start the manager and its tick task.
     */
    public static synchronized EventSequenceManager start() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        ZoneId zone = resolveZoneFromConfig();
        INSTANCE = new EventSequenceManager(zone);
        INSTANCE.startTickTask();

        ConquestDragons.getInstance().getLogger().info(
                "✅  EventSequenceManager started (zone=" + zone + ")."
        );
        return INSTANCE;
    }

    /**
     * Stop the manager and clear all runtime state.
     */
    public static synchronized void stop() {
        if (INSTANCE == null) return;
        INSTANCE.stopTickTask();
        INSTANCE.runsByEventId.clear();
        INSTANCE.plugin.getLogger().info("✅  EventSequenceManager stopped.");
        INSTANCE = null;
    }

    // ---------------------------------------------------
    // Fields
    // ---------------------------------------------------

    private final ConquestDragons plugin;
    private final ZoneId zoneId;
    private BukkitTask tickTask;

    /**
     * Per-event runtime state keyed by event id.
     */
    private final ConcurrentMap<String, ScheduledRun> runsByEventId = new ConcurrentHashMap<>();

    private EventSequenceManager(ZoneId zoneId) {
        this.plugin = Objects.requireNonNull(ConquestDragons.getInstance(), "plugin");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    // ---------------------------------------------------
    // Config-based timezone resolution
    // ---------------------------------------------------

    /**
     * Resolve the timezone configured in config.yml under time.timezone.
     * Falls back to system default if invalid or missing.
     */
    private static ZoneId resolveZoneFromConfig() {
        ConquestDragons plugin = ConquestDragons.getInstance();
        FileConfiguration cfg = ConfigFile.getConfig();

        String rawId = cfg.getString("time.timezone", "UTC");
        try {
            ZoneId zone = ZoneId.of(rawId);
            plugin.getLogger().info("⏱️  Using configured timezone: " + rawId);
            return zone;
        } catch (Exception ex) {
            ZoneId fallback = ZoneId.systemDefault();
            plugin.getLogger().warning(
                    "⚠️  Invalid time.timezone value '" + rawId + "' in config.yml; " +
                            "falling back to system default: " + fallback
            );
            return fallback;
        }
    }

    // ---------------------------------------------------
    // Tick task lifecycle
    // ---------------------------------------------------

    private void startTickTask() {
        if (tickTask != null) {
            return;
        }
        // Tick once per second (20 ticks) on the main thread.
        tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                20L
        );
    }

    private void stopTickTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    // ---------------------------------------------------
    // Core tick
    // ---------------------------------------------------

    /**
     * Main periodic loop. Called from a Bukkit repeating task.
     */
    private void tick() {
        Instant now = Instant.now();

        // Snapshot of all events from EventManager
        Collection<EventModel> events = EventManager.all();

        for (EventModel event : events) {
            if (!event.enabled()) {
                // If disabled, drop any existing scheduled run.
                runsByEventId.remove(event.id());
                continue;
            }

            // Either reuse existing run, or create a new one for this event.
            ScheduledRun run = runsByEventId.computeIfAbsent(
                    event.id(),
                    id -> ScheduledRun.forEvent(event, zoneId, now)
            );

            // If the current run is fully completed, roll to the next one.
            if (run.isCompleted(now)) {
                run = ScheduledRun.forEvent(event, zoneId, now);
                runsByEventId.put(event.id(), run);
            }

            processRun(event, run, now);
        }
    }

    private void processRun(EventModel event, ScheduledRun run, Instant now) {
        // 1) Pre-start heads-up reminders
        run.fireDuePreStartReminders(event, now);

        // 2) Join window opened at startInstant
        if (!run.joinWindowOpened && !now.isBefore(run.startInstant)) {
            run.joinWindowOpened = true;
            run.lastJoinReminderInstant = now;
            onJoinWindowOpened(event, run);
        }

        // 3) Join reminders while within join window
        if (run.joinWindowOpened && !run.joinWindowClosed && now.isBefore(run.joinWindowEndInstant)) {
            Duration sinceLastReminder = Duration.between(run.lastJoinReminderInstant, now);
            if (!run.joinWindowLength.isZero()
                    && !run.joinReminderInterval.isZero()
                    && sinceLastReminder.compareTo(run.joinReminderInterval) >= 0) {
                run.lastJoinReminderInstant = now;
                onJoinWindowReminder(event, run);
            }
        }

        // 4) Join window ended
        if (!run.joinWindowClosed && !now.isBefore(run.joinWindowEndInstant)) {
            run.joinWindowClosed = true;
            onJoinWindowEnded(event, run);
        }
    }

    // ---------------------------------------------------
    // Public join-window query API
    // ---------------------------------------------------

    /**
     * High-level join-window state for a given event.
     *
     * UPCOMING  -> join window not yet open for the current scheduled run.
     * OPEN      -> join window currently open.
     * CLOSED    -> join window for the current scheduled run has ended.
     * UNSCHEDULED -> no runtime schedule info for this event (yet).
     */
    public enum JoinWindowState {
        UPCOMING,
        OPEN,
        CLOSED,
        UNSCHEDULED
    }

    /**
     * Query the current join-window state for the given event.
     *
     * This is used by /dragons join to decide whether players
     * should be allowed to join now, or receive "not started"/"window closed".
     */
    public JoinWindowState queryJoinWindowState(EventModel event) {
        if (event == null || !event.enabled()) {
            return JoinWindowState.UNSCHEDULED;
        }

        String id = event.id();
        Instant now = Instant.now();

        // Try to reuse existing run; if none, lazily build one so that
        // commands can still reason about the next scheduled time even
        // before the first tick executes.
        ScheduledRun run = runsByEventId.get(id);
        if (run == null) {
            run = ScheduledRun.forEvent(event, zoneId, now);
            runsByEventId.put(id, run);
        }

        if (now.isBefore(run.startInstant)) {
            return JoinWindowState.UPCOMING;
        }
        if (now.isBefore(run.joinWindowEndInstant)) {
            return JoinWindowState.OPEN;
        }
        return JoinWindowState.CLOSED;
    }

    // ---------------------------------------------------
    // Hook methods (wired into your message system)
    // ---------------------------------------------------

    /**
     * Called each time a pre-start reminder offset is reached.
     *
     * Uses messages.user.countdown with {time} placeholder.
     */
    private void onPreStartReminder(EventModel event,
                                    Duration offsetBeforeStart,
                                    ScheduledRun run) {
        String timeText = humanReadable(offsetBeforeStart);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("time", timeText);
        // placeholders.put("event", event.displayName());

        broadcastUserMessage(UserMessageModels.EVENT_COUNTDOWN, placeholders);
    }

    /**
     * Called when the join window opens (at startInstant).
     *
     * Uses messages.user.EventStart and now sends {time}
     * = total join window length (e.g. "5m", "10m").
     */
    private void onJoinWindowOpened(EventModel event, ScheduledRun run) {
        // Mark runtime join-window state on the event
        event.setJoinWindowOpen(true);

        Map<String, String> placeholders = new HashMap<>();

        // How long the join window stays open
        String windowLengthText = humanReadable(run.joinWindowLength);
        placeholders.put("time", windowLengthText);
        // placeholders.put("event", event.displayName());

        broadcastUserMessage(UserMessageModels.EVENT_START, placeholders);

        // Later: runtime controller hook
        // DragonEventRuntimeManager.getInstance().onJoinWindowOpened(event, run.startInstant, run.joinWindowEndInstant);
    }

    /**
     * Called periodically during the join window, at joinReminderInterval.
     *
     * Uses messages.user.EventStartReminder with {time}
     * = remaining time in the join window.
     */
    private void onJoinWindowReminder(EventModel event, ScheduledRun run) {
        Duration remaining = Duration.between(
                Instant.now(),
                run.joinWindowEndInstant
        );
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("time", humanReadable(remaining));
        // placeholders.put("event", event.displayName());

        broadcastUserMessage(UserMessageModels.EVENT_START_REMINDER, placeholders);
    }

    /**
     * Called once when the join window ends.
     *
     * Uses messages.user.EventStarted to announce that the event
     * has begun (gates closed, combat starting, etc.).
     *
     * Here we also:
     *  - flip joinWindowOpen=false and running=true
     *  - set currentStageKey=INITIAL
     *  - teleport all participants to the INITIAL stage's spawn
     *    (falling back to dragonSpawn if no stage area is defined).
     */
    private void onJoinWindowEnded(EventModel event, ScheduledRun run) {
        // Close join window + mark event as running in INITIAL stage
        event.setJoinWindowOpen(false);
        event.setRunning(true);
        event.setCurrentStageKey(EventStageKey.INITIAL);

        // Teleport all participants into the INITIAL stage arena
        Location initialSpawn = resolveInitialStageSpawn(event);
        for (UUID uuid : event.participantsSnapshot()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(initialSpawn);
            }
        }

        Map<String, String> placeholders = new HashMap<>();
        // Even if {event} isn't used yet in YAML, this is safe and future-proof.
        placeholders.put("event", event.displayName());

        broadcastUserMessage(UserMessageModels.EVENT_STARTED, placeholders);

        // Later: actually schedule per-stage timed events / commands here
        // (e.g. INITIAL -> IN_BELLY -> POST_BELLY -> FINAL)
        // DragonEventRuntimeManager.getInstance().onJoinWindowClosedAndStartEvent(event, run.startInstant);
    }

    // ---------------------------------------------------
    // Helpers
    // ---------------------------------------------------

    /**
     * Decide where to send players when the INITIAL stage begins:
     *  - Prefer the INITIAL StageArea's spawn, if configured
     *  - Otherwise fall back to the global dragonSpawn.
     */
    private static Location resolveInitialStageSpawn(EventModel event) {
        EventModel.StageArea initialArea = event.stageAreaOrNull(EventStageKey.INITIAL);
        if (initialArea != null) {
            return initialArea.spawn();
        }
        return event.dragonSpawn();
    }

    private static String humanReadable(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) {
            return "0s";
        }

        long seconds = d.getSeconds();

        // Hours, rounded to nearest hour
        if (seconds >= 3600) {
            long hours = Math.round(seconds / 3600.0);
            return hours + "H";
        }

        // Minutes, rounded to nearest minute
        if (seconds >= 60) {
            long minutes = Math.round(seconds / 60.0);
            return minutes + "M";
        }

        // Under 60 seconds → just show seconds
        return seconds + "s";
    }

    /**
     * Broadcast a user message model (with placeholders) to all online players.
     */
    private void broadcastUserMessage(UserMessageModels model, Map<String, String> placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            MessageResponseManager.send(player, model, placeholders);
        }
    }

    // ---------------------------------------------------
    // ScheduledRun (per-event runtime state)
    // ---------------------------------------------------

    private static final class ScheduledRun {

        final Instant startInstant;
        final Instant joinWindowEndInstant;
        final Duration joinWindowLength;
        final Duration joinReminderInterval;

        final List<Reminder> preStartReminders;

        boolean joinWindowOpened;
        boolean joinWindowClosed;
        Instant lastJoinReminderInstant;

        private ScheduledRun(Instant startInstant,
                             Instant joinWindowEndInstant,
                             Duration joinWindowLength,
                             Duration joinReminderIntervalRaw,
                             List<Reminder> preStartReminders) {
            this.startInstant = startInstant;
            this.joinWindowEndInstant = joinWindowEndInstant;
            this.joinWindowLength = joinWindowLength;
            this.joinReminderInterval = joinWindowIntervalSafe(joinReminderIntervalRaw);
            this.preStartReminders = preStartReminders;
        }

        private static Duration joinWindowIntervalSafe(Duration interval) {
            if (interval == null || interval.isNegative() || interval.isZero()) {
                return Duration.ZERO;
            }
            return interval;
        }

        /**
         * Build a new ScheduledRun from an EventModel and current time.
         */
        static ScheduledRun forEvent(EventModel event, ZoneId zoneId, Instant now) {
            EventModel.EventSchedule schedule = event.schedule();
            Instant start = schedule.nextRun(zoneId, now);

            Duration joinWindow = event.joinWindowLength();
            if (joinWindow.isNegative()) {
                joinWindow = Duration.ZERO;
            }
            Instant joinEnd = start.plus(joinWindow);

            Duration joinReminderInterval = event.joinReminderInterval();
            List<Reminder> reminders = buildPreStartReminders(schedule.preStartReminderOffsets(), start);

            return new ScheduledRun(start, joinEnd, joinWindow, joinReminderInterval, reminders);
        }

        private static List<Reminder> buildPreStartReminders(List<Duration> offsets, Instant startInstant) {
            if (offsets == null || offsets.isEmpty()) {
                return Collections.emptyList();
            }
            List<Reminder> list = new ArrayList<>(offsets.size());
            for (Duration offset : offsets) {
                if (offset == null || offset.isNegative() || offset.isZero()) continue;
                Instant at = startInstant.minus(offset);
                list.add(new Reminder(at, offset));
            }
            list.sort(Comparator.comparing(r -> r.fireInstant));
            return list;
        }

        boolean isCompleted(Instant now) {
            // Consider this run fully done once the join window has closed AND
            // we've passed joinWindowEndInstant by at least 1 second.
            return now.isAfter(joinWindowEndInstant.plusSeconds(1));
        }

        void fireDuePreStartReminders(EventModel event, Instant now) {
            if (preStartReminders.isEmpty()) return;

            // Find the single most recent reminder that is due (fireInstant <= now)
            Reminder latestDue = null;

            for (Reminder r : preStartReminders) {
                if (r.fired) continue;
                if (!now.isBefore(r.fireInstant)) { // fireInstant <= now
                    latestDue = r; // list is sorted, so this ends up as the last due reminder
                }
            }

            if (latestDue == null) {
                // Nothing new is due this tick.
                return;
            }

            // Mark all earlier due reminders as "fired" silently so they never trigger.
            for (Reminder r : preStartReminders) {
                if (!r.fired && r.fireInstant.isBefore(latestDue.fireInstant)) {
                    r.fired = true;
                }
            }

            // Fire only the most relevant (latest) one.
            latestDue.fired = true;
            EventSequenceManager manager = EventSequenceManager.getInstance();
            if (manager != null) {
                manager.onPreStartReminder(event, latestDue.offsetBeforeStart, this);
            }
        }

        private static final class Reminder {
            final Instant fireInstant;
            final Duration offsetBeforeStart;
            boolean fired;

            Reminder(Instant fireInstant, Duration offsetBeforeStart) {
                this.fireInstant = fireInstant;
                this.offsetBeforeStart = offsetBeforeStart;
            }
        }
    }
}
