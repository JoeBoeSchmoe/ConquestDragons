package org.conquestDragons.conquestDragons.commandHandler.subcommandHandler;

import org.bukkit.entity.Player;
import org.conquestClans.conquestclans.commandHandler.permissionHandler.PermissionManager;
import org.conquestClans.conquestclans.commandHandler.permissionHandler.PermissionModels;
import org.conquestClans.conquestclans.responseHandler.MessageResponseManager;
import org.conquestClans.conquestclans.responseHandler.messageModels.GenericMessageModels;
import org.conquestClans.conquestclans.responseHandler.messageModels.UserMessageModels;

/**
 * 🎮 UserCommandManager
 * Handles all non-admin /clans subcommands with a switch-based dispatcher.
 */
public class UserCommandManager {

    private UserCommandManager() {}

    public static boolean handle(Player player, String subcommand, String[] args) {
        switch (subcommand) {
            case "help":
                return handleHelp(player, args);

            // Add new user subcommands here as you implement them:
            // case "create": return handleCreate(player, args);
            // case "invite": return handleInvite(player, args);
            // case "join":   return handleJoin(player, args);

            default:
                MessageResponseManager.send(player, GenericMessageModels.UNKNOWN_COMMAND);
                return true;
        }
    }

    // --------------------
    // Handlers
    // --------------------

    private static boolean handleHelp(Player player, String[] args) {
        if (!PermissionManager.has(player, PermissionModels.USER_HELP)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) { /* default 1 */ }
        }

        MessageResponseManager.sendUserHelpPage(player, "messages.user.help", page);
        return true;
    }

    // --------------------
    // Utilities (used by CommandManager)
    // --------------------

    public static boolean sendUsageHint(Player player) {
        if (PermissionManager.has(player, PermissionModels.USER_HELP)) {
            MessageResponseManager.send(player, UserMessageModels.USER_HELP_USAGE);
        } else {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
        }
        return true;
    }
}
