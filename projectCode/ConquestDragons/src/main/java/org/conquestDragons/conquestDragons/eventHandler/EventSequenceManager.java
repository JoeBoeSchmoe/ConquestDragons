package org.conquestDragons.conquestDragons.eventHandler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.ConfigFile;
import org.conquestDragons.conquestDragons.dragonHandler.DragonBossbarManager;
import org.conquestDragons.conquestDragons.dragonHandler.DragonManager;
import org.conquestDragons.conquestDragons.dragonHandler.DragonModel;
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
 *  - Drives INITIAL stage dragon spawns using DragonManager.getBuilder().
 *  - Coordinates transition into IN_BELLY stage when dragons hit belly health threshold.
 *  - Drives IN_BELLY duration and transitions players to POST_BELLY.
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

        // Start DragonBossbarManager (tracks dragons + health)
        DragonBossbarManager.start(ConquestDragons.getInstance());

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

        // 5) Per-stage timed commands + repeat messages + INITIAL dragon spawns + belly timing
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
    // Belly trigger callback (from DragonBossbarManager)
    // ---------------------------------------------------

    /**
     * Called by DragonBossbarManager when a dragon for this event crosses the
     * belly trigger health fraction.
     *
     * Responsibilities:
     *  - Ensure INITIAL stage is running for this event.
     *  - On first trigger, transition to IN_BELLY stage.
     *  - Compute per-dragon player quota based on active dragons.
     *  - Select players not already in belly, teleport them to IN_BELLY spawn.
     *  - Record which players were eaten by which dragon for premature-free logic.
     */
    public void onDragonBellyTrigger(EventModel event,
                                     EnderDragon dragon,
                                     double healthFraction) {
        if (event == null || dragon == null) {
            return;
        }

        ScheduledRun run = runsByEventId.get(event.id());
        if (run == null) {
            return;
        }

        // Must be in INITIAL stage to do belly transitions
        ScheduledRun.StageRuntime initialRt = run.getStageRuntime(EventStageKey.INITIAL);
        if (initialRt == null || !initialRt.started || initialRt.ended) {
            return;
        }

        // First time any dragon triggers belly: start IN_BELLY stage.
        if (!run.inBellyStageStarted) {
            run.inBellyStageStarted = true;

            // End INITIAL stage commands + messages
            endStage(event, run, EventStageKey.INITIAL);

            // Switch logical current stage for this event
            event.setCurrentStageKey(EventStageKey.IN_BELLY);

            // Start IN_BELLY stage commands/timers + start message
            startStage(event, run, EventStageKey.IN_BELLY, Instant.now());

            plugin.getLogger().info("[ConquestDragons] Event '" + event.id()
                    + "' entering IN_BELLY stage due to dragon " + dragon.getUniqueId()
                    + " (healthFraction=" + healthFraction + ")");
        }

        // Now compute how many players this dragon gets to "eat".
        Collection<UUID> participants = event.participantsSnapshot();
        if (participants == null || participants.isEmpty()) {
            return;
        }

        int totalParticipants = participants.size();

        // Ask bossbar manager how many dragons are currently alive for this event.
        DragonBossbarManager bossMgr = DragonBossbarManager.getInstance();
        int activeDragonCount = 1;
        if (bossMgr != null) {
            activeDragonCount = bossMgr.countActiveDragonsForEvent(event);
        }
        if (activeDragonCount <= 0) {
            activeDragonCount = 1; // safety
        }

        // Example: 2 dragons, 20 players → ceil(20/2) = 10 per dragon.
        int playersPerDragon = (int) Math.ceil(totalParticipants / (double) activeDragonCount);
        if (playersPerDragon <= 0) {
            playersPerDragon = 1;
        }

        // Build list of candidates: participants not already teleported into belly.
        List<UUID> candidates = new ArrayList<>();
        for (UUID uuid : participants) {
            if (!run.inBellyParticipants.contains(uuid)) {
                candidates.add(uuid);
            }
        }
        if (candidates.isEmpty()) {
            return; // everyone already in belly
        }

        // Shuffle for fairness/randomness
        Collections.shuffle(candidates);

        int toTake = Math.min(playersPerDragon, candidates.size());
        Location bellySpawn = resolveInBellyStageSpawn(event);
        if (bellySpawn == null) {
            plugin.getLogger().warning("[ConquestDragons] No IN_BELLY spawn configured for event '"
                    + event.id() + "'. Belly teleport skipped.");
            return;
        }

        // Per-dragon bucket for eaten players
        UUID dragonId = dragon.getUniqueId();
        Set<UUID> eatenByDragon = run.bellyPlayersByDragon.computeIfAbsent(
                dragonId,
                k -> ConcurrentHashMap.newKeySet()
        );

        for (int i = 0; i < toTake; i++) {
            UUID uuid = candidates.get(i);
            run.inBellyParticipants.add(uuid);
            eatenByDragon.add(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(bellySpawn);
            }
        }

        plugin.getLogger().info("[ConquestDragons] Dragon " + dragon.getUniqueId()
                + " pulled " + toTake + " players into IN_BELLY for event '" + event.id()
                + "' (playersPerDragon=" + playersPerDragon
                + ", activeDragons=" + activeDragonCount + ")");
    }

    // ---------------------------------------------------
    // Dragon killed callback (from DragonBossbarManager)
    // ---------------------------------------------------

    /**
     * Called when an event dragon dies.
     *
     * If we're still in the IN_BELLY stage and this dragon had eaten players,
     * we prematurely free those players:
     *  - remove them from inBellyParticipants
     *  - teleport them to POST_BELLY spawn
     *
     * This does NOT advance the global stage timing; it's per-player "escape".
     */
    public void onDragonKilled(EventModel event, EnderDragon dragon) {
        if (event == null || dragon == null) {
            return;
        }

        ScheduledRun run = runsByEventId.get(event.id());
        if (run == null) {
            return;
        }

        // We only care if IN_BELLY stage exists and is currently running.
        ScheduledRun.StageRuntime bellyRt = run.getStageRuntime(EventStageKey.IN_BELLY);
        if (bellyRt == null || !bellyRt.started || bellyRt.ended) {
            return;
        }

        UUID dragonId = dragon.getUniqueId();
        Set<UUID> eaten = run.bellyPlayersByDragon.remove(dragonId);
        if (eaten == null || eaten.isEmpty()) {
            return; // this dragon never ate anyone
        }

        Location postBellySpawn = resolvePostBellyStageSpawn(event);
        if (postBellySpawn == null) {
            plugin.getLogger().warning("[ConquestDragons] No POST_BELLY spawn configured for event '"
                    + event.id() + "'. Cannot prematurely free belly players.");
            return;
        }

        int moved = 0;
        for (UUID uuid : eaten) {
            // Mark them as no longer in belly so the global timeout doesn't double-move them
            run.inBellyParticipants.remove(uuid);

            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(postBellySpawn);
                moved++;
            }
        }

        plugin.getLogger().info("[ConquestDragons] Dragon " + dragon.getUniqueId()
                + " was killed early; freed " + moved
                + " belly player(s) into POST_BELLY spawn for event '" + event.id() + "'.");
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

        // Clamp tiny positives to 1 minute for nicer UX
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

        // End the LOBBY stage (end-commands + end message)
        endStage(event, run, EventStageKey.LOBBY);

        // Teleport all participants into the INITIAL stage arena
        Location initialSpawn = resolveInitialStageSpawn(event);
        for (UUID uuid : event.participantsSnapshot()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && initialSpawn != null) {
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
     * - send stage START message to participants
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

        // Stage START console commands
        executeCommands(runtime.model.startCommands());

        // Stage START user message
        sendStageStartMessage(event, key);

        // INITIAL stage: prepare dragon spawn scheduling
        if (key == EventStageKey.INITIAL) {
            run.setupInitialDragonSpawns(event, startInstant);
        }
    }

    /**
     * End a logical stage for this run:
     * - mark ended
     * - run its end-commands
     * - send stage END message to participants
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

        // Stage END console commands
        executeCommands(runtime.model.endCommands());

        // Stage END user message
        sendStageEndMessage(event, key);
    }

    /**
     * Per-tick stage pipeline:
     * - fire due timed-commands (one-shot)
     * - fire repeat stage messages on their interval (participants only)
     * - drive INITIAL dragon spawns
     * - enforce IN_BELLY duration and transition remaining belly players to POST_BELLY
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

                sendStageTimedMessage(event, runtime.stageKey);

                long intervalTicks = rm.intervalTicks;
                if (intervalTicks > 0L) {
                    rm.nextFireInstant = rm.nextFireInstant.plusMillis(intervalTicks * 50L);
                } else {
                    rm.nextFireInstant = null;
                }
            }
        }

        // 3) INITIAL stage: spawn dragons one at a time with configured interval
        run.tickInitialDragonSpawns(event, now);

        // 4) IN_BELLY duration: once elapsed, move remaining belly players to POST_BELLY
        tickInBellyDuration(event, run, now);
    }

    /**
     * Handle the configured IN_BELLY duration for remaining belly players.
     *
     * Once the IN_BELLY stage has run for event.inBellyDuration(), we:
     *  - end the IN_BELLY stage
     *  - move all still-tracked in-belly participants to the POST_BELLY spawn
     *  - switch currentStageKey to POST_BELLY
     *  - start the POST_BELLY stage (start commands + messages)
     */
    private void tickInBellyDuration(EventModel event, ScheduledRun run, Instant now) {
        // If event has no configured belly duration, do nothing.
        Duration bellyDuration = event.inBellyDuration();
        if (bellyDuration == null || bellyDuration.isZero() || bellyDuration.isNegative()) {
            return;
        }

        // We only care if IN_BELLY stage exists and is currently running.
        ScheduledRun.StageRuntime bellyRt = run.getStageRuntime(EventStageKey.IN_BELLY);
        if (bellyRt == null || !bellyRt.started || bellyRt.ended) {
            return;
        }

        Instant bellyEndInstant = bellyRt.startInstant.plus(bellyDuration);
        if (now.isBefore(bellyEndInstant)) {
            return; // still inside belly window
        }

        // Past the IN_BELLY duration → time to move remaining belly players into POST_BELLY.
        Location postBellySpawn = resolvePostBellyStageSpawn(event);
        if (postBellySpawn == null) {
            plugin.getLogger().warning("[ConquestDragons] No POST_BELLY spawn configured for event '"
                    + event.id() + "'. Cannot move players out of belly.");
            return;
        }

        // End IN_BELLY stage (commands + messages).
        endStage(event, run, EventStageKey.IN_BELLY);

        // Teleport all players we still consider "in belly".
        int moved = 0;
        for (UUID uuid : new ArrayList<>(run.inBellyParticipants)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(postBellySpawn);
                moved++;
            }
        }
        run.inBellyParticipants.clear();
        run.bellyPlayersByDragon.clear();

        // Switch logical stage and start POST_BELLY (once).
        event.setCurrentStageKey(EventStageKey.POST_BELLY);
        startStage(event, run, EventStageKey.POST_BELLY, now);

        plugin.getLogger().info("[ConquestDragons] IN_BELLY duration ended for event '"
                + event.id() + "'. Moved " + moved + " player(s) into POST_BELLY.");
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

    /**
     * Decide where to send players when they are pulled into IN_BELLY:
     * - Prefer the IN_BELLY StageArea's spawn, if configured
     * - Otherwise fall back to the global dragonSpawn.
     */
    private static Location resolveInBellyStageSpawn(EventModel event) {
        EventModel.StageArea bellyArea = event.stageAreaOrNull(EventStageKey.IN_BELLY);
        if (bellyArea != null) {
            return bellyArea.spawn();
        }
        return event.dragonSpawn();
    }

    /**
     * Decide where to send players when they leave IN_BELLY and enter POST_BELLY:
     * - Prefer the POST_BELLY StageArea's spawn, if configured
     * - Otherwise fall back to the global dragonSpawn.
     */
    private static Location resolvePostBellyStageSpawn(EventModel event) {
        EventModel.StageArea postBellyArea = event.stageAreaOrNull(EventStageKey.POST_BELLY);
        if (postBellyArea != null) {
            return postBellyArea.spawn();
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

    // ---------------------------------------------------
    // Stage user messages: START / TIMED / END
    // ---------------------------------------------------

    /**
     * Send the stage START message to event participants only.
     */
    private void sendStageStartMessage(EventModel event, EventStageKey stageKey) {
        UserMessageModels model = resolveStageStartModel(stageKey);
        if (model == null) {
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
     * Send the stage END message to event participants only.
     */
    private void sendStageEndMessage(EventModel event, EventStageKey stageKey) {
        UserMessageModels model = resolveStageEndModel(stageKey);
        if (model == null) {
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
     * Send the stage "repeat" message (still using your existing *TIMED* models)
     * to event participants only.
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
     * Map logical stage → START message model.
     */
    private UserMessageModels resolveStageStartModel(EventStageKey stageKey) {
        switch (stageKey) {
            case LOBBY:
                return UserMessageModels.LOBBY_STAGE_START;
            case INITIAL:
                return UserMessageModels.INITIAL_STAGE_START;
            case IN_BELLY:
                return UserMessageModels.IN_BELLY_STAGE_START;
            case POST_BELLY:
                return UserMessageModels.POST_BELLY_STAGE_START;
            case FINAL:
                return UserMessageModels.FINAL_STAGE_START;
            default:
                return null;
        }
    }

    /**
     * Map logical stage → TIMED message model.
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

    /**
     * Map logical stage → END message model.
     */
    private UserMessageModels resolveStageEndModel(EventStageKey stageKey) {
        switch (stageKey) {
            case LOBBY:
                return UserMessageModels.LOBBY_STAGE_END;
            case INITIAL:
                return UserMessageModels.INITIAL_STAGE_END;
            case IN_BELLY:
                return UserMessageModels.IN_BELLY_STAGE_END;
            case POST_BELLY:
                return UserMessageModels.POST_BELLY_STAGE_END;
            case FINAL:
                return UserMessageModels.FINAL_STAGE_END;
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

        // INITIAL stage dragon spawn scheduling
        private List<String> initialDragonIds = Collections.emptyList();
        private int initialDragonIndex = 0;
        private Instant nextInitialDragonSpawnInstant;
        private boolean initialSpawnsInitialized = false;
        private boolean initialSpawnsComplete = false;

        // IN_BELLY tracking (per-event + per-dragon)
        boolean inBellyStageStarted = false;
        final Set<UUID> inBellyParticipants = ConcurrentHashMap.newKeySet();
        final Map<UUID, Set<UUID>> bellyPlayersByDragon = new ConcurrentHashMap<>();

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

        // ----- INITIAL stage spawn scheduling ----------------------------

        /**
         * Prepare INITIAL stage dragon spawn queue.
         * Called once when INITIAL stage starts.
         */
        void setupInitialDragonSpawns(EventModel event, Instant stageStartInstant) {
            if (initialSpawnsInitialized) {
                return;
            }
            initialSpawnsInitialized = true;

            List<String> ids = event.dragonIds();
            if (ids == null || ids.isEmpty()) {
                initialSpawnsComplete = true;
                return;
            }

            this.initialDragonIds = List.copyOf(ids);
            this.initialDragonIndex = 0;
            // First dragon at stage start
            this.nextInitialDragonSpawnInstant = stageStartInstant;
        }

        /**
         * Called each tick by EventSequenceManager to spawn INITIAL-stage dragons
         * one at a time, spaced by event.dragonSpawnInterval().
         */
        void tickInitialDragonSpawns(EventModel event, Instant now) {
            if (!initialSpawnsInitialized || initialSpawnsComplete) {
                return;
            }

            // Ensure INITIAL stage is actually running
            StageRuntime initialRuntime = stageRuntimes.get(EventStageKey.INITIAL);
            if (initialRuntime == null || !initialRuntime.started || initialRuntime.ended) {
                return;
            }

            if (nextInitialDragonSpawnInstant == null || now.isBefore(nextInitialDragonSpawnInstant)) {
                return;
            }

            // Time to spawn one dragon
            if (initialDragonIndex < initialDragonIds.size()) {
                String dragonId = initialDragonIds.get(initialDragonIndex);
                EventSequenceManager mgr = EventSequenceManager.getInstance();
                if (mgr != null) {
                    mgr.spawnInitialStageDragon(event, dragonId);
                }
                initialDragonIndex++;
            }

            // All done?
            if (initialDragonIndex >= initialDragonIds.size()) {
                initialSpawnsComplete = true;
                nextInitialDragonSpawnInstant = null;
                return;
            }

            // Schedule next spawn based on event.dragonSpawnInterval
            Duration interval = event.dragonSpawnInterval();
            if (interval == null || interval.isNegative()) {
                interval = Duration.ZERO;
            }

            // If interval is zero, fall back to 1 second between spawns.
            if (interval.isZero()) {
                interval = Duration.ofSeconds(1);
            }

            nextInitialDragonSpawnInstant = nextInitialDragonSpawnInstant.plus(interval);
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

    // ---------------------------------------------------
    // INITIAL stage dragon spawn (outer helper)
    // ---------------------------------------------------

    /**
     * Spawn a single INITIAL-stage dragon using the shared DragonBuilder.
     */
    private void spawnInitialStageDragon(EventModel event, String dragonConfigId) {
        if (dragonConfigId == null || dragonConfigId.isBlank()) {
            return;
        }

        DragonModel model = DragonManager.getOrNull(dragonConfigId);
        if (model == null) {
            plugin.getLogger().warning("[ConquestDragons] INITIAL stage tried to spawn unknown dragon id '"
                    + dragonConfigId + "' for event '" + event.id() + "'.");
            return;
        }

        // Prefer configured dragon-spawn, fall back to INITIAL stage spawn
        Location spawnLoc = event.dragonSpawn();
        if (spawnLoc == null) {
            spawnLoc = resolveInitialStageSpawn(event);
        }
        if (spawnLoc == null) {
            plugin.getLogger().warning("[ConquestDragons] No valid spawn location for INITIAL dragon '"
                    + dragonConfigId + "' in event '" + event.id() + "'.");
            return;
        }

        try {
            EnderDragon dragon = DragonManager.getBuilder()
                    .model(model)
                    .spawnAt(spawnLoc)
                    .spawn();

            // Register this dragon with the bossbar/glow manager
            DragonBossbarManager mgr = DragonBossbarManager.getInstance();
            if (mgr != null) {
                mgr.trackDragon(event, dragon);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[ConquestDragons] Failed to spawn initial-stage dragon '" + dragonConfigId +
                            "' for event '" + event.id() + "'.",
                    ex
            );
        }
    }

}
