package org.conquestDragons.conquestDragons.eventHandler;

import org.bukkit.Location;
import org.bukkit.World;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * High-level configuration + core runtime container for a dragon event.
 *
 * Config-driven fields (from EventData/*.yml):
 *  - id                       : stable config id for this event
 *  - displayName              : MiniMessage display name for UI/logs
 *  - enabled                  : whether this event is active/usable at all
 *
 *  - dragonIds                : list of NON-BOSS dragon config IDs in the INITIAL / POST_BELLY phases
 *                               (players fight these in the main arena)
 *
 *  - bossDragonId             : single final boss dragon config ID
 *
 *  - dragonRegion             : GLOBAL axis-aligned cubic region used to clamp
 *                               dragon movement / AI (where dragons are allowed to exist).
 *
 *  - dragonSpawn              : primary spawn location for dragons (usually the boss,
 *                               but can also be used as a central reference for others).
 *
 *  - completionSpawn          : global completion / exit location for players after
 *                               the event ends or when they leave spectator mode.
 *                               Falls back to dragonSpawn when not configured.
 *
 *  - stageAreas               : OPTIONAL per-stage player arena region + spawn location,
 *                               so we can smoothly move players and keep them inside bounds
 *                               for each stage.
 *
 *  - bellyTriggerHealthFraction:
 *        per-dragon trigger point for belly capture.
 *        When (currentHealth / maxHealth) <= this fraction,
 *        that dragon captures its share of players into the belly.
 *        Range: 0.0–1.0 (e.g. 0.35 = 35% HP).
 *
 *  - maxDuration              : maximum allowed length for this event from start
 *
 *  - joinWindowLength         : how long players are allowed to join after the event opens
 *  - joinReminderInterval     : how often to broadcast join reminders during that window
 *
 *  - schedule                 : repeating schedule definition (DAILY/WEEKLY/MONTHLY + local time)
 *
 *  - stages                   : ordered list of {@link EventStageModel} that define
 *                               start/timed/end commands per stage (LOBBY, INITIAL, IN_BELLY,
 *                               POST_BELLY, FINAL, etc.).
 *
 *  - completionRewards        : reward rolls applied to all participants after a successful event.
 *                               Each has a chancePercent [0.0–100.0] and one or more commands
 *                               to run if it hits.
 *
 *  - rankingRewards           : per-rank rewards keyed by final damage rank
 *                               (e.g. rank 1, rank 2, ..., rank 10), each with its own
 *                               chancePercent [0.0–100.0].
 *
 * Runtime-only state:
 *  - participants             : set of players (UUIDs) currently in this event
 *  - spectators               : set of players (UUIDs) currently spectating this event
 *  - damageByPlayer           : total damage dealt per participant (for ranking)
 *
 * Note: configuration fields are immutable; the participants/spectators & damage maps are
 * mutable runtime state owned by this event instance.
 */
public final class EventModel {

    private final String id;
    private final String displayName;
    private final boolean enabled;

    /** Non-boss dragon config IDs. */
    private final List<String> dragonIds;

    /** Final boss dragon config ID. */
    private final String bossDragonId;

    /**
     * Global region for dragons (movement / AI clamp).
     */
    private final EventRegion dragonRegion;

    /**
     * Global dragon spawn location.
     * Typically inside {@link #dragonRegion()}.
     */
    private final Location dragonSpawn;

    /**
     * Global completion / exit location for players after the event ends
     * or when they leave spectator mode.
     * Falls back to {@link #dragonSpawn()} when not configured.
     */
    private final Location completionSpawn;

    /**
     * OPTIONAL per-stage area definitions for players:
     *  - region: bounds to keep players inside during that stage
     *  - spawn : central teleport location for that stage
     */
    private final Map<EventStageKey, StageArea> playingAreas;

    /**
     * Per-dragon health trigger for belly capture.
     * When dragonHealth / maxHealth <= this, that dragon "eats" its share.
     */
    private final double bellyTriggerHealthFraction;

    /** Maximum allowed duration for the event. */
    private final Duration maxDuration;

    /** How long players can join after the event opens. */
    private final Duration joinWindowLength;

    /** Interval between join reminder broadcasts. */
    private final Duration joinReminderInterval;

    /** Repeating schedule definition (DAILY / WEEKLY / MONTHLY + time-of-day). */
    private final EventSchedule schedule;

    /** Ordered stage definitions. */
    private final List<EventStageModel> stages;

    /**
     * Rewards rolled for all participants on successful event completion.
     * (chancePercent 0.0–100.0)
     */
    private final List<RewardSpec> completionRewards;

    /**
     * Rank-based rewards (per final damage rank: rank=1,2,...).
     * (chancePercent 0.0–100.0)
     */
    private final List<RankingRewardSpec> rankingRewards;

    /**
     * Players currently in this event instance.
     * Runtime state; thread-safe set of UUIDs.
     */
    private final ConcurrentMap<UUID, Boolean> participants = new ConcurrentHashMap<>();

    /**
     * Players currently spectating this event instance.
     * Runtime state; thread-safe set of UUIDs.
     */
    private final ConcurrentMap<UUID, Boolean> spectators = new ConcurrentHashMap<>();

    /**
     * Total damage dealt per participant (runtime only).
     * Populated by combat listeners and used for ranking rewards.
     */
    private final ConcurrentMap<UUID, Double> damageByPlayer = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    /**
     * Constructor WITHOUT explicit dragonSpawn / completionSpawn / stageAreas / rewards.
     * dragonSpawn will default to the center of the dragonRegion.
     *
     * `enabled` is explicit so the loader can wire from YAML.
     */
    public EventModel(String id,
                      String displayName,
                      boolean enabled,
                      List<String> dragonIds,
                      String bossDragonId,
                      Location dragonCornerA,
                      Location dragonCornerB,
                      double bellyTriggerHealthFraction,
                      Duration maxDuration,
                      Duration joinWindowLength,
                      Duration joinReminderInterval,
                      EventSchedule schedule,
                      List<EventStageModel> stages) {
        this(
                id,
                displayName,
                enabled,
                dragonIds,
                bossDragonId,
                dragonCornerA,
                dragonCornerB,
                /* dragonSpawn      */ null,
                /* completionSpawn  */ null,
                /* stageAreas       */ Collections.emptyMap(),
                bellyTriggerHealthFraction,
                maxDuration,
                joinWindowLength,
                joinReminderInterval,
                schedule,
                stages,
                /* completionRewards */ Collections.emptyList(),
                /* rankingRewards    */ Collections.emptyList()
        );
    }

    /**
     * Full constructor including explicit dragonSpawn + completionSpawn +
     * per-stage areas + rewards.
     */
    public EventModel(String id,
                      String displayName,
                      boolean enabled,
                      List<String> dragonIds,
                      String bossDragonId,
                      Location dragonCornerA,
                      Location dragonCornerB,
                      Location dragonSpawn,
                      Location completionSpawn,
                      Map<EventStageKey, StageArea> playingAreas,
                      double bellyTriggerHealthFraction,
                      Duration maxDuration,
                      Duration joinWindowLength,
                      Duration joinReminderInterval,
                      EventSchedule schedule,
                      List<EventStageModel> stages,
                      List<RewardSpec> completionRewards,
                      List<RankingRewardSpec> rankingRewards) {

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id cannot be null/blank");
        }
        if (bellyTriggerHealthFraction < 0.0 || bellyTriggerHealthFraction > 1.0) {
            throw new IllegalArgumentException("bellyTriggerHealthFraction must be between 0.0 and 1.0");
        }
        Objects.requireNonNull(dragonCornerA, "dragonCornerA");
        Objects.requireNonNull(dragonCornerB, "dragonCornerB");

        this.id = id;
        this.displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
        this.enabled = enabled;

        this.dragonIds = unmodifiableCopy(dragonIds);
        this.bossDragonId = Objects.requireNonNull(bossDragonId, "bossDragonId");

        this.dragonRegion = new EventRegion(dragonCornerA, dragonCornerB);
        this.dragonSpawn = resolveDragonSpawn(dragonSpawn, this.dragonRegion);
        this.completionSpawn = resolveCompletionSpawn(completionSpawn, this.dragonRegion, this.dragonSpawn);

        this.playingAreas = unmodifiableStageAreaMap(playingAreas);

        this.bellyTriggerHealthFraction = bellyTriggerHealthFraction;
        this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        this.joinWindowLength = Objects.requireNonNull(joinWindowLength, "joinWindowLength");
        this.joinReminderInterval = Objects.requireNonNull(joinReminderInterval, "joinReminderInterval");
        this.schedule = Objects.requireNonNull(schedule, "schedule");

        this.stages = unmodifiableCopy(stages);
        if (this.stages.isEmpty()) {
            throw new IllegalArgumentException("EventModel must have at least one stage");
        }

        this.completionRewards = unmodifiableCopy(completionRewards);
        this.rankingRewards = unmodifiableCopy(rankingRewards);
    }

    private static <T> List<T> unmodifiableCopy(List<T> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    private static Map<EventStageKey, StageArea> unmodifiableStageAreaMap(Map<EventStageKey, StageArea> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyMap();
        }
        EnumMap<EventStageKey, StageArea> copy = new EnumMap<>(EventStageKey.class);
        for (Map.Entry<EventStageKey, StageArea> e : src.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            copy.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Decide final dragonSpawn:
     *  - If non-null, validate same world as dragonRegion and use it.
     *  - If null, default to center of dragonRegion.
     */
    private static Location resolveDragonSpawn(Location candidate, EventRegion region) {
        Location min = region.cornerMin();
        Location max = region.cornerMax();
        World world = min.getWorld();
        if (world == null) {
            throw new IllegalStateException("dragonRegion has no world for corners");
        }

        if (candidate != null) {
            World spawnWorld = candidate.getWorld();
            if (spawnWorld == null || !spawnWorld.getName().equals(world.getName())) {
                throw new IllegalArgumentException("dragonSpawn must be in the same world as dragonRegion");
            }
            return candidate.clone();
        }

        // Default: center of region
        double cx = (min.getX() + max.getX()) / 2.0;
        double cy = (min.getY() + max.getY()) / 2.0;
        double cz = (min.getZ() + max.getZ()) / 2.0;
        return new Location(world, cx, cy, cz);
    }

    /**
     * Decide final completionSpawn:
     *  - If non-null, validate same world as dragonRegion and use it.
     *  - If null, default to dragonSpawn (already resolved and validated).
     */
    private static Location resolveCompletionSpawn(Location candidate,
                                                   EventRegion region,
                                                   Location dragonSpawn) {
        Location min = region.cornerMin();
        World regionWorld = min.getWorld();
        if (regionWorld == null) {
            throw new IllegalStateException("dragonRegion has no world for corners");
        }

        if (candidate != null) {
            World spawnWorld = candidate.getWorld();
            if (spawnWorld == null || !spawnWorld.getName().equals(regionWorld.getName())) {
                throw new IllegalArgumentException("completionSpawn must be in the same world as dragonRegion");
            }
            return candidate.clone();
        }

        // Default: reuse dragon spawn
        return dragonSpawn.clone();
    }

    // ---------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** True if this event is active/configured for use. */
    public boolean enabled() {
        return enabled;
    }

    /** Non-boss dragon config IDs. */
    public List<String> dragonIds() {
        return dragonIds;
    }

    /** Final boss dragon config ID. */
    public String bossDragonId() {
        return bossDragonId;
    }

    /** Global dragon region (movement / AI clamp for dragons). */
    public EventRegion dragonRegion() {
        return dragonRegion;
    }

    /** Global dragon spawn location. */
    public Location dragonSpawn() {
        return dragonSpawn.clone();
    }

    /** Completion / exit spawn location. */
    public Location completionSpawn() {
        return completionSpawn.clone();
    }

    /**
     * Unmodifiable snapshot of per-stage player areas.
     */
    public Map<EventStageKey, StageArea> stageAreas() {
        return playingAreas;
    }

    /**
     * Per-stage player area for the given key, or null if not defined.
     * Runtime can fall back to {@link #dragonRegion()} / {@link #dragonSpawn()} when this is null.
     */
    public StageArea stageAreaOrNull(EventStageKey key) {
        if (key == null || playingAreas.isEmpty()) return null;
        return playingAreas.get(key);
    }

    /** Health fraction at which a dragon should capture its share of players. */
    public double bellyTriggerHealthFraction() {
        return bellyTriggerHealthFraction;
    }

    /** Maximum allowed duration for the event. */
    public Duration maxDuration() {
        return maxDuration;
    }

    /** How long players may join after the event opens. */
    public Duration joinWindowLength() {
        return joinWindowLength;
    }

    /** Interval between join reminder broadcasts. */
    public Duration joinReminderInterval() {
        return joinReminderInterval;
    }

    /** Schedule configuration for this event. */
    public EventSchedule schedule() {
        return schedule;
    }

    /** Ordered stage definitions. */
    public List<EventStageModel> stages() {
        return stages;
    }

    /** Global completion rewards (chancePercent 0.0–100.0). */
    public List<RewardSpec> completionRewards() {
        return completionRewards;
    }

    /** Rank-based damage rewards (chancePercent 0.0–100.0). */
    public List<RankingRewardSpec> rankingRewards() {
        return rankingRewards;
    }

    // ---------------------------------------------------------------------
    // Stage helpers (logic stages, not geometry)
    // ---------------------------------------------------------------------

    public EventStageModel firstStage() {
        return stages.getFirst();
    }

    public EventStageModel lastStage() {
        return stages.getLast();
    }

    public EventStageModel findStageOrNull(EventStageKey key) {
        if (key == null) return null;
        for (EventStageModel stage : stages) {
            if (stage.stageKey() == key) {
                return stage;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Participants (runtime)
    // ---------------------------------------------------------------------

    /** Add a player to this event's participant list. */
    public void addParticipant(UUID playerId) {
        if (playerId == null) return;
        participants.put(playerId, Boolean.TRUE);
    }

    /** Remove a player from this event's participant list. */
    public void removeParticipant(UUID playerId) {
        if (playerId == null) return;
        participants.remove(playerId);
        damageByPlayer.remove(playerId);
    }

    /** Check if the given player is part of this event. */
    public boolean isParticipant(UUID playerId) {
        if (playerId == null) return false;
        return participants.containsKey(playerId);
    }

    /** Snapshot of all participants' UUIDs. */
    public Set<UUID> participantsSnapshot() {
        return Collections.unmodifiableSet(participants.keySet());
    }

    // ---------------------------------------------------------------------
    // Spectators (runtime)
    // ---------------------------------------------------------------------

    /** Add a player to this event's spectator list. */
    public void addSpectator(UUID playerId) {
        if (playerId == null) return;
        spectators.put(playerId, Boolean.TRUE);
    }

    /** Remove a player from this event's spectator list. */
    public void removeSpectator(UUID playerId) {
        if (playerId == null) return;
        spectators.remove(playerId);
    }

    /** Check if the given player is spectating this event. */
    public boolean isSpectator(UUID playerId) {
        if (playerId == null) return false;
        return spectators.containsKey(playerId);
    }

    /** Snapshot of all spectators' UUIDs. */
    public Set<UUID> spectatorsSnapshot() {
        return Collections.unmodifiableSet(spectators.keySet());
    }

    // ---------------------------------------------------------------------
    // Damage tracking (runtime)
    // ---------------------------------------------------------------------

    /**
     * Add damage dealt by a participant to their running total.
     *
     * @param playerId player UUID
     * @param amount   damage dealt (ignored if <= 0)
     */
    public void recordDamage(UUID playerId, double amount) {
        if (playerId == null || amount <= 0.0) return;
        damageByPlayer.merge(playerId, amount, Double::sum);
    }

    /** Total damage dealt by a given player (0.0 if none). */
    public double totalDamage(UUID playerId) {
        if (playerId == null) return 0.0;
        return damageByPlayer.getOrDefault(playerId, 0.0);
    }

    /** Unmodifiable snapshot of all damage totals. */
    public Map<UUID, Double> damageSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(damageByPlayer));
    }

    /**
     * Return a descending damage leaderboard (UUID → total damage),
     * truncated to at most {@code maxEntries} entries.
     */
    public List<Map.Entry<UUID, Double>> topDamage(int maxEntries) {
        if (maxEntries <= 0 || damageByPlayer.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<UUID, Double>> list = new ArrayList<>(damageByPlayer.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        if (list.size() > maxEntries) {
            list = list.subList(0, maxEntries);
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    // ---------------------------------------------------------------------
    // Immutability: with() helpers (config-level fields only)
    // ---------------------------------------------------------------------

    public EventModel withEnabled(boolean newEnabled) {
        return new EventModel(
                this.id,
                this.displayName,
                newEnabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withMaxDuration(Duration newDuration) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                Objects.requireNonNull(newDuration, "newDuration"),
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withStages(List<EventStageModel> newStages) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                newStages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withDragonIds(List<String> newDragonIds) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                newDragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withBossDragonId(String newBossId) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                Objects.requireNonNull(newBossId, "newBossId"),
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withDragonRegion(Location cornerA, Location cornerB) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                cornerA,
                cornerB,
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withDragonRegionAndSpawn(Location cornerA, Location cornerB, Location newDragonSpawn) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                cornerA,
                cornerB,
                newDragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withStageAreas(Map<EventStageKey, StageArea> newStageAreas) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                newStageAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withBellyTriggerHealthFraction(double newFraction) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                newFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withJoinWindow(Duration newJoinWindow) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                Objects.requireNonNull(newJoinWindow, "newJoinWindow"),
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withJoinReminderInterval(Duration newInterval) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                Objects.requireNonNull(newInterval, "newInterval"),
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withSchedule(EventSchedule newSchedule) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                Objects.requireNonNull(newSchedule, "newSchedule"),
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    public EventModel withCompletionRewards(List<RewardSpec> newCompletionRewards) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                newCompletionRewards,
                this.rankingRewards
        );
    }

    public EventModel withRankingRewards(List<RankingRewardSpec> newRankingRewards) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                this.completionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                newRankingRewards
        );
    }

    public EventModel withCompletionSpawn(Location newCompletionSpawn) {
        return new EventModel(
                this.id,
                this.displayName,
                this.enabled,
                this.dragonIds,
                this.bossDragonId,
                this.dragonRegion.cornerMin(),
                this.dragonRegion.cornerMax(),
                this.dragonSpawn,
                newCompletionSpawn,
                this.playingAreas,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages,
                this.completionRewards,
                this.rankingRewards
        );
    }

    @Override
    public String toString() {
        return "EventModel{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", enabled=" + enabled +
                ", dragonIds=" + dragonIds +
                ", bossDragonId='" + bossDragonId + '\'' +
                ", dragonRegion=" + dragonRegion +
                ", dragonSpawn=" + dragonSpawn +
                ", completionSpawn=" + completionSpawn +
                ", stageAreas=" + playingAreas +
                ", bellyTriggerHealthFraction=" + bellyTriggerHealthFraction +
                ", maxDuration=" + maxDuration +
                ", joinWindowLength=" + joinWindowLength +
                ", joinReminderInterval=" + joinReminderInterval +
                ", schedule=" + schedule +
                ", stages=" + stages +
                ", completionRewards=" + completionRewards +
                ", rankingRewards=" + rankingRewards +
                ", participants=" + participants.keySet() +
                ", spectators=" + spectators.keySet() +
                ", damageByPlayerSize=" + damageByPlayer.size() +
                '}';
    }

    // ---------------------------------------------------------------------
    // EventRegion value object
    // ---------------------------------------------------------------------

    /**
     * Axis-aligned 3D box defined by two corners in a single world.
     *
     * Used to clamp dragon AI and to quickly check if an entity
     * is inside the event space.
     *
     * Both the global dragonRegion and per-stage playingAreas use this.
     */
    public static final class EventRegion {

        private final String worldName;

        private final double minX;
        private final double minY;
        private final double minZ;

        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private final Location cornerMin;
        private final Location cornerMax;

        public EventRegion(Location a, Location b) {
            World worldA = a.getWorld();
            World worldB = b.getWorld();
            if (worldA == null || worldB == null) {
                throw new IllegalArgumentException("EventRegion corners must have a world");
            }
            if (!worldA.getName().equals(worldB.getName())) {
                throw new IllegalArgumentException("EventRegion corners must be in the same world");
            }

            this.worldName = worldA.getName();

            this.minX = Math.min(a.getX(), b.getX());
            this.minY = Math.min(a.getY(), b.getY());
            this.minZ = Math.min(a.getZ(), b.getZ());

            this.maxX = Math.max(a.getX(), b.getX());
            this.maxY = Math.max(a.getY(), b.getY());
            this.maxZ = Math.max(a.getZ(), b.getZ());

            this.cornerMin = new Location(worldA, minX, minY, minZ);
            this.cornerMax = new Location(worldA, maxX, maxY, maxZ);
        }

        public String worldName() {
            return worldName;
        }

        public Location cornerMin() {
            return cornerMin.clone();
        }

        public Location cornerMax() {
            return cornerMax.clone();
        }

        /** True if the given location is inside (inclusive) this region. */
        public boolean contains(Location loc) {
            if (loc == null || loc.getWorld() == null) return false;
            if (!loc.getWorld().getName().equals(worldName)) return false;

            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();

            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        @Override
        public String toString() {
            return "EventRegion{" +
                    "worldName='" + worldName + '\'' +
                    ", min=(" + minX + "," + minY + "," + minZ + ')' +
                    ", max=(" + maxX + "," + maxY + "," + maxZ + ')' +
                    '}';
        }
    }

    // ---------------------------------------------------------------------
    // StageArea value object (per-stage player region + spawn)
    // ---------------------------------------------------------------------

    /**
     * Defines the geometric context for a single stage for PLAYERS:
     *  - region: bounds for that stage (players should stay inside)
     *  - spawn : preferred spawn/teleport location within that region
     *
     * Note: region is an EventRegion, exactly like dragonRegion.
     */
    public static final class StageArea {

        private final EventRegion region;
        private final Location spawn;

        public StageArea(EventRegion region, Location spawn) {
            this.region = Objects.requireNonNull(region, "region");
            this.spawn = Objects.requireNonNull(spawn, "spawn");

            World worldFromRegion = region.cornerMin().getWorld();
            World worldFromSpawn = spawn.getWorld();
            if (worldFromRegion == null || worldFromSpawn == null ||
                    !worldFromRegion.getName().equals(worldFromSpawn.getName())) {
                throw new IllegalArgumentException("StageArea region and spawn must be in the same world");
            }
        }

        public EventRegion region() {
            return region;
        }

        public Location spawn() {
            return spawn.clone();
        }

        @Override
        public String toString() {
            return "StageArea{" +
                    "region=" + region +
                    ", spawn=" + spawn +
                    '}';
        }
    }

    // ---------------------------------------------------------------------
    // Reward value objects
    // ---------------------------------------------------------------------

    /**
     * Global reward definition:
     *  - chancePercent: 0.0–100.0 (per participant roll)
     *  - commands: one or more commands to run if the roll succeeds
     *
     * Typically used after a successful event, iterating all participants.
     *
     * YAML:
     *   rewards:
     *     completion:
     *       - chance: 100.0
     *         commands:
     *           - "give {player} minecraft:emerald 3"
     */
    public static final class RewardSpec {

        private final double chancePercent;
        private final List<String> commands;

        public RewardSpec(double chancePercent, List<String> commands) {
            if (chancePercent < 0.0 || chancePercent > 100.0) {
                throw new IllegalArgumentException("chancePercent must be between 0.0 and 100.0 (inclusive)");
            }
            List<String> cmds = EventModel.unmodifiableCopy(commands);
            if (cmds.isEmpty()) {
                throw new IllegalArgumentException("commands must not be empty");
            }
            this.chancePercent = chancePercent;
            this.commands = cmds;
        }

        /** Chance in percent (0.0–100.0) as configured in YAML. */
        public double chancePercent() {
            return chancePercent;
        }

        /** Convenience: normalized 0.0–1.0 chance for internal RNG use. */
        public double chanceNormalized() {
            return chancePercent / 100.0;
        }

        public List<String> commands() {
            return commands;
        }

        @Override
        public String toString() {
            return "RewardSpec{" +
                    "chancePercent=" + chancePercent +
                    ", commands=" + commands +
                    '}';
        }
    }

    /**
     * Rank-based reward:
     *  - rank: 1-based final damage rank (1 = highest damage)
     *  - chancePercent: 0.0–100.0 chance that this reward pays out
     *  - commands: commands to run if this reward rolls successfully
     *
     * YAML:
     *   rewards:
     *     ranking:
     *       - rank: 1
     *         chance: 100.0
     *         commands: [...]
     *       - rank: 2
     *         chance: 80.0
     *         commands: [...]
     */
    public static final class RankingRewardSpec {

        private final int rank;
        private final double chancePercent;
        private final List<String> commands;

        public RankingRewardSpec(int rank,
                                 double chancePercent,
                                 List<String> commands) {

            if (rank < 1) {
                throw new IllegalArgumentException("rank must be >= 1");
            }
            if (chancePercent < 0.0 || chancePercent > 100.0) {
                throw new IllegalArgumentException("chancePercent must be between 0.0 and 100.0 (inclusive)");
            }

            List<String> cmds = EventModel.unmodifiableCopy(commands);
            if (cmds.isEmpty()) {
                throw new IllegalArgumentException("commands must not be empty");
            }

            this.rank = rank;
            this.chancePercent = chancePercent;
            this.commands = cmds;
        }

        /** 1-based final damage rank this reward applies to. */
        public int rank() {
            return rank;
        }

        /** Chance in percent (0.0–100.0) as configured in YAML. */
        public double chancePercent() {
            return chancePercent;
        }

        /** Normalized 0.0–1.0 chance for RNG use. */
        public double chanceNormalized() {
            return chancePercent / 100.0;
        }

        public List<String> commands() {
            return commands;
        }

        /** True if this reward applies to the given 1-based rank. */
        public boolean appliesToRank(int rank) {
            return this.rank == rank;
        }

        @Override
        public String toString() {
            return "RankingRewardSpec{" +
                    "rank=" + rank +
                    ", chancePercent=" + chancePercent +
                    ", commands=" + commands +
                    '}';
        }
    }

    // ---------------------------------------------------------------------
    // EventSchedule value object (matches "schedule" in YAML)
    // ---------------------------------------------------------------------

    public enum RepeatType {
        DAILY,
        WEEKLY,
        MONTHLY
    }

    /**
     * Repeating schedule definition:
     *
     * schedule:
     *   repeat: DAILY | WEEKLY | MONTHLY
     *   time:
     *     hour: 19
     *     minute: 30
     *     second: 0
     *   day-of-week: FRIDAY      # only for WEEKLY
     *   day-of-month: 1          # only for MONTHLY
     *   pre-start-reminders:     # optional; times BEFORE start
     *     - "1H"
     *     - "30M"
     *     - "15M"
     *     - "5M"
     *     - "1M"
     */
    public static final class EventSchedule {

        private final RepeatType repeatType;
        private final LocalTime timeOfDay;
        private final DayOfWeek dayOfWeek; // only for WEEKLY, else null
        private final int dayOfMonth;      // only for MONTHLY, else 0;
        private final List<Duration> preStartReminderOffsets;

        public EventSchedule(RepeatType repeatType,
                             LocalTime timeOfDay,
                             DayOfWeek dayOfWeek,
                             int dayOfMonth,
                             List<Duration> preStartReminderOffsets) {

            this.repeatType = Objects.requireNonNull(repeatType, "repeatType");
            this.timeOfDay = Objects.requireNonNull(timeOfDay, "timeOfDay");
            this.dayOfWeek = dayOfWeek;
            this.dayOfMonth = dayOfMonth;
            this.preStartReminderOffsets = EventModel.unmodifiableCopy(preStartReminderOffsets);
        }

        public RepeatType repeatType() {
            return repeatType;
        }

        public LocalTime timeOfDay() {
            return timeOfDay;
        }

        public DayOfWeek dayOfWeek() {
            return dayOfWeek;
        }

        public int dayOfMonth() {
            return dayOfMonth;
        }

        /**
         * Offsets BEFORE the scheduled start time when you should send
         * "Dragons attacking in {time}" style buildup messages.
         *
         * Values are positive Durations; actual reminder instants are:
         *   nextRunInstant.minus(offset)
         */
        public List<Duration> preStartReminderOffsets() {
            return preStartReminderOffsets;
        }

        /**
         * Convenience: compute the next run Instant given a timezone and "now".
         * (You can call this from your scheduler logic.)
         */
        public Instant nextRun(ZoneId zone, Instant nowInstant) {
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(nowInstant, "nowInstant");

            ZonedDateTime now = nowInstant.atZone(zone);

            return switch (repeatType) {
                case DAILY -> nextDaily(now).toInstant();
                case WEEKLY -> nextWeekly(now).toInstant();
                case MONTHLY -> nextMonthly(now).toInstant();
            };
        }

        private ZonedDateTime nextDaily(ZonedDateTime now) {
            ZonedDateTime candidate = now.with(timeOfDay);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            return candidate;
        }

        private ZonedDateTime nextWeekly(ZonedDateTime now) {
            if (dayOfWeek == null) {
                return nextDaily(now); // fallback
            }
            int currentDow = now.getDayOfWeek().getValue();
            int targetDow = dayOfWeek.getValue();
            int diff = (targetDow - currentDow + 7) % 7;

            ZonedDateTime candidate = now.with(timeOfDay).plusDays(diff);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusWeeks(1);
            }
            return candidate;
        }

        private ZonedDateTime nextMonthly(ZonedDateTime now) {
            int desiredDom = (dayOfMonth <= 0) ? now.getDayOfMonth() : dayOfMonth;

            int currentLength = now.toLocalDate().lengthOfMonth();
            int dom = Math.min(desiredDom, currentLength);

            ZonedDateTime candidate = now.withDayOfMonth(dom).with(timeOfDay);
            if (!candidate.isAfter(now)) {
                ZonedDateTime nextMonth = now.plusMonths(1);
                int nextLen = nextMonth.toLocalDate().lengthOfMonth();
                int nextDom = Math.min(desiredDom, nextLen);
                candidate = nextMonth.withDayOfMonth(nextDom).with(timeOfDay);
            }
            return candidate;
        }

        @Override
        public String toString() {
            return "EventSchedule{" +
                    "repeatType=" + repeatType +
                    ", timeOfDay=" + timeOfDay +
                    ", dayOfWeek=" + dayOfWeek +
                    ", dayOfMonth=" + dayOfMonth +
                    ", preStartReminderOffsets=" + preStartReminderOffsets +
                    '}';
        }
    }
}
