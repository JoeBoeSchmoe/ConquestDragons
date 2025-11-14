package org.conquestDragons.conquestDragons.dragonHandler;

import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonDifficultyKey;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Locale;

/**
 * Central in-memory registry for all loaded DragonModel instances.
 *
 * Populated by DragonDataFiles during plugin startup/reload.
 * Acts as the authoritative source for all dragon templates.
 */
public final class DragonManager {

    /** Keyed by normalized dragon id (lowercase). */
    private static final ConcurrentMap<String, DragonModel> DRAGONS = new ConcurrentHashMap<>();

    private DragonManager() { }

    // ---------------------------------------------------
    // Normalize
    // ---------------------------------------------------
    private static String normalize(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // Registry Mutations
    // ---------------------------------------------------

    /**
     * Clears all dragons and reloads the registry with the given models.
     */
    public static void reloadAll(Collection<DragonModel> models) {
        DRAGONS.clear();
        if (models == null) return;

        for (DragonModel model : models) {
            if (model == null) continue;
            DRAGONS.put(normalize(model.id()), model);
        }
    }

    /**
     * Register or replace a single dragon in the cache.
     */
    public static void register(DragonModel model) {
        if (model == null) return;
        DRAGONS.put(normalize(model.id()), model);
    }

    /**
     * Remove a dragon from the cache by id.
     */
    public static void unregister(String id) {
        if (id == null) return;
        DRAGONS.remove(normalize(id));
    }

    /**
     * Clear all dragons.
     */
    public static void clear() {
        DRAGONS.clear();
    }

    // ---------------------------------------------------
    // Lookup
    // ---------------------------------------------------

    /**
     * Find a dragon by ID (case-insensitive).
     */
    public static Optional<DragonModel> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(DRAGONS.get(normalize(id)));
    }

    /**
     * Returns the dragon or null if not present.
     */
    public static DragonModel getOrNull(String id) {
        if (id == null) return null;
        return DRAGONS.get(normalize(id));
    }

    /**
     * Unmodifiable snapshot of all dragons.
     */
    public static Collection<DragonModel> all() {
        return Collections.unmodifiableCollection(DRAGONS.values());
    }

    /**
     * All dragons that use a specific difficulty key.
     */
    public static List<DragonModel> findByDifficulty(DragonDifficultyKey difficultyKey) {
        if (difficultyKey == null) return List.of();
        return DRAGONS.values().stream()
                .filter(model -> model.difficulty().difficultyKey() == difficultyKey)
                .toList();
    }
}
