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
 *
 *  - dragonIds                : list of NON-BOSS dragon config IDs in the INITIAL phase
 *                               (players fight these in the main arena)
 *
 *  - bossDragonId             : single final boss dragon config ID
 *
 *  - arenaRegion              : axis-aligned cubic region defined by two Locations
 *                               (corner A/B), used by AI and logic to clamp the arena.
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
 *                               start/timed/end commands per stage (INITIAL, IN_BELLY, FINAL, etc.).
 *
 * Runtime-only state:
 *  - participants             : set of players (UUIDs) currently in this event
 *
 * Note: configuration fields are immutable; the participants set is intentionally
 * mutable runtime state owned by this event instance.
 */
public final class EventModel {

    private final String id;
    private final String displayName;

    /** Non-boss dragon config IDs. */
    private final List<String> dragonIds;

    /** Final boss dragon config ID. */
    private final String bossDragonId;

    /** Main arena region as a rectangular box. */
    private final ArenaRegion arenaRegion;

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

    /** Ordered stage definitions (INITIAL -> IN_BELLY -> FINAL, etc.). */
    private final List<EventStageModel> stages;

    /**
     * Players currently in this event instance.
     * Runtime state; thread-safe set of UUIDs.
     */
    private final ConcurrentMap<UUID, Boolean> participants = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    public EventModel(String id,
                      String displayName,
                      List<String> dragonIds,
                      String bossDragonId,
                      Location arenaCornerA,
                      Location arenaCornerB,
                      double bellyTriggerHealthFraction,
                      Duration maxDuration,
                      Duration joinWindowLength,
                      Duration joinReminderInterval,
                      EventSchedule schedule,
                      List<EventStageModel> stages) {

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id cannot be null/blank");
        }
        if (bellyTriggerHealthFraction < 0.0 || bellyTriggerHealthFraction > 1.0) {
            throw new IllegalArgumentException("bellyTriggerHealthFraction must be between 0.0 and 1.0");
        }
        Objects.requireNonNull(arenaCornerA, "arenaCornerA");
        Objects.requireNonNull(arenaCornerB, "arenaCornerB");

        this.id = id;
        this.displayName = (displayName == null || displayName.isBlank()) ? id : displayName;

        this.dragonIds = unmodifiableCopy(dragonIds);
        this.bossDragonId = Objects.requireNonNull(bossDragonId, "bossDragonId");

        this.arenaRegion = new ArenaRegion(arenaCornerA, arenaCornerB);

        this.bellyTriggerHealthFraction = bellyTriggerHealthFraction;
        this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        this.joinWindowLength = Objects.requireNonNull(joinWindowLength, "joinWindowLength");
        this.joinReminderInterval = Objects.requireNonNull(joinReminderInterval, "joinReminderInterval");
        this.schedule = Objects.requireNonNull(schedule, "schedule");

        this.stages = unmodifiableCopy(stages);
        if (this.stages.isEmpty()) {
            throw new IllegalArgumentException("EventModel must have at least one stage");
        }
    }

    private static <T> List<T> unmodifiableCopy(List<T> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(src));
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

    /** Non-boss dragon config IDs. */
    public List<String> dragonIds() {
        return dragonIds;
    }

    /** Final boss dragon config ID. */
    public String bossDragonId() {
        return bossDragonId;
    }

    /** Arena region (axis-aligned box). */
    public ArenaRegion arenaRegion() {
        return arenaRegion;
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

    // ---------------------------------------------------------------------
    // Stage helpers
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
    // Immutability: with() helpers (config-level fields only)
    // ---------------------------------------------------------------------

    public EventModel withMaxDuration(Duration newDuration) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                Objects.requireNonNull(newDuration, "newDuration"),
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages
        );
    }

    public EventModel withStages(List<EventStageModel> newStages) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                newStages
        );
    }

    public EventModel withDragonIds(List<String> newDragonIds) {
        return new EventModel(
                this.id,
                this.displayName,
                newDragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages
        );
    }

    public EventModel withBossDragonId(String newBossId) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                Objects.requireNonNull(newBossId, "newBossId"),
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages
        );
    }

    public EventModel withArenaCorners(Location cornerA, Location cornerB) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                cornerA,
                cornerB,
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages
        );
    }

    public EventModel withBellyTriggerHealthFraction(double newFraction) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                newFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                this.schedule,
                this.stages
        );
    }

    public EventModel withJoinWindow(Duration newJoinWindow) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                Objects.requireNonNull(newJoinWindow, "newJoinWindow"),
                this.joinReminderInterval,
                this.schedule,
                this.stages
        );
    }

    public EventModel withJoinReminderInterval(Duration newInterval) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                Objects.requireNonNull(newInterval, "newInterval"),
                this.schedule,
                this.stages
        );
    }

    public EventModel withSchedule(EventSchedule newSchedule) {
        return new EventModel(
                this.id,
                this.displayName,
                this.dragonIds,
                this.bossDragonId,
                this.arenaRegion.cornerMin(),
                this.arenaRegion.cornerMax(),
                this.bellyTriggerHealthFraction,
                this.maxDuration,
                this.joinWindowLength,
                this.joinReminderInterval,
                Objects.requireNonNull(newSchedule, "newSchedule"),
                this.stages
        );
    }

    @Override
    public String toString() {
        return "EventModel{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", dragonIds=" + dragonIds +
                ", bossDragonId='" + bossDragonId + '\'' +
                ", arenaRegion=" + arenaRegion +
                ", bellyTriggerHealthFraction=" + bellyTriggerHealthFraction +
                ", maxDuration=" + maxDuration +
                ", joinWindowLength=" + joinWindowLength +
                ", joinReminderInterval=" + joinReminderInterval +
                ", schedule=" + schedule +
                ", stages=" + stages +
                ", participants=" + participants.keySet() +
                '}';
    }

    // ---------------------------------------------------------------------
    // ArenaRegion value object
    // ---------------------------------------------------------------------

    /**
     * Axis-aligned 3D box defined by two corners in a single world.
     *
     * Used to clamp dragon AI and to quickly check if a player or mob
     * is inside the event arena.
     */
    public static final class ArenaRegion {

        private final String worldName;

        private final double minX;
        private final double minY;
        private final double minZ;

        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private final Location cornerMin;
        private final Location cornerMax;

        public ArenaRegion(Location a, Location b) {
            World worldA = a.getWorld();
            World worldB = b.getWorld();
            if (worldA == null || worldB == null) {
                throw new IllegalArgumentException("ArenaRegion corners must have a world");
            }
            if (!worldA.getName().equals(worldB.getName())) {
                throw new IllegalArgumentException("ArenaRegion corners must be in the same world");
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
            return "ArenaRegion{" +
                    "worldName='" + worldName + '\'' +
                    ", min=(" + minX + "," + minY + "," + minZ + ')' +
                    ", max=(" + maxX + "," + maxY + "," + maxZ + ')' +
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
     *   day-of-week: FRIDAY    # only for WEEKLY
     *   day-of-month: 1        # only for MONTHLY
     */
    public static final class EventSchedule {

        private final RepeatType repeatType;
        private final LocalTime timeOfDay;
        private final DayOfWeek dayOfWeek; // only for WEEKLY, else null
        private final int dayOfMonth;      // only for MONTHLY, else 0

        public EventSchedule(RepeatType repeatType,
                             LocalTime timeOfDay,
                             DayOfWeek dayOfWeek,
                             int dayOfMonth) {

            this.repeatType = Objects.requireNonNull(repeatType, "repeatType");
            this.timeOfDay = Objects.requireNonNull(timeOfDay, "timeOfDay");
            this.dayOfWeek = dayOfWeek;
            this.dayOfMonth = dayOfMonth;
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
                    '}';
        }
    }
}
