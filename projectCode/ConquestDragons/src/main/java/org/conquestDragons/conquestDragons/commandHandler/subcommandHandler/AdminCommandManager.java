package org.conquestDragons.conquestDragons.commandHandler.subcommandHandler;

import org.bukkit.entity.Player;
import org.conquestClans.conquestclans.ConquestClans;
import org.conquestClans.conquestclans.commandHandler.permissionHandler.PermissionManager;
import org.conquestClans.conquestclans.commandHandler.permissionHandler.PermissionModels;
import org.conquestClans.conquestclans.responseHandler.MessageResponseManager;
import org.conquestClans.conquestclans.responseHandler.messageModels.AdminMessageModels;
import org.conquestClans.conquestclans.responseHandler.messageModels.GenericMessageModels;

/**
 * 👑 AdminCommandManager
 * Handles /clans admin subcommands.
 *
 * Uses AdminMessageModels and MessageResponseManager for
 * all feedback, following the same pattern as UserCommandManager.
 */
public class AdminCommandManager {

    /**
     * Handles /clans admin <subcommand>
     *
     * @param player player executing the command
     * @param args   full argument array
     * @return true if handled, false otherwise
     */
    public static boolean handle(Player player, String[] args) {
        if (!hasAdminPermission(player, PermissionModels.ADMIN_BASE)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        if (args.length < 2) {
            sendUsageHint(player);
            return true;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "help":
                return handleHelp(player, args);
            case "reload":
                return handleReload(player);
            default:
                MessageResponseManager.send(player, GenericMessageModels.UNKNOWN_COMMAND);
                return true;
        }
    }

    /**
     * Sends the admin help-usage hint message.
     * Equivalent to: messages.admin.help-usage in adminMessages.yml
     */
    public static boolean sendUsageHint(Player player) {
        MessageResponseManager.send(player, AdminMessageModels.ADMIN_HELP_USAGE);
        return true;
    }

    /**
     * Handles /clans admin help [page]
     */
    private static boolean handleHelp(Player player, String[] args) {
        if (!hasAdminPermission(player, PermissionModels.ADMIN_HELP)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException ignored) {
                // fallback to 1
            }
        }

        // Corresponds to "messages.admin.help" section in adminMessages.yml
        MessageResponseManager.sendAdminHelpPage(player, "messages.admin.help", page);
        return true;
    }

    /**
     * Handles /clans admin reload
     */
    private static boolean handleReload(Player player) {
        if (!hasAdminPermission(player, PermissionModels.ADMIN_RELOAD)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        ConquestClans.getInstance().reload();
        MessageResponseManager.send(player, AdminMessageModels.RELOAD_SUCCESS);
        return true;
    }

    // ─────────────────────────────────────────────
    // Permission Helpers
    // ─────────────────────────────────────────────

    /** Checks for specific admin sub-permission (or wildcard). */
    private static boolean hasAdminPermission(Player player, PermissionModels neededPermission) {
        return PermissionManager.has(player, PermissionModels.ADMIN_ALL)
                || (PermissionManager.has(player, PermissionModels.ADMIN_BASE)
                && PermissionManager.has(player, neededPermission));
    }
}
