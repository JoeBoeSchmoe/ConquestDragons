package org.conquestDragons.conquestDragons.eventHandler;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central in-memory registry for all loaded EventModel instances.
 *
 * Populated by EventDataFiles during plugin startup/reload.
 * Acts as the authoritative source for all event templates.
 *
 * This manager is intentionally simple and thread-safe:
 *  - events are keyed by normalized id (lowercase)
 *  - config fields inside EventModel are immutable
 *  - runtime state (participants, running/joinWindow flags) is owned by EventModel itself
 */
public final class EventManager {

    /** Keyed by normalized event id (lowercase). */
    private static final ConcurrentMap<String, EventModel> EVENTS = new ConcurrentHashMap<>();

    private EventManager() { }

    // ---------------------------------------------------
    // Normalize
    // ---------------------------------------------------

    private static String normalize(String id) {
        return (id == null) ? "" : id.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Registry Mutations
    // ---------------------------------------------------

    /**
     * Clears all events and reloads the registry with the given models.
     *
     * Intended to be called from EventDataFiles.loadAll().
     */
    public static void reloadAll(Collection<EventModel> models) {
        EVENTS.clear();
        if (models == null) {
            return;
        }

        for (EventModel model : models) {
            if (model == null) continue;
            EVENTS.put(normalize(model.id()), model);
        }
    }

    /**
     * Register or replace a single event in the cache.
     */
    public static void register(EventModel model) {
        if (model == null) return;
        EVENTS.put(normalize(model.id()), model);
    }

    /**
     * Remove an event from the cache by id.
     */
    public static void unregister(String id) {
        if (id == null) return;
        EVENTS.remove(normalize(id));
    }

    /**
     * Clear all events.
     */
    public static void clear() {
        EVENTS.clear();
    }

    // ---------------------------------------------------
    // Lookup
    // ---------------------------------------------------

    /**
     * Find an event by ID (case-insensitive).
     */
    public static Optional<EventModel> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(EVENTS.get(normalize(id)));
    }

    /**
     * Returns the event or null if not present.
     */
    public static EventModel getOrNull(String id) {
        if (id == null) return null;
        return EVENTS.get(normalize(id));
    }

    /**
     * Unmodifiable snapshot of all events.
     */
    public static Collection<EventModel> all() {
        return Collections.unmodifiableCollection(EVENTS.values());
    }

    /**
     * All events that are enabled in config (regardless of runtime state).
     */
    public static List<EventModel> allEnabled() {
        return EVENTS.values().stream()
                .filter(EventModel::enabled)
                .toList();
    }

    /**
     * All events that are currently running (runtime flag) AND enabled.
     */
    public static List<EventModel> allRunning() {
        return EVENTS.values().stream()
                .filter(EventModel::enabled)
                .filter(EventModel::isRunning)
                .toList();
    }

    /**
     * All events that are currently joinable:
     *  - enabled in config
     *  - currently running
     *  - join window is open
     *
     * This is ideal for /dragons join auto-selection logic.
     */
    public static List<EventModel> allJoinable() {
        return EVENTS.values().stream()
                .filter(EventModel::enabled)
                .filter(EventModel::isRunning)
                .filter(EventModel::isJoinWindowOpen)
                .toList();
    }

    // ---------------------------------------------------
    // Convenience filters
    // ---------------------------------------------------

    /**
     * All events that reference a given boss dragon id.
     */
    public static List<EventModel> findByBossDragonId(String bossDragonId) {
        if (bossDragonId == null || bossDragonId.isBlank()) return List.of();
        final String normalized = normalize(bossDragonId);

        return EVENTS.values().stream()
                .filter(event -> normalized.equals(normalize(event.bossDragonId())))
                .toList();
    }

    /**
     * All events that reference the given dragon id
     * either as a non-boss dragon or as boss.
     */
    public static List<EventModel> findByDragonId(String dragonId) {
        if (dragonId == null || dragonId.isBlank()) return List.of();
        final String normalized = normalize(dragonId);

        return EVENTS.values().stream()
                .filter(event -> {
                    if (normalized.equals(normalize(event.bossDragonId()))) {
                        return true;
                    }
                    // Any non-boss dragon ids
                    return event.dragonIds().stream()
                            .map(EventManager::normalize)
                            .anyMatch(normalized::equals);
                })
                .toList();
    }

    // ---------------------------------------------------
    // Schedule helpers
    // ---------------------------------------------------

    /**
     * Lightweight value object describing an event's next scheduled run.
     */
    public record ScheduledEvent(EventModel event, Instant nextRun) { }

    /**
     * Compute the next scheduled run for each ENABLED event, using the given time zone and "now".
     *
     * Disabled events are ignored.
     *
     * This does NOT schedule anything by itself; it is a helper for your scheduler logic.
     */
    public static List<ScheduledEvent> computeNextRuns(ZoneId zone, Instant now) {
        if (zone == null || now == null) return List.of();

        return EVENTS.values().stream()
                .filter(EventModel::enabled)
                .map(event -> new ScheduledEvent(
                        event,
                        event.schedule().nextRun(zone, now)
                ))
                .toList();
    }

    /**
     * Find the single earliest next scheduled ENABLED event (if any), in the given zone.
     *
     * Useful if you want a "central tick" that always schedules the next soonest event.
     */
    public static Optional<ScheduledEvent> findNextScheduled(ZoneId zone, Instant now) {
        return computeNextRuns(zone, now).stream()
                .min((a, b) -> a.nextRun().compareTo(b.nextRun()));
    }
}
