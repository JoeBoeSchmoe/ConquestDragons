package org.conquestDragons.conquestDragons.commandHandler;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.conquestClans.conquestclans.commandHandler.permissionHandler.PermissionManager;
import org.conquestClans.conquestclans.commandHandler.permissionHandler.PermissionModels;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 🔄 AutoTabManager
 * Minimal tab completion for ConquestClans commands.
 *
 * Matches the plugin.yml commands and permissions structure.
 */
public class AutoTabManager {

    // Root-level subcommands available to all players
    private static final List<String> USER_SUBCOMMANDS = List.of("help", "admin");

    // Admin-only root subcommand
    private static final String ADMIN_ROOT = "admin";

    // Admin subcommands (expand as needed)
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "help");

    /**
     * Returns a list of tab suggestions based on command input and permissions.
     *
     * @param sender the command sender
     * @param args   the command arguments
     * @return a list of matching suggestions
     */
    public static List<String> getSuggestions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();

        // /clans <sub>
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(USER_SUBCOMMANDS);

            // Only show "admin" if player has admin perms
            if (PermissionManager.has(sender, PermissionModels.ADMIN_BASE) ||
                    PermissionManager.has(sender, PermissionModels.ADMIN_ALL)) {
                suggestions.add(ADMIN_ROOT);
            }

            return partialMatch(args[0], suggestions);
        }

        // /clans admin <sub>
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
