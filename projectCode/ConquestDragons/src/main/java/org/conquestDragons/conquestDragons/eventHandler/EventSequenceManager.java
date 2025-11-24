package org.conquestDragons.conquestDragons.eventHandler;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
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
import org.conquestDragons.conquestDragons.dragonHandler.InBellyBossbarManager;
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
 *  - After all non-boss dragons are dead, starts FINAL stage and summons the boss dragon.
 *
 *  - NEW:
 *      • When boss dies and event completes → global victory message + participants/spectators to completionSpawn.
 *      • If participants reach 0 before completion → global defeat message + spectators ejected after same delay.
 *      • NEW tracking: each ScheduledRun remembers dragon UUIDs and automatically
 *        re-attaches them to DragonBossbarManager when chunks/entities reload.
 */
public final class EventSequenceManager {

    // ---------------------------------------------------
    // Singleton
    // ---------------------------------------------------

    private static EventSequenceManager INSTANCE;

    // 3 seconds (60 ticks) delay for stage-related teleports
    private static final long STAGE_TELEPORT_DELAY_TICKS = 100L;

    // Slight delay before swallowing players into the belly arena
    private static final long BELLY_TELEPORT_DELAY_TICKS = 100L;


    public static EventSequenceManager getInstance() {
        return INSTANCE;
    }
    private final Map<String, Set<Chunk>> forcedChunks = new ConcurrentHashMap<>();

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
        InBellyBossbarManager.start(ConquestDragons.getInstance());

        ConquestDragons.getInstance().getLogger().info(
                "✅  EventSequenceManager started (zone=" + zone + ")."
        );
        return INSTANCE;
    }

    /**
     * Stop the manager and clear all runtime state.
     */
    public static synchronized EventSequenceManager stop() {
        if (INSTANCE == null) return null;
        INSTANCE.stopTickTask();
        INSTANCE.runsByEventId.clear();
        ConquestDragons.getInstance().getLogger().info("✅  EventSequenceManager stopped.");
        EventSequenceManager old = INSTANCE;
        INSTANCE = null;
        return old;
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

        // 6) Keep dragon tracking consistent with actual entities (handles chunk unload/reload)
        ensureDragonsTracked(event, run);

        // 7) Early defeat check: if participants drop to 0 before we complete, we lose.
        checkForEarlyDefeat(event, run);
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
     * Called by DragonBossbarManager when the event's "belly trigger" should fire.
     *
     * IMPORTANT:
     * - We now assume DragonBossbarManager is using the COMBINED health of all
     *   INITIAL/POST_BELLY-stage dragons vs. their combined max health and only calls this
     *   once when that fraction falls below belly-trigger-health-fraction.
     * - When this fires the first time, we send 100% of participants into the belly.
     */
    public void onDragonBellyTrigger(EventModel event,
                                     EnderDragon dragon,
                                     double healthFraction) {
        if (event == null) {
            return;
        }

        ScheduledRun run = runsByEventId.get(event.id());
        if (run == null) {
            return;
        }

        // We only want to handle the belly trigger ONCE per run.
        if (run.inBellyStageStarted) {
            return;
        }

        // INITIAL must actually be running; otherwise ignore.
        ScheduledRun.StageRuntime initialRt = run.getStageRuntime(EventStageKey.INITIAL);
        if (initialRt == null || !initialRt.started || initialRt.ended) {
            return;
        }

        // Snapshot participants at the moment of the trigger.
        Collection<UUID> participants = event.participantsSnapshot();
        if (participants == null || participants.isEmpty()) {
            return;
        }

        Location bellySpawn = resolveInBellyStageSpawn(event);
        if (bellySpawn == null) {
            plugin.getLogger().warning("[ConquestDragons] No IN_BELLY spawn configured for event '"
                    + event.id() + "'. Belly teleport skipped.");
            return;
        }

        // Mark that we've begun the belly flow for this run.
        run.inBellyStageStarted = true;

        // Clear any per-dragon mapping; we're now doing GLOBAL belly.
        run.bellyPlayersByDragon.clear();

        // Send 100% of participants into the belly and track them.
        for (UUID uuid : participants) {
            run.inBellyParticipants.add(uuid);

            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                // Use belly-specific delay so we can sync with the dragon "eat" animation.
                scheduleBellyTeleport(uuid, bellySpawn);
            }
        }

        // After the same delay, flip the logical stage into IN_BELLY and start it.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ScheduledRun currentRun = runsByEventId.get(event.id());
            if (currentRun == null) {
                return;
            }

            // If INITIAL is still marked running, end it now.
            ScheduledRun.StageRuntime currentInitialRt =
                    currentRun.getStageRuntime(EventStageKey.INITIAL);
            if (currentInitialRt != null && currentInitialRt.started && !currentInitialRt.ended) {
                endStage(event, currentRun, EventStageKey.INITIAL);
            }

            // Switch logical current stage for this event.
            event.setCurrentStageKey(EventStageKey.IN_BELLY);

            // Start IN_BELLY stage commands/timers + start message.
            Instant startInstant = Instant.now();
            startStage(event, currentRun, EventStageKey.IN_BELLY, startInstant);

            // 🔥 Start the in-belly survival bossbar.
            InBellyBossbarManager bellyMgr = InBellyBossbarManager.getInstance();
            if (bellyMgr != null) {
                bellyMgr.startInBellyBar(event);
            }

        }, BELLY_TELEPORT_DELAY_TICKS);
    }


    // ---------------------------------------------------
    // Dragon killed callback (from DragonBossbarManager)
    // ---------------------------------------------------

    /**
     * Called when an event dragon dies.
     */
    public void onDragonKilled(EventModel event, EnderDragon dragon) {
        if (event == null || dragon == null) {
            return;
        }

        ScheduledRun run = runsByEventId.get(event.id());
        if (run == null) {
            return;
        }

        // This dragon is now genuinely gone; stop trying to track it for this run.
        run.unregisterDragon(dragon.getUniqueId());

        // We no longer do per-dragon early release from the belly.
        // IN_BELLY duration (tickInBellyDuration) is the single authority for
        // when belly players move into POST_BELLY.

        // After the kill, check if this was the last non-boss dragon.
        maybeStartFinalStageIfNoDragons(event, run);

        // ----------------------------------------------------
        // If the FINAL boss has already been spawned and now
        // there are NO dragons left, the event is over.
        // ----------------------------------------------------
        DragonBossbarManager mgr = DragonBossbarManager.getInstance();
        int remaining = (mgr != null) ? mgr.countActiveDragonsForEvent(event) : 0;

        if (run.bossSpawned
                && remaining <= 0
                && event.currentStageKey() == EventStageKey.FINAL) {
            completeEventAfterBossDeath(event, run);
        }
    }

    /**
     * Called once the FINAL boss dragon has been killed and no dragons remain.
     */
    private void completeEventAfterBossDeath(EventModel event, ScheduledRun run) {
        if (run == null) {
            return;
        }

        // Avoid double-completion if FINAL is already ended or result already resolved
        if (run.resultResolved) {
            return;
        }

        ScheduledRun.StageRuntime finalRt = run.getStageRuntime(EventStageKey.FINAL);
        if (finalRt != null && finalRt.ended) {
            return;
        }

        run.resultResolved = true;

        // End FINAL stage (runs FINAL end-commands + FINAL_STAGE_END user message)
        endStage(event, run, EventStageKey.FINAL);

        // 🔔 Global victory announcement
        broadcastEventResult(event, true);

        // Teleport participants and spectators to completionSpawn, if configured
        Location completion = event.completionSpawn();
        int movedParticipants = 0;
        int movedSpectators = 0;

        if (completion != null) {
            // Participants
            Collection<UUID> participants = event.participantsSnapshot();
            if (participants != null) {
                for (UUID uuid : participants) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        scheduleStageTeleport(uuid, completion);
                        movedParticipants++;
                    }
                }
            }

            // Spectators
            Collection<UUID> spectators = event.spectatorsSnapshot();
            if (spectators != null) {
                for (UUID uuid : spectators) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        scheduleSpectatorCompletionTeleport(uuid, completion);
                        movedSpectators++;
                    }
                }
            }

            // --------------------------------------------------
            // REWARDS (VICTORY PATH)
            // --------------------------------------------------
            try {
                EventRewardExecutor.grantCompletionRewards(event);
                EventRewardExecutor.grantRankingRewards(event, 10); // modify max ranks as needed
                plugin.getLogger().info("[ConquestDragons] Rewards granted for event '" + event.id() + "' (VICTORY).");
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "[ConquestDragons] Failed to grant rewards for event '" + event.id() + "'.", ex);
            }



            plugin.getLogger().info("[ConquestDragons] Event '" + event.id()
                    + "' completed (VICTORY). Teleporting " + movedParticipants
                    + " participant(s) and " + movedSpectators
                    + " spectator(s) to completionSpawn at "
                    + formatLocation(completion) + " after "
                    + (STAGE_TELEPORT_DELAY_TICKS / 20.0) + "s.");
        } else {
            plugin.getLogger().warning(
                    "[ConquestDragons] Event '" + event.id()
                            + "' completed (VICTORY) but has no completionSpawn configured; players will not be teleported."
            );
        }

        // Fully tear down this game run: no participants, no spectators, no belly data.
        fullyEndEvent(event, run);
    }

    /**
     * Early-loss path.
     */
    private void handleEarlyDefeat(EventModel event, ScheduledRun run) {
        if (event == null || run == null) {
            return;
        }
        if (run.resultResolved) {
            return;
        }

        run.resultResolved = true;

        // End any currently running stages cleanly (run end-commands + messages).
        for (EventStageKey key : EventStageKey.values()) {
            ScheduledRun.StageRuntime rt = run.getStageRuntime(key);
            if (rt != null && rt.started && !rt.ended) {
                endStage(event, run, key);
            }
        }

        // 🔔 Global defeat announcement
        broadcastEventResult(event, false);

        Location completion = event.completionSpawn();
        int movedSpectators = 0;

        if (completion != null) {
            Collection<UUID> spectators = event.spectatorsSnapshot();
            if (spectators != null) {
                for (UUID uuid : spectators) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        scheduleSpectatorCompletionTeleport(uuid, completion);
                        movedSpectators++;
                    }
                }
            }

            plugin.getLogger().info("[ConquestDragons] Event '" + event.id()
                    + "' DEFEAT. Teleporting " + movedSpectators
                    + " remaining spectator(s) to completionSpawn at "
                    + formatLocation(completion) + " after "
                    + (STAGE_TELEPORT_DELAY_TICKS / 20.0) + "s.");
        } else {
            plugin.getLogger().warning(
                    "[ConquestDragons] Event '" + event.id()
                            + "' DEFEAT but has no completionSpawn configured; spectators will not be teleported."
            );
        }

        // Fully tear down this game run.
        fullyEndEvent(event, run);
    }

    /**
     * Hard-ends the current game run for the given event.
     */
    private void fullyEndEvent(EventModel event, ScheduledRun run) {
        if (event == null) {
            return;
        }

        // Mark event as no longer running/joinable so region enforcement & join logic relax
        event.setRunning(false);
        event.setJoinWindowOpen(false);

        // Clear participants and spectators from the runtime model
        try {
            event.clearParticipants();
        } catch (NoSuchMethodError ignored) {
        }

        try {
            event.clearSpectators();
        } catch (NoSuchMethodError ignored) {
        }

        // Clear any in-belly tracking from this run instance
        if (run != null) {
            run.inBellyParticipants.clear();
            run.bellyPlayersByDragon.clear();
            run.bossSpawned = false;
            run.inBellyStageStarted = false;
            run.trackedDragonIds.clear();
        }
        releaseForcedChunks(event.id());

        // Remove this run from the per-event runtime map
        runsByEventId.remove(event.id());

        plugin.getLogger().info("[ConquestDragons] Event '" + event.id()
                + "' fully ended. Participants, spectators, and runtime state cleared.");
    }

    /**
     * If no dragons remain and the boss hasn't been spawned yet,
     * transition into FINAL stage and summon the boss dragon.
     *
     * IMPORTANT:
     *  - For events that have an IN_BELLY stage configured, we ONLY allow FINAL
     *    to start from POST_BELLY (after belly flow is done).
     *  - For events without a belly stage, we keep the previous
     *    "any non-lobby combat stage" behaviour.
     */
    private void maybeStartFinalStageIfNoDragons(EventModel event, ScheduledRun run) {
        if (event == null || run == null) {
            return;
        }

        // Boss already spawned → nothing to do here
        if (run.bossSpawned) {
            return;
        }

        EventStageKey currentStage = event.currentStageKey();
        if (currentStage == null) {
            return;
        }

        // Does this event actually have an IN_BELLY stage configured?
        boolean hasBellyStage = (event.findStageOrNull(EventStageKey.IN_BELLY) != null);

        if (hasBellyStage) {
            // Belly-type events:
            // Only allow FINAL transition from POST_BELLY, never from IN_BELLY/INITIAL.
            if (currentStage != EventStageKey.POST_BELLY) {
                return;
            }

            // Extra safety: make sure IN_BELLY has fully ended if it exists.
            ScheduledRun.StageRuntime bellyRt = run.getStageRuntime(EventStageKey.IN_BELLY);
            if (bellyRt != null && !bellyRt.ended) {
                return;
            }
        } else {
            // Non-belly events: keep old behaviour, but still ignore LOBBY.
            if (currentStage == EventStageKey.LOBBY) {
                return;
            }
        }

        // How many dragons are currently alive for this event?
        DragonBossbarManager mgr = DragonBossbarManager.getInstance();
        int remaining = (mgr != null) ? mgr.countActiveDragonsForEvent(event) : 0;

        // If there are still dragons alive, do nothing.
        if (remaining > 0) {
            return;
        }

        // If INITIAL spawns are NOT complete, we may still have more
        // non-boss dragons queued to spawn. Do NOT start FINAL yet.
        if (run.initialSpawnsInitialized && !run.initialSpawnsComplete) {
            return;
        }

        // At this point:
        //  - No dragons are currently alive (remaining == 0)
        //  - Either:
        //      • Event has no IN_BELLY stage and we're in a combat stage, OR
        //      • Event has an IN_BELLY stage, we're in POST_BELLY, and IN_BELLY is ended.
        //  → It is safe to start FINAL + boss.

        Instant now = Instant.now();

        // Cleanly end POST_BELLY and/or INITIAL if they're still marked running.
        ScheduledRun.StageRuntime postRt = run.getStageRuntime(EventStageKey.POST_BELLY);
        if (postRt != null && postRt.started && !postRt.ended) {
            endStage(event, run, EventStageKey.POST_BELLY);
        }

        ScheduledRun.StageRuntime initialRt = run.getStageRuntime(EventStageKey.INITIAL);
        if (initialRt != null && initialRt.started && !initialRt.ended) {
            endStage(event, run, EventStageKey.INITIAL);
        }

        // FINAL stage player spawn (arena where players go)
        Location finalSpawn = resolveFinalStageSpawn(event);
        if (finalSpawn != null) {
            for (UUID uuid : event.participantsSnapshot()) {
                scheduleStageTeleport(uuid, finalSpawn);
            }
        } else {
            plugin.getLogger().warning(
                    "[ConquestDragons] FINAL stage has no player spawn for event '"
                            + event.id() + "'. Participants will not be teleported."
            );
        }

        // Boss spawn location
        Location bossSpawnLoc = event.dragonSpawn();  // from YAML

        // Switch to FINAL stage and start it
        event.setCurrentStageKey(EventStageKey.FINAL);
        startStage(event, run, EventStageKey.FINAL, now);

        // Summon the boss dragon
        spawnBossDragon(event, bossSpawnLoc);

        run.bossSpawned = true;
    }

    private static String formatLocation(Location loc) {
        if (loc == null) {
            return "null";
        }
        if (loc.getWorld() == null) {
            return "null-world@" + loc.getX() + "," + loc.getY() + "," + loc.getZ();
        }
        return loc.getWorld().getName()
                + "@x=" + loc.getX()
                + ",y=" + loc.getY()
                + ",z=" + loc.getZ()
                + ",yaw=" + loc.getYaw()
                + ",pitch=" + loc.getPitch();
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
     * Join window ends → INITIAL stage begins.
     */
    private void onJoinWindowEnded(EventModel event, ScheduledRun run) {
        event.setJoinWindowOpen(false);
        event.setRunning(true);
        event.setCurrentStageKey(EventStageKey.INITIAL);

        // End the LOBBY stage (end-commands + end message)
        endStage(event, run, EventStageKey.LOBBY);

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
     * Schedule a delayed teleport for a participant used for stage transitions.
     */
    private void scheduleStageTeleport(UUID uuid, Location target) {
        if (target == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(target);
            }
        }, STAGE_TELEPORT_DELAY_TICKS);
    }

    /**
     * Spectator completion teleport helper:
     *  - Teleports the player to the given target.
     *  - If they are currently in SPECTATOR, restores them to SURVIVAL.
     */
    private void scheduleSpectatorCompletionTeleport(UUID uuid, Location target) {
        if (target == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    p.setGameMode(GameMode.SURVIVAL);
                }
                p.teleport(target);
            }
        }, STAGE_TELEPORT_DELAY_TICKS);
    }

    /**
     * Start a logical stage for this run.
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

        // Stage START console commands
        executeStageCommands(event, key, runtime.model.startCommands());

        // Stage START user message
        sendStageStartMessage(event, key);

        // INITIAL stage: prepare dragon spawn scheduling
        if (key == EventStageKey.INITIAL) {
            run.setupInitialDragonSpawns(event, startInstant);
            forceLoadRegion(event.dragonRegion(), event.id());

        }
    }

    /**
     * End a logical stage for this run.
     */
    private void endStage(EventModel event,
                          ScheduledRun run,
                          EventStageKey key) {

        ScheduledRun.StageRuntime runtime = run.getStageRuntime(key);
        if (runtime == null || runtime.ended) {
            return;
        }

        runtime.ended = true;

        // Stage END console commands (supports {player})
        executeStageCommands(event, key, runtime.model.endCommands());

        // Stage END user message
        sendStageEndMessage(event, key);
    }

    /**
     * Per-tick stage pipeline.
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
                    executeStageCommands(event, runtime.stageKey, batch.commands);
                }
            }

            // 2) Repeat stage message (looping while stage active)
            ScheduledRun.RepeatMessageState rm = runtime.repeatMessage;
            if (rm != null && rm.nextFireInstant != null && !now.isBefore(rm.nextFireInstant)) {
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
     * IMPORTANT: For this transition we now teleport players INSTANTLY
     * (no STAGE_TELEPORT_DELAY_TICKS) to sync with your countdown.
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
        // NOTE: This is now an INSTANT teleport, not delayed via scheduleStageTeleport.
        for (UUID uuid : new ArrayList<>(run.inBellyParticipants)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(postBellySpawn); // instant move on countdown completion
            }
        }
        run.inBellyParticipants.clear();
        run.bellyPlayersByDragon.clear();

        // Switch logical stage and start POST_BELLY (once).
        event.setCurrentStageKey(EventStageKey.POST_BELLY);
        startStage(event, run, EventStageKey.POST_BELLY, now);

        // Stop the in-belly bossbar once the stage is over
        InBellyBossbarManager bellyMgr = InBellyBossbarManager.getInstance();
        if (bellyMgr != null) {
            bellyMgr.stopInBellyBar(event);
        }
    }

    // ---------------------------------------------------
    // Helpers
    // ---------------------------------------------------

    /**
     * Schedule a delayed teleport specifically for belly captures.
     */
    private void scheduleBellyTeleport(UUID uuid, Location target) {
        if (target == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(target);
            }
        }, BELLY_TELEPORT_DELAY_TICKS);
    }

    private static Location resolveInitialStageSpawn(EventModel event) {
        EventModel.StageArea initialArea = event.stageAreaOrNull(EventStageKey.INITIAL);
        if (initialArea != null) {
            return initialArea.spawn();
        }
        return event.dragonSpawn();
    }

    private static Location resolveInBellyStageSpawn(EventModel event) {
        EventModel.StageArea bellyArea = event.stageAreaOrNull(EventStageKey.IN_BELLY);
        if (bellyArea != null) {
            return bellyArea.spawn();
        }
        return event.dragonSpawn();
    }

    private static Location resolvePostBellyStageSpawn(EventModel event) {
        EventModel.StageArea postBellyArea = event.stageAreaOrNull(EventStageKey.POST_BELLY);
        if (postBellyArea != null) {
            return postBellyArea.spawn();
        }
        return event.dragonSpawn();
    }

    private static Location resolveFinalStageSpawn(EventModel event) {
        EventModel.StageArea finalArea = event.stageAreaOrNull(EventStageKey.FINAL);
        if (finalArea != null) {
            return finalArea.spawn();
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
     * Broadcast a user message model (with placeholders) to all online players.
     */
    private void broadcastUserMessage(UserMessageModels model, Map<String, String> placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            MessageResponseManager.send(player, model, placeholders);
        }
    }

    /**
     * Global result helper: victory/defeat broadcast.
     */
    private void broadcastEventResult(EventModel event, boolean victory) {
        UserMessageModels model = victory
                ? UserMessageModels.EVENT_WIN
                : UserMessageModels.EVENT_DEFEAT;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("event", event.displayName());
        placeholders.put("eventName", event.displayName());

        broadcastUserMessage(model, placeholders);
    }

    /**
     * Early defeat detection.
     */
    private void checkForEarlyDefeat(EventModel event, ScheduledRun run) {
        if (event == null || run == null) {
            return;
        }
        if (run.resultResolved) {
            return;
        }

        // Only consider defeat for a "live" run
        if (!run.joinWindowOpened || !run.joinWindowClosed) {
            return;
        }

        // Only consider defeat while we're in a combat-related stage (not LOBBY)
        EventStageKey current = event.currentStageKey();
        if (current == null || current == EventStageKey.LOBBY) {
            return;
        }

        // If boss already spawned, let the normal completion logic own the result.
        if (run.bossSpawned) {
            return;
        }

        Collection<UUID> participants = event.participantsSnapshot();
        if (participants != null && !participants.isEmpty()) {
            return;
        }

        // No participants left pre-completion → defeat.
        handleEarlyDefeat(event, run);
    }

    /**
     * Ensure that all dragons belonging to this event run are currently being
     * tracked by DragonBossbarManager, if their entities are loaded.
     *
     * This automatically recovers when chunks containing dragons unload during
     * IN_BELLY and later reload when players return for POST_BELLY.
     */
    private void ensureDragonsTracked(EventModel event, ScheduledRun run) {
        if (event == null || run == null) {
            return;
        }

        DragonBossbarManager mgr = DragonBossbarManager.getInstance();
        if (mgr == null) {
            return;
        }

        // Snapshot to avoid concurrent modification issues if we remove ids.
        List<UUID> ids = new ArrayList<>(run.trackedDragonIds);

        for (UUID dragonId : ids) {
            if (dragonId == null) {
                continue;
            }

            // If the bossbar manager already knows about this dragon, skip.
            if (mgr.findEventForDragon(dragonId) != null) {
                continue;
            }

            // Try to resolve the entity by UUID.
            org.bukkit.entity.Entity entity = Bukkit.getEntity(dragonId);

            if (entity == null) {
                // IMPORTANT:
                // Null here usually means "entity is in an unloaded chunk/world".
                // We MUST NOT unregister the dragon; keep the UUID so that when
                // the chunk loads again we can reattach it.
                // plugin.getLogger().fine("[ConquestDragons] Dragon " + dragonId + " not currently loaded for event '" + event.id() + "'.");
                continue;
            }

            if (!(entity instanceof EnderDragon)) {
                // UUID now belongs to some other entity type or is invalid → drop it.
                run.unregisterDragon(dragonId);
                plugin.getLogger().warning("[ConquestDragons] Tracked dragon UUID " + dragonId
                        + " for event '" + event.id() + "' is no longer an EnderDragon. Removing from tracking.");
                continue;
            }

            EnderDragon dragon = (EnderDragon) entity;

            if (dragon.isDead()) {
                // Dead dragons are cleaned up by onDragonKilled, but if we missed it
                // make sure we don't keep retrying here.
                run.unregisterDragon(dragonId);
                plugin.getLogger().info("[ConquestDragons] Tracked dragon " + dragonId
                        + " for event '" + event.id() + "' is dead. Removing from tracking.");
                continue;
            }

            // At this point we have a live EnderDragon entity that the
            // DragonBossbarManager does NOT know about → (re)attach it.
            try {
                mgr.trackDragon(event, dragon);
                plugin.getLogger().info("[ConquestDragons] Re-attached dragon " + dragon.getUniqueId()
                        + " to DragonBossbarManager for event '" + event.id() + "'.");
            } catch (Exception ex) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[ConquestDragons] Failed to (re)attach dragon " + dragonId
                                + " to DragonBossbarManager for event '" + event.id() + "'.",
                        ex
                );
            }
        }
    }


    // ---------------------------------------------------
    // Stage user messages: START / TIMED / END
    // ---------------------------------------------------

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
        final Instant runEndInstant;

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

        // FINAL stage / boss tracking
        boolean bossSpawned = false;

        // Result guard: once true, victory/defeat has been resolved.
        boolean resultResolved = false;

        // NEW: dragon ownership tracking for this run (non-boss and boss)
        final Set<UUID> trackedDragonIds = ConcurrentHashMap.newKeySet();

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
                if (!now.isBefore(r.fireInstant)) {
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

        // ----- dragon UUID tracking --------------------------------------

        void registerDragon(UUID dragonId) {
            if (dragonId != null) {
                trackedDragonIds.add(dragonId);
            }
        }

        void unregisterDragon(UUID dragonId) {
            if (dragonId != null) {
                trackedDragonIds.remove(dragonId);
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

            // Use the configured dragonSpawnInterval for the *first* spawn as well.
            Duration interval = event.dragonSpawnInterval();
            if (interval == null || interval.isNegative()) {
                interval = Duration.ZERO;
            }

            // If no interval is configured, default to 1 second between spawns.
            if (interval.isZero()) {
                interval = Duration.ofSeconds(1);
            }

            // First dragon spawns AFTER the interval, not instantly at stage start.
            this.nextInitialDragonSpawnInstant = stageStartInstant.plus(interval);
        }


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

        static final class PendingTimedCommand {
            final Instant fireInstant;
            final List<String> commands;
            boolean executed;

            PendingTimedCommand(Instant fireInstant, List<String> commands) {
                this.fireInstant = fireInstant;
                this.commands = commands;
            }
        }

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
    // INITIAL & FINAL stage dragon spawns (outer helpers)
    // ---------------------------------------------------

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

            plugin.getLogger().info("[CD][INITIAL] Spawned INITIAL dragon "
                    + dragon.getUniqueId() + " (configId=" + dragonConfigId
                    + ", event=" + event.id() + ") at " + formatLocation(spawnLoc));

            // Register this dragon with the current run so we can keep tracking it
            ScheduledRun run = runsByEventId.get(event.id());
            if (run != null) {
                run.registerDragon(dragon.getUniqueId());
            }

            // Use retry-based tracking so the first dragon can't miss the bossbar manager.
            trackDragonWithRetry(event, dragon, 5, 5L);

        } catch (Exception ex) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[ConquestDragons] Failed to spawn initial-stage dragon '" + dragonConfigId +
                            "' for event '" + event.id() + "'.",
                    ex
            );
        }

    }

    private void forceLoadRegion(EventModel.EventRegion region, String eventId) {
        if (region == null) return;

        Location min = region.cornerMin();
        Location max = region.cornerMax();
        if (min == null || max == null) return;

        if (min.getWorld() != max.getWorld()) return;

        Set<Chunk> set = ConcurrentHashMap.newKeySet();

        int minChunkX = min.getBlockX() >> 4;
        int maxChunkX = max.getBlockX() >> 4;
        int minChunkZ = min.getBlockZ() >> 4;
        int maxChunkZ = max.getBlockZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Chunk chunk = min.getWorld().getChunkAt(cx, cz);
                chunk.setForceLoaded(true);
                set.add(chunk);
            }
        }

        forcedChunks.put(eventId, set);

        plugin.getLogger().info("[CD] Force-loaded "
                + set.size() + " chunks for event " + eventId);
    }

    private void releaseForcedChunks(String eventId) {
        Set<Chunk> set = forcedChunks.remove(eventId);
        if (set == null) return;

        for (Chunk chunk : set) {
            try {
                chunk.setForceLoaded(false);
            } catch (Exception ignored) {}
        }

        plugin.getLogger().info("[CD] Released forced chunks for event " + eventId);
    }


    /**
     * Ensure a dragon gets tracked even if DragonBossbarManager wasn't ready
     * at the exact moment it spawned.
     *
     * We retry a few times with small delays. Once tracking succeeds or the
     * dragon dies, retries stop.
     */
    private void trackDragonWithRetry(EventModel event,
                                      EnderDragon dragon,
                                      int attemptsRemaining,
                                      long retryDelayTicks) {
        if (event == null || dragon == null || attemptsRemaining <= 0) {
            return;
        }

        // If the dragon is already gone, don't bother.
        if (dragon.isDead() || dragon.getWorld() == null) {
            return;
        }

        DragonBossbarManager mgr = DragonBossbarManager.getInstance();
        if (mgr != null) {
            try {
                mgr.trackDragon(event, dragon);
                return;
            } catch (Exception ex) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[ConquestDragons] Failed to track dragon " + dragon.getUniqueId()
                                + " for event '" + event.id() + "' on this attempt.",
                        ex
                );
            }
        }

        int nextAttempts = attemptsRemaining - 1;

        Bukkit.getScheduler().runTaskLater(plugin, () ->
                        trackDragonWithRetry(event, dragon, nextAttempts, retryDelayTicks),
                retryDelayTicks
        );
    }


    /**
     * Spawn the FINAL-stage boss dragon using event.bossDragonId().
     */
    private void spawnBossDragon(EventModel event, Location preferredSpawn) {
        String bossId = event.bossDragonId();
        if (bossId == null || bossId.isBlank()) {
            plugin.getLogger().warning("[ConquestDragons] No boss-dragon-id configured for event '"
                    + event.id() + "'. FINAL boss will not be spawned.");
            return;
        }

        DragonModel model = DragonManager.getOrNull(bossId);
        if (model == null) {
            plugin.getLogger().warning("[ConquestDragons] Boss dragon id '" + bossId
                    + "' not found in DragonModel registry for event '" + event.id() + "'.");
            return;
        }

        Location spawnLoc = preferredSpawn;
        if (spawnLoc == null) {
            spawnLoc = resolveFinalStageSpawn(event);
        }
        if (spawnLoc == null) {
            spawnLoc = event.dragonSpawn();
        }
        if (spawnLoc == null) {
            plugin.getLogger().warning("[ConquestDragons] No valid spawn location for boss dragon '"
                    + bossId + "' in event '" + event.id() + "'.");
            return;
        }

        try {
            EnderDragon dragon = DragonManager.getBuilder()
                    .model(model)
                    .spawnAt(spawnLoc)
                    .spawn();

            // Register boss dragon as well so automatic re-tracking works in FINAL
            ScheduledRun run = runsByEventId.get(event.id());
            if (run != null) {
                run.registerDragon(dragon.getUniqueId());
            }

            trackDragonWithRetry(event, dragon, 5, 5L);

        } catch (Exception ex) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[ConquestDragons] Failed to spawn boss dragon '" + bossId +
                            "' for event '" + event.id() + "'.",
                    ex
            );
        }
    }

    /**
     * Execute a batch of commands in the context of a specific event + stage.
     */
    private void executeStageCommands(EventModel event,
                                      EventStageKey stageKey,
                                      List<String> commands) {
        if (event == null || stageKey == null || commands == null || commands.isEmpty()) {
            return;
        }

        // Determine target UUIDs for {player} expansion
        Collection<UUID> targetUuids = null;

        if (stageKey == EventStageKey.IN_BELLY) {
            ScheduledRun run = runsByEventId.get(event.id());
            if (run != null && !run.inBellyParticipants.isEmpty()) {
                targetUuids = new ArrayList<>(run.inBellyParticipants);
            }
        }

        // Fallback: all participants of the event
        if (targetUuids == null) {
            Collection<UUID> participants = event.participantsSnapshot();
            if (participants != null && !participants.isEmpty()) {
                targetUuids = new ArrayList<>(participants);
            }
        }

        for (String raw : commands) {
            if (raw == null) continue;
            String cmd = raw.trim();
            if (cmd.isEmpty()) continue;

            // If command does not contain {player}, run once globally
            if (!cmd.contains("{player}")) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Exception ex) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Failed to execute event command: \"" + cmd + "\"",
                            ex
                    );
                }
                continue;
            }

            // Commands WITH {player} but no targets → just skip quietly
            if (targetUuids == null || targetUuids.isEmpty()) {
                plugin.getLogger().fine("[ConquestDragons] Skipping {player} command with no targets: " + cmd);
                continue;
            }

            // Per-player expansion
            for (UUID uuid : targetUuids) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;

                String perPlayerCmd = cmd.replace("{player}", p.getName());
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), perPlayerCmd);
                } catch (Exception ex) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Failed to execute per-player event command for " + p.getName()
                                    + ": \"" + perPlayerCmd + "\"",
                            ex
                    );
                }
            }
        }
    }

}
