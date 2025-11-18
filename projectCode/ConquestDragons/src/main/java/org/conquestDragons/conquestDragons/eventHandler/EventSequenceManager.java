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
import java.util.logging.Level;

/**
 * EventSequenceManager
 *
 * Central runtime coordinator for scheduled dragon events:
 *  - Computes each EventModel's next run from its schedule.
 *  - Manages the join window (open/close + reminders).
 *  - Starts/ends logical stages (LOBBY, INITIAL, etc.).
 *  - Executes stage start/timed/end commands.
 *  - Handles per-stage repeat messages (looping while stage is active).
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

            // If the current run is fully completed (based on maxDuration), roll to the next one.
            if (run.isCompleted(now)) {
                run = ScheduledRun.forEvent(event, zoneId, now);
                runsByEventId.put(event.id(), run);
            }

            processRun(event, run, now);
        }
    }

    private void processRun(EventModel event, ScheduledRun run, Instant now) {
        // 1) Pre-start heads-up reminders (global countdown)
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

        // 5) Per-stage timed commands + repeat messages
        tickStages(event, run, now);
    }

    // ---------------------------------------------------
    // Public join-window query API
    // ---------------------------------------------------

    public enum JoinWindowState {
        UPCOMING,
        OPEN,
        CLOSED,
        UNSCHEDULED
    }

    public JoinWindowState queryJoinWindowState(EventModel event) {
        if (event == null || !event.enabled()) {
            return JoinWindowState.UNSCHEDULED;
        }

        String id = event.id();
        Instant now = Instant.now();

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
    // Join-window hooks
    // ---------------------------------------------------

    private void onPreStartReminder(EventModel event,
                                    Duration offsetBeforeStart,
                                    ScheduledRun run) {
        String timeText = humanReadable(offsetBeforeStart);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("time", timeText);

        broadcastUserMessage(UserMessageModels.EVENT_COUNTDOWN, placeholders);
    }

    /**
     * Join window opens:
     * - event.joinWindowOpen = true
     * - currentStageKey = LOBBY
     * - LOBBY stage started
     */
    private void onJoinWindowOpened(EventModel event, ScheduledRun run) {
        event.setJoinWindowOpen(true);
        event.setRunning(false); // not "combat live" yet
        event.setCurrentStageKey(EventStageKey.LOBBY);

        Map<String, String> placeholders = new HashMap<>();
        String windowLengthText = humanReadable(run.joinWindowLength);
        placeholders.put("time", windowLengthText);

        broadcastUserMessage(UserMessageModels.EVENT_START, placeholders);

        // Start LOBBY stage (pre-combat waiting room)
        startStage(event, run, EventStageKey.LOBBY, run.startInstant);
    }

    private void onJoinWindowReminder(EventModel event, ScheduledRun run) {
        Duration remaining = Duration.between(
                Instant.now(),
                run.joinWindowEndInstant
        );
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }

        // 🔧 Clamp small positive durations to 1 minute so we don't show "57s"
        if (remaining.compareTo(Duration.ZERO) > 0
                && remaining.compareTo(Duration.ofMinutes(1)) < 0) {
            remaining = Duration.ofMinutes(1);
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("time", humanReadable(remaining));

        broadcastUserMessage(UserMessageModels.EVENT_START_REMINDER, placeholders);
    }

    /**
     * Join window ends:
     * - joinWindowOpen = false
     * - running = true
     * - currentStageKey = INITIAL
     * - LOBBY end-commands
     * - INITIAL start-commands + timers
     * - participants teleported into INITIAL arena
     */
    private void onJoinWindowEnded(EventModel event, ScheduledRun run) {
        event.setJoinWindowOpen(false);
        event.setRunning(true);
        event.setCurrentStageKey(EventStageKey.INITIAL);

        // End the LOBBY stage (end-commands)
        endStage(event, run, EventStageKey.LOBBY);

        // Teleport all participants into the INITIAL stage arena
        Location initialSpawn = resolveInitialStageSpawn(event);
        for (UUID uuid : event.participantsSnapshot()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(initialSpawn);
            }
        }

        // Start INITIAL stage right as the join window closes
        startStage(event, run, EventStageKey.INITIAL, run.joinWindowEndInstant);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("event", event.displayName());

        broadcastUserMessage(UserMessageModels.EVENT_STARTED, placeholders);
    }

    // ---------------------------------------------------
    // Stage runtime helpers
    // ---------------------------------------------------

    /**
     * Start a logical stage for this run:
     * - build StageRuntime (timed commands + repeat message state)
     * - immediately run its start-commands
     */
    private void startStage(EventModel event,
                            ScheduledRun run,
                            EventStageKey key,
                            Instant startInstant) {

        ScheduledRun.StageRuntime runtime = run.getOrCreateStageRuntime(event, key, startInstant);
        if (runtime == null) {
            plugin.getLogger().warning("[ConquestDragons] No EventStageModel configured for stage "
                    + key + " in event " + event.id());
            return;
        }

        if (runtime.started) {
            return; // already started
        }

        runtime.started = true;

        plugin.getLogger().info("[ConquestDragons] Starting stage " + key
                + " for event " + event.id()
                + " (timedCommands=" + runtime.timedCommands.size()
                + ", hasRepeatMessage=" + (runtime.repeatMessage != null) + ")");

        executeCommands(runtime.model.startCommands());
    }

    /**
     * End a logical stage for this run:
     * - mark ended
     * - run its end-commands
     */
    private void endStage(EventModel event,
                          ScheduledRun run,
                          EventStageKey key) {

        ScheduledRun.StageRuntime runtime = run.getStageRuntime(key);
        if (runtime == null || runtime.ended) {
            return;
        }

        runtime.ended = true;

        plugin.getLogger().info("[ConquestDragons] Ending stage " + key
                + " for event " + event.id());

        executeCommands(runtime.model.endCommands());
    }

    /**
     * Per-tick stage pipeline:
     * - fire due timed-commands (one-shot)
     * - fire repeat stage messages on their interval (participants only)
     */
    private void tickStages(EventModel event, ScheduledRun run, Instant now) {
        for (ScheduledRun.StageRuntime runtime : run.allStageRuntimes()) {
            if (!runtime.started || runtime.ended) {
                continue;
            }

            // 1) Timed commands (one-shot each)
            for (ScheduledRun.PendingTimedCommand batch : runtime.timedCommands) {
                if (!batch.executed && !now.isBefore(batch.fireInstant)) {
                    batch.executed = true;

                    plugin.getLogger().info("[ConquestDragons] Executing timed-commands for stage "
                            + runtime.stageKey + " (event=" + event.id()
                            + ", commands=" + batch.commands.size() + ")");

                    executeCommands(batch.commands);
                }
            }

            // 2) Repeat stage message (looping while stage active)
            ScheduledRun.RepeatMessageState rm = runtime.repeatMessage;
            if (rm != null && rm.nextFireInstant != null && !now.isBefore(rm.nextFireInstant)) {
                plugin.getLogger().info("[ConquestDragons] Firing repeat stage message for stage "
                        + runtime.stageKey + " (event=" + event.id() + ")");

                sendStageTimedMessage(event, runtime.stageKey); // still uses your existing message models

                // Schedule next fire based on intervalTicks
                long intervalTicks = rm.intervalTicks;
                if (intervalTicks > 0L) {
                    rm.nextFireInstant = rm.nextFireInstant.plusMillis(intervalTicks * 50L);
                } else {
                    // Safety: if somehow intervalTicks <= 0, disable further fires
                    rm.nextFireInstant = null;
                }
            }
        }
    }

    // ---------------------------------------------------
    // Helpers
    // ---------------------------------------------------

    /**
     * Decide where to send players when the INITIAL stage begins:
     * - Prefer the INITIAL StageArea's spawn, if configured
     * - Otherwise fall back to the global dragonSpawn.
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

        if (seconds >= 3600) {
            long hours = Math.round(seconds / 3600.0);
            return hours + "H";
        }
        if (seconds >= 60) {
            long minutes = Math.round(seconds / 60.0);
            return minutes + "M";
        }
        return seconds + "s";
    }

    /**
     * Execute a batch of console commands safely.
     */
    private void executeCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) return;

        for (String raw : commands) {
            if (raw == null) continue;
            String cmd = raw.trim();
            if (cmd.isEmpty()) continue;

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception ex) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Failed to execute event command: \"" + cmd + "\"",
                        ex
                );
            }
        }
    }

    /**
     * Broadcast a user message model (with placeholders) to all online players.
     * (Used only for global notices like countdown / join window).
     */
    private void broadcastUserMessage(UserMessageModels model, Map<String, String> placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            MessageResponseManager.send(player, model, placeholders);
        }
    }

    /**
     * Send the stage "repeat" message (still using your existing *TIMED* models)
     * to event participants only.
     *
     * Your UserMessageModels currently look like:
     *   LOBBY_STAGE_TIMED, INITIAL_STAGE_TIMED, ...
     * pointing at YAML sections:
     *   messages.user.lobby-stage.timed
     *   messages.user.initial-stage.timed
     *   ...
     */
    private void sendStageTimedMessage(EventModel event, EventStageKey stageKey) {
        UserMessageModels model = resolveStageTimedModel(stageKey);
        if (model == null) {
            plugin.getLogger().warning("[ConquestDragons] No UserMessageModels mapping for stage timed message: "
                    + stageKey);
            return;
        }

        Collection<UUID> participants = event.participantsSnapshot();
        if (participants == null || participants.isEmpty()) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("event", event.displayName());
        placeholders.put("stage", stageKey.name());

        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                MessageResponseManager.send(p, model, placeholders);
            }
        }
    }

    /**
     * Map logical stage → timed message model.
     * Adjust these names to your actual enum values.
     */
    private UserMessageModels resolveStageTimedModel(EventStageKey stageKey) {
        switch (stageKey) {
            case LOBBY:
                return UserMessageModels.LOBBY_STAGE_TIMED;
            case INITIAL:
                return UserMessageModels.INITIAL_STAGE_TIMED;
            case IN_BELLY:
                return UserMessageModels.IN_BELLY_STAGE_TIMED;
            case POST_BELLY:
                return UserMessageModels.POST_BELLY_STAGE_TIMED;
            case FINAL:
                return UserMessageModels.FINAL_STAGE_TIMED;
            default:
                return null;
        }
    }

    // ---------------------------------------------------
    // ScheduledRun (per-event runtime state)
    // ---------------------------------------------------

    private static final class ScheduledRun {

        final Instant startInstant;
        final Instant joinWindowEndInstant;
        final Instant runEndInstant;        // when the whole event run is considered finished

        final Duration joinWindowLength;
        final Duration joinReminderInterval;

        final List<Reminder> preStartReminders;

        boolean joinWindowOpened;
        boolean joinWindowClosed;
        Instant lastJoinReminderInstant;

        // Per-stage runtime (commands + repeat message)
        private final Map<EventStageKey, StageRuntime> stageRuntimes = new EnumMap<>(EventStageKey.class);

        private ScheduledRun(Instant startInstant,
                             Instant joinWindowEndInstant,
                             Instant runEndInstant,
                             Duration joinWindowLength,
                             Duration joinReminderIntervalRaw,
                             List<Reminder> preStartReminders) {
            this.startInstant = startInstant;
            this.joinWindowEndInstant = joinWindowEndInstant;
            this.runEndInstant = runEndInstant;
            this.joinWindowLength = joinWindowLength;
            this.joinReminderInterval = safeInterval(joinReminderIntervalRaw);
            this.preStartReminders = preStartReminders;
        }

        private static Duration safeInterval(Duration interval) {
            if (interval == null || interval.isNegative() || interval.isZero()) {
                return Duration.ZERO;
            }
            return interval;
        }

        static ScheduledRun forEvent(EventModel event, ZoneId zoneId, Instant now) {
            EventModel.EventSchedule schedule = event.schedule();
            Instant start = schedule.nextRun(zoneId, now);

            // Join window
            Duration joinWindow = event.joinWindowLength();
            if (joinWindow.isNegative()) {
                joinWindow = Duration.ZERO;
            }
            Instant joinEnd = start.plus(joinWindow);

            Duration joinReminderInterval = event.joinReminderInterval();
            List<Reminder> reminders = buildPreStartReminders(schedule.preStartReminderOffsets(), start);

            // Compute when this event run should fully end, based on maxDuration
            Duration maxDuration = event.maxDuration();
            if (maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()) {
                // Safety: ensure we don't instantly mark it complete
                maxDuration = Duration.ofMinutes(1);
            }
            Instant runEnd = start.plus(maxDuration);

            return new ScheduledRun(start, joinEnd, runEnd, joinWindow, joinReminderInterval, reminders);
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
            // Run is considered complete once we've passed the configured maxDuration
            return now.isAfter(runEndInstant);
        }

        void fireDuePreStartReminders(EventModel event, Instant now) {
            if (preStartReminders.isEmpty()) return;

            Reminder latestDue = null;

            for (Reminder r : preStartReminders) {
                if (r.fired) continue;
                if (!now.isBefore(r.fireInstant)) { // fireInstant <= now
                    latestDue = r;
                }
            }

            if (latestDue == null) {
                return;
            }

            for (Reminder r : preStartReminders) {
                if (!r.fired && r.fireInstant.isBefore(latestDue.fireInstant)) {
                    r.fired = true;
                }
            }

            latestDue.fired = true;
            EventSequenceManager manager = EventSequenceManager.getInstance();
            if (manager != null) {
                manager.onPreStartReminder(event, latestDue.offsetBeforeStart, this);
            }
        }

        // ----- Stage runtime accessors -----------------------------------

        StageRuntime getOrCreateStageRuntime(EventModel event,
                                             EventStageKey key,
                                             Instant startInstant) {

            StageRuntime existing = stageRuntimes.get(key);
            if (existing != null) {
                return existing;
            }

            EventStageModel model = event.findStageOrNull(key);
            if (model == null) {
                return null;
            }

            StageRuntime created = new StageRuntime(key, model, startInstant);
            stageRuntimes.put(key, created);
            return created;
        }

        StageRuntime getStageRuntime(EventStageKey key) {
            return stageRuntimes.get(key);
        }

        Collection<StageRuntime> allStageRuntimes() {
            return stageRuntimes.values();
        }

        // ----- Inner value objects --------------------------------------

        private static final class Reminder {
            final Instant fireInstant;
            final Duration offsetBeforeStart;
            boolean fired;

            Reminder(Instant fireInstant, Duration offsetBeforeStart) {
                this.fireInstant = fireInstant;
                this.offsetBeforeStart = offsetBeforeStart;
            }
        }

        /**
         * Per-stage runtime:
         * - startInstant      : when this stage began
         * - timedCommands     : batches scheduled from stage start (one-shot)
         * - repeatMessage     : optional repeating message state
         * - started / ended   : lifecycle flags
         */
        static final class StageRuntime {
            final EventStageKey stageKey;
            final EventStageModel model;
            final Instant startInstant;
            final List<PendingTimedCommand> timedCommands;
            final RepeatMessageState repeatMessage;

            boolean started;
            boolean ended;

            StageRuntime(EventStageKey stageKey,
                         EventStageModel model,
                         Instant startInstant) {

                this.stageKey = Objects.requireNonNull(stageKey, "stageKey");
                this.model = Objects.requireNonNull(model, "model");
                this.startInstant = Objects.requireNonNull(startInstant, "startInstant");

                this.timedCommands = buildTimedCommandBatches(model.timedCommands(), startInstant);
                this.repeatMessage = buildRepeatMessageState(model.repeatMessage(), startInstant);
            }
        }

        /**
         * One batch of commands scheduled at a specific instant.
         */
        static final class PendingTimedCommand {
            final Instant fireInstant;
            final List<String> commands;
            boolean executed;

            PendingTimedCommand(Instant fireInstant, List<String> commands) {
                this.fireInstant = fireInstant;
                this.commands = commands;
            }
        }

        /**
         * Repeat message state:
         * - intervalTicks > 0 => loop every N ticks
         * - nextFireInstant   => when to fire next (null == disabled)
         */
        static final class RepeatMessageState {
            final long intervalTicks;
            Instant nextFireInstant;

            RepeatMessageState(long intervalTicks, Instant nextFireInstant) {
                this.intervalTicks = intervalTicks;
                this.nextFireInstant = nextFireInstant;
            }
        }

        // ----- Builders for stage timers --------------------------------

        private static List<PendingTimedCommand> buildTimedCommandBatches(
                List<EventStageModel.TimedCommandSpec> specs,
                Instant startInstant
        ) {
            if (specs == null || specs.isEmpty()) {
                return Collections.emptyList();
            }

            List<PendingTimedCommand> list = new ArrayList<>(specs.size());
            for (EventStageModel.TimedCommandSpec spec : specs) {
                if (spec == null) continue;
                List<String> cmds = spec.commands();
                if (cmds == null || cmds.isEmpty()) continue;

                long ticks = spec.delayTicks();
                if (ticks < 0) continue;

                Instant at = startInstant.plusMillis(ticks * 50L); // 20 ticks = 1s
                list.add(new PendingTimedCommand(at, cmds));
            }

            list.sort(Comparator.comparing(pc -> pc.fireInstant));
            return list;
        }

        /**
         * Build repeat-message state from the RepeatMessageSpec.
         *
         * intervalTicks:
         *   - 0  => disabled (returns null)
         *   - >0 => first fire at startInstant + interval
         */
        private static RepeatMessageState buildRepeatMessageState(
                EventStageModel.RepeatMessageSpec spec,
                Instant startInstant
        ) {
            if (spec == null) {
                return null;
            }

            long intervalTicks = spec.intervalTicks();
            if (intervalTicks <= 0L) {
                // 0 = disabled by design
                return null;
            }

            Instant firstFire = startInstant.plusMillis(intervalTicks * 50L);
            return new RepeatMessageState(intervalTicks, firstFire);
        }
    }
}
