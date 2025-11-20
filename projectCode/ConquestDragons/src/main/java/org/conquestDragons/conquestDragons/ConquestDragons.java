package org.conquestDragons.conquestDragons;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.conquestDragons.conquestDragons.commandHandler.CommandManager;
import org.conquestDragons.conquestDragons.configurationHandler.ConfigurationManager;
import org.conquestDragons.conquestDragons.eventHandler.EventManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.eventHandler.EventSequenceManager;
import org.conquestDragons.conquestDragons.listenerHandler.CommandRestrictionListener;
import org.conquestDragons.conquestDragons.listenerHandler.EventRegionManager;

import java.util.*;
import java.util.logging.Level;

/**
 * 🧱 ConquestDragons - Main plugin class.
 *
 * Bootstraps configuration, commands, listeners, and subsystem managers.
 * Keep logic tiny here—delegate to services/managers for testability.
 */
public final class ConquestDragons extends JavaPlugin {

    private static ConquestDragons pluginInstance;

    private ConfigurationManager configurationManager;
    private final boolean premiumEnabled = true;

    private CommandManager commandManager;
    private String primaryCommandName; // resolved from plugin.yml at runtime

    @Override
    public void onLoad() {
        pluginInstance = this;
    }

    @Override
    public void onEnable() {
        getLogger().info("🔧  Enabling ConquestDragons...");

        // ---------------------------------------------------
        // Config / data
        // ---------------------------------------------------
        configurationManager = new ConfigurationManager();
        configurationManager.initialize(); // load base configs (config.yml, etc.)

        // ---------------------------------------------------
        // Commands & listeners
        // ---------------------------------------------------
        setupCommands();
        registerListeners(
                // new PlayerJoinListener()
                // new ClanChatListener()
                // new RegionGuardListener()
                new CommandRestrictionListener(),
                new EventRegionManager()
        );

        // ---------------------------------------------------
        // Runtime managers
        // ---------------------------------------------------
        EventSequenceManager.start();

        getLogger().info("✅  ConquestDragons fully loaded.");
    }

    @Override
    public void onDisable() {
        getLogger().info("📦  Saving plugin state...");

        // Emergency: clean up any running/joinable events and dragons
        emergencyTerminateActiveEvents();

        // Stop scheduled event sequences cleanly
        EventSequenceManager.stop();

        getLogger().info("🔻  ConquestDragons has been disabled.");
    }

    /**
     * Reloads configs and hot-reloadable subsystems safely.
     */
    public void reload() {
        getLogger().info("🔄  Reloading ConquestDragons...");

        // Before touching configs or schedules, clean up any active events/dragons.
        emergencyTerminateActiveEvents();

        // Base configs
        configurationManager.initialize();

        // Reset event sequencing runtime to match new schedules/config
        EventSequenceManager.stop();
        EventSequenceManager.start();

        // Keep command binding intact, but re-sync aliases from config
        syncCommandAliases();

        getLogger().info("✅  Reload complete.");
    }

    /**
     * Binds the command from plugin.yml (whatever root the user configured)
     * and syncs aliases from config: command-aliases.
     */
    private void setupCommands() {
        // Resolve our declared commands from plugin.yml
        Map<String, Map<String, Object>> declared = getDescription().getCommands();
        if (declared.isEmpty()) {
            getLogger().severe("❌  No commands declared in plugin.yml! Aborting command setup.");
            return;
        }

        // Choose the first declared command name as our primary (typical plugins declare only one)
        primaryCommandName = declared.keySet().iterator().next();

        PluginCommand pluginCommand = getCommand(primaryCommandName);
        if (pluginCommand == null) {
            getLogger().severe("❌  Command '" + primaryCommandName + "' not found in plugin.yml!");
            return;
        }

        // Create a single manager instance (executor + tab)
        commandManager = new CommandManager();
        pluginCommand.setExecutor(commandManager);
        pluginCommand.setTabCompleter(commandManager);

        // Initial alias sync from config
        syncCommandAliases();

        getLogger().info("🧭  Bound root command: /" + primaryCommandName);
    }

    /**
     * Sync aliases from config (command-aliases) to the bound PluginCommand.
     * If config includes the primary name, we strip it (Bukkit handles the base).
     */
    private void syncCommandAliases() {
        if (primaryCommandName == null) return;

        PluginCommand pluginCommand = getCommand(primaryCommandName);
        if (pluginCommand == null) return;

        List<String> configured = getConfig().getStringList("command-aliases");

        // Normalize + de-duplicate
        Set<String> unique = new LinkedHashSet<>();
        for (String s : configured) {
            if (s == null) continue;
            String alias = s.trim();
            if (alias.isEmpty()) continue;
            if (alias.equalsIgnoreCase(primaryCommandName)) continue; // don't duplicate base as alias
            unique.add(alias.toLowerCase(Locale.ROOT));
        }

        List<String> aliases = new ArrayList<>(unique);
        pluginCommand.setAliases(aliases);

        // Log helpful hints if plugin.yml and config look out-of-sync
        Map<String, Map<String, Object>> declared = getDescription().getCommands();
        Map<String, Object> meta = declared.get(primaryCommandName);
        @SuppressWarnings("unchecked")
        List<String> ymlAliases = meta != null
                ? (List<String>) meta.getOrDefault("aliases", Collections.emptyList())
                : Collections.emptyList();

        getLogger().log(Level.INFO, () ->
                "📝  Command sync → primary: /" + primaryCommandName +
                        ", yml-aliases: " + ymlAliases +
                        ", cfg-aliases: " + aliases
        );
    }

    private void registerListeners(Listener... listeners) {
        for (Listener listener : listeners) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
    }

    // ---------------------------------------------------------------------
    // Emergency cleanup on disable / reload
    // ---------------------------------------------------------------------

    /**
     * Emergency safety:
     *
     * - For any event that is currently running OR has an open join window:
     *      • Teleport all participants to completionSpawn (if defined),
     *        otherwise dragonSpawn, otherwise their world spawn.
     *      • Mark event as not running and not joinable.
     *
     * - Then hard-kill all EnderDragons across all worlds.
     *
     * This is used on plugin disable and reload to avoid leaving players
     * stuck in belly/final arenas or leaving stray dragons alive.
     */
    private void emergencyTerminateActiveEvents() {
        int affectedEvents = 0;
        int teleportedPlayers = 0;
        int killedDragons = 0;

        // 1) Clean up events + participants
        for (EventModel eventModel : EventManager.all()) {
            boolean active = eventModel.isRunning() || eventModel.isJoinWindowOpen();
            if (!active) {
                continue;
            }

            affectedEvents++;

            // Decide where to send players:
            //  - prefer completionSpawn
            //  - fallback to dragonSpawn
            Location target = eventModel.completionSpawn();
            if (target == null) {
                target = eventModel.dragonSpawn();
            }

            for (UUID uuid : eventModel.participantsSnapshot()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    continue;
                }

                Location safeTarget = target;
                if (safeTarget == null) {
                    // As an absolute fallback, use the player's world spawn
                    safeTarget = player.getWorld().getSpawnLocation();
                }

                player.teleport(safeTarget);
                teleportedPlayers++;
            }

            // Mark event as no longer active; RegionManager will stop enforcing
            eventModel.setRunning(false);
            eventModel.setJoinWindowOpen(false);
        }

        // 2) Kill all EnderDragons in all worlds
        for (World world : Bukkit.getWorlds()) {
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                try {
                    dragon.setHealth(0.0);
                } catch (Throwable t) {
                    // If something weird happens, just remove the entity
                    dragon.remove();
                }
                killedDragons++;
            }
        }

        if (affectedEvents > 0 || teleportedPlayers > 0 || killedDragons > 0) {
            getLogger().info(
                    "🧹 Emergency cleanup → events=" + affectedEvents +
                            ", playersTeleported=" + teleportedPlayers +
                            ", dragonsKilled=" + killedDragons
            );
        }
    }

    // ---- Accessors (prefer constructor injection elsewhere) ----

    public static ConquestDragons getInstance() {
        return pluginInstance;
    }

    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public boolean isPremium() {
        return premiumEnabled;
    }
}
