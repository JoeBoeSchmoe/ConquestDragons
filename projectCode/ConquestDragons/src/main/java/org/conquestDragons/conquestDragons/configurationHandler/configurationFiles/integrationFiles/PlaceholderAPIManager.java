package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 🧩 PlaceHolderAPIManager (ConquestDragons)
 * - Config-aware initialization
 * - Graceful fallback if PAPI is not installed/enabled
 * - Minimal PlaceholderExpansion registered under "ConquestDragons" (future-ready)
 * - MiniMessage parsing utilities + simple {key} placeholder replacement
 */
public final class PlaceholderAPIManager {

    private static final ConquestDragons plugin = ConquestDragons.getInstance();
    private static final Logger log = plugin.getLogger();

    private static boolean requestedEnabled = false;
    private static boolean usingPapi = false;
    private static PlaceholderAPIPlugin papiPlugin = null;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private PlaceholderAPIManager() {
        // Utility class — prevent instantiation
    }

    /**
     * Initialize PlaceholderAPI integration based on config flag.
     * If PAPI is missing or disabled, we operate in fallback mode (no exceptions).
     */
    public static void initialize(boolean configEnabled) {
        requestedEnabled = configEnabled;

        if (!configEnabled) {
            disable("⛔  PlaceholderAPI disabled via config.");
            return;
        }

        Plugin found = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (!(found instanceof PlaceholderAPIPlugin papi)) {
            disable("⚠️  PlaceholderAPI not found. Continuing without integration.");
            return;
        }

        if (!found.isEnabled()) {
            disable("⚠️  PlaceholderAPI is installed but not enabled. Continuing without integration.");
            return;
        }

        // Register a minimal expansion now; add real placeholders later as features land.
        new PlaceholderExpansion() {
            @Override
            public @NotNull String getIdentifier() {
                return "ConquestDragons";
            }

            @Override
            public @NotNull String getAuthor() {
                return "ConquestCoder";
            }

            @Override
            public @NotNull String getVersion() {
                return "1.0.0";
            }

            @Override
            public boolean persist() {
                return true;
            }

            @Override
            public boolean canRegister() {
                return true;
            }

            /**
             * Placeholder entrypoint.
             * Examples to add later:
             *   %ConquestDragons:dragon.name%
             */
            @Override
            public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
                // Foundation-only: return null for unknown keys.
                // Add actual keys when dragon features are implemented.
                // Example scaffold:
                // if (identifier.equalsIgnoreCase("clan.name")) { ... }
                // if (identifier.equalsIgnoreCase("clan.member_count")) { ... }
                return null;
            }
        }.register();

        papiPlugin = papi;
        usingPapi = true;
        log.info("📘  PlaceholderAPI hooked. Version: " + found.getDescription().getVersion() + " | Expansion: ConquestDragons");
    }

    /**
     * Graceful shutdown/unregister logic (safe to call onDisable()).
     */
    public static void shutdown() {
        usingPapi = false;
        papiPlugin = null;
        log.info("🔻  PlaceholderAPI integration shut down.");
    }

    private static void disable(String reason) {
        usingPapi = false;
        papiPlugin = null;
        log.info(reason);
    }

    // ---------------- Public Status ----------------

    /** True if config requested PAPI usage (regardless of availability). */
    public static boolean wasRequestedEnabled() {
        return requestedEnabled;
    }

    /** True if PAPI is installed, enabled, and our expansion is active. */
    public static boolean isUsingPlaceholderAPI() {
        return usingPapi;
    }

    /** Access to the raw PlaceholderAPI plugin, if active. */
    public static @Nullable PlaceholderAPIPlugin getPapiPlugin() {
        return papiPlugin;
    }

    // ---------------- Parsing Utilities (null-safe) ----------------

    /**
     * Parses a MiniMessage string after performing simple {key} replacements and PAPI expansions.
     * - {key} replacements happen first (from the provided map)
     * - If PAPI is active and player != null, %placeholders% are expanded
     * - Returns Component.empty() for null/empty input
     */
    public static Component parse(@Nullable Player player, @Nullable String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) return Component.empty();

        // Apply simple {key} replacements
        String parsed = raw;
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                parsed = parsed.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }

        // Expand PAPI (if active)
        if (player != null && usingPapi) {
            try {
                parsed = PlaceholderAPI.setPlaceholders(player, parsed);
            } catch (Throwable t) {
                log.warning("⚠️  Failed to parse PlaceholderAPI placeholders: " + t.getMessage());
            }
        }

        // Deserialize with MiniMessage
        return MINI.deserialize(parsed);
    }

    /** Overload with no custom {key} replacements. */
    public static Component parse(@Nullable Player player, @Nullable String raw) {
        return parse(player, raw, Collections.emptyMap());
    }

    /** Plain string variant using Adventure plain text serializer. */
    public static String parsePlain(@Nullable Player player, @Nullable String raw, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(parse(player, raw, placeholders));
    }

    /** Convenience for raw string with optional PAPI, no MiniMessage deserialize. */
    public static String parsePlaceholders(@Nullable Player player, @Nullable String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String parsed = raw;
        if (player != null && usingPapi) {
            try {
                parsed = PlaceholderAPI.setPlaceholders(player, parsed);
            } catch (Throwable t) {
                log.warning("⚠️  Failed to parse PlaceholderAPI placeholders: " + t.getMessage());
            }
        }
        return parsed;
    }

    // ---------------- Small helper for building {key} maps ----------------

    public static class PlaceholderSet {
        private final Map<String, String> placeholders = new HashMap<>();

        public PlaceholderSet add(String key, String value) {
            placeholders.put(key, value);
            return this;
        }

        public Map<String, String> build() {
            return placeholders;
        }

        /** Apply {key} replacements to a raw string (no PAPI, no MiniMessage). */
        public static String applyToStatic(@Nullable String raw, Map<String, String> externalPlaceholders) {
            if (raw == null) return "";
            String result = raw;
            if (externalPlaceholders != null) {
                for (Map.Entry<String, String> entry : externalPlaceholders.entrySet()) {
                    result = result.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
            return result;
        }
    }
}
