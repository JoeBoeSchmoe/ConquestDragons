package org.conquestDragons.conquestDragons.commandHandler.permissionHandler;

import org.bukkit.command.CommandSender;

/**
 * 🔐 PermissionManager
 * Utility class for validating command permissions in ConquestDragons.
 *
 * Provides hierarchical permission checking:
 * - Direct node check
 * - Wildcard parent check (e.g. conquestdragons.user.*)
 * - Operator (op) override
 *
 * Inspired by ConquestDragons’s PermissionManager.
 */
public class PermissionManager {

    /**
     * Checks if a {@link CommandSender} has a given permission or its parent wildcard.
     *
     * @param sender     The sender (player or console)
     * @param permission The permission node to verify
     * @return {@code true} if the sender has access
     */
    public static boolean has(CommandSender sender, PermissionModels permission) {
        if (sender.isOp()) return true; // Operators bypass checks

        String node = permission.getNode();

        // Direct permission check
        if (sender.hasPermission(node)) return true;

        // Check wildcard parents recursively (e.g. conquestdragons.user.*)
        String[] parts = node.split("\\.");
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) current.append(".");
            current.append(parts[i]);

            if (sender.hasPermission(current + ".*")) {
                return true;
            }
        }

        // No matching permission found
        return false;
    }
}
