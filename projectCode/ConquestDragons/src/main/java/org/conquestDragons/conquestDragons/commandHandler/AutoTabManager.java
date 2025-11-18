package org.conquestDragons.conquestDragons.commandHandler;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.conquestDragons.conquestDragons.commandHandler.permissionHandler.PermissionManager;
import org.conquestDragons.conquestDragons.commandHandler.permissionHandler.PermissionModels;
import org.conquestDragons.conquestDragons.eventHandler.EventManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 🔄 AutoTabManager
 * Minimal tab completion for ConquestDragons commands.
 *
 * Matches the plugin.yml commands and permissions structure.
 */
public class AutoTabManager {

    // Root-level user subcommands
    private static final List<String> USER_SUBCOMMANDS = List.of(
            "help",
            "join",
            "leave",
            "spectate"
    );

    // Admin root label
    private static final String ADMIN_ROOT = "admin";

    // Admin subcommands (expand as needed)
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "reload",
            "help"
    );

    /**
     * Returns a list of tab suggestions based on command input and permissions.
     *
     * @param sender the command sender
     * @param args   the command arguments
     * @return a list of matching suggestions
     */
    public static List<String> getSuggestions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();

        // /dragons <sub>
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(USER_SUBCOMMANDS);

            // Only show "admin" if player has admin perms
            if (PermissionManager.has(sender, PermissionModels.ADMIN_BASE) ||
                    PermissionManager.has(sender, PermissionModels.ADMIN_ALL)) {
                suggestions.add(ADMIN_ROOT);
            }

            return partialMatch(args[0], suggestions);
        }

        // /dragons join <eventName>
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            // Only suggest if player has join permission
            if (!PermissionManager.has(sender, PermissionModels.USER_JOIN)) {
                return Collections.emptyList();
            }

            // Suggest event ids based on partial
            List<String> eventIds = EventManager.all().stream()
                    .map(EventModel::id)
                    .toList();

            return partialMatch(args[1], eventIds);
        }

        // /dragons leave  -> no further args
        if (args.length >= 2 && args[0].equalsIgnoreCase("leave")) {
            // No extra arguments for /dragons leave
            return Collections.emptyList();
        }

        // /dragons spectate <eventName|leave>
        if (args.length == 2 && args[0].equalsIgnoreCase("spectate")) {
            List<String> options = new ArrayList<>();

            // /dragons spectate leave
            if (PermissionManager.has(sender, PermissionModels.USER_SPECTATE_LEAVE)) {
                options.add("leave");
            }

            // /dragons spectate <eventName>
            if (PermissionManager.has(sender, PermissionModels.USER_SPECTATE)) {
                List<String> eventIds = EventManager.all().stream()
                        .map(EventModel::id)
                        .toList();
                options.addAll(eventIds);
            }

            if (options.isEmpty()) {
                return Collections.emptyList();
            }

            return partialMatch(args[1], options);
        }

        // /dragons admin <sub>
        if (args.length == 2 && args[0].equalsIgnoreCase(ADMIN_ROOT)) {
            if (!PermissionManager.has(sender, PermissionModels.ADMIN_BASE) &&
                    !PermissionManager.has(sender, PermissionModels.ADMIN_ALL)) {
                return Collections.emptyList();
            }

            return partialMatch(args[1], ADMIN_SUBCOMMANDS);
        }

        return Collections.emptyList();
    }

    // Helper method for partial matches
    private static List<String> partialMatch(String input, List<String> options) {
        String needle = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(needle)) {
                matches.add(opt);
            }
        }
        return matches;
    }
}
