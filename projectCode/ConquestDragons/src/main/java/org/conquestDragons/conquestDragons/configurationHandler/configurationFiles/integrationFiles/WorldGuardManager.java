package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.conquestDragons.conquestDragons.ConquestDragons;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 🛡️ WorldGuardManager (foundation-only)
 * - Config-aware "hook" to WorldGuard (no queries/logic)
 * - Optional custom StateFlag registration via reflection
 *
 * Design:
 *  • No hard WG imports (avoid NoClassDefFoundError when WG isn't installed).
 *  • Minimal surface: initialize / shutdown / register flags / status checks.
 */
public final class WorldGuardManager {

    private static final ConquestDragons plugin = ConquestDragons.getInstance();
    private static final Logger log = plugin.getLogger();

    private static boolean requestedEnabled = false;
    private static boolean active = false;
    private static String wgVersion = "unknown";

    // Store registered custom flags by name (values are the WG Flag instance via reflection)
    private static final Map<String, Object> CUSTOM_FLAGS = new HashMap<>();

    private WorldGuardManager() {
        // Utility class — prevent instantiation
    }

    /**
     * Initialize WorldGuard integration (hook only).
     * If WG is missing/disabled, we fall back gracefully.
     */
    public static void initialize(boolean enabledByConfig) {
        requestedEnabled = enabledByConfig;

        if (!enabledByConfig) {
            disable("⛔  WorldGuard integration disabled via config.");
            return;
        }

        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null || !wg.isEnabled()) {
            disable("⚠️  WorldGuard not found or not enabled. Continuing without integration.");
            return;
        }

        wgVersion = wg.getDescription().getVersion();
        active = true;
        log.info("🛡️  WorldGuard hooked. Version: " + wgVersion);
    }

    /**
     * Optional early registration of your custom flags.
     * Safe to call even if WG isn't present (it will no-op).
     *
     * Example built-ins you can use or remove later:
     *  - dragons-force-pvp
     */
    public static void registerFlagsEarly() {
        // Only attempt if we're either active or at least the plugin exists (for early boot calls).
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null) {
            log.info("ℹ️  Skipping WorldGuard flag registration (plugin not found).");
            return;
        }

        // Try to register a couple of placeholder flags for future features.
        registerCustomStateFlag("dragons-force-pvp", false);
        registerCustomStateFlag("dragons-handle-death", false);
    }

    /**
     * Register a custom StateFlag with the given name and default value.
     * Uses reflection so we don't hard-link to WG classes.
     *
     * @return true if registration succeeded; false otherwise.
     */
    public static boolean registerCustomStateFlag(String name, boolean defaultValue) {
        try {
            // new StateFlag(name, defaultValue)
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
            Constructor<?> ctor = stateFlagClass.getConstructor(String.class, boolean.class);
            Object stateFlag = ctor.newInstance(name, defaultValue);

            // WorldGuard.getInstance().getFlagRegistry().register(flag)
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object registry = wgClass.getMethod("getFlagRegistry").invoke(wg);

            Class<?> flagBaseClass = Class.forName("com.sk89q.worldguard.protection.flags.Flag");
            Method register = registry.getClass().getMethod("register", flagBaseClass);
            register.invoke(registry, stateFlag);

            CUSTOM_FLAGS.put(name.toLowerCase(), stateFlag);
            log.info("✅  Registered WorldGuard flag: " + name + " (default=" + defaultValue + ")");
            return true;
        } catch (Throwable t) {
            log.info("⚠️  Skipped WorldGuard flag '" + name + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Retrieve a previously-registered custom flag object by name (nullable).
     * The returned object is a WorldGuard Flag instance (reflected).
     */
    public static Object getCustomFlag(String name) {
        if (name == null) return null;
        return CUSTOM_FLAGS.get(name.toLowerCase());
    }

    /**
     * Shutdown hook (optional).
     */
    public static void shutdown() {
        active = false;
        log.info("🔻  WorldGuard integration shut down.");
    }

    private static void disable(String reason) {
        active = false;
        log.info(reason);
    }

    // ---------------- Status ----------------

    public static boolean isActive() {
        return active;
    }

    public static boolean wasRequestedEnabled() {
        return requestedEnabled;
    }

    public static String getWorldGuardVersion() {
        return wgVersion;
    }
}
