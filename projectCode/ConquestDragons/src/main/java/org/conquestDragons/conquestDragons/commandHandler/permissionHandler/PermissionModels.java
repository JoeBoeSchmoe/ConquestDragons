package org.conquestDragons.conquestDragons.commandHandler.permissionHandler;

/**
 * 🔐 PermissionModels
 * Enum of all permission nodes used in ConquestClans.
 *
 * This centralizes all permission identifiers for easier reference,
 * maintainability, and consistency across the project.
 *
 * Based on ConquestClans’s PermissionModels design.
 */
public enum PermissionModels {

    // ─────────────────────────────────────────────
    // 🎮 User Permissions
    // ─────────────────────────────────────────────
    USER_BASECOMMAND("conquestclans.user.basecommand"),
    USER_HELP("conquestclans.user.help"),
    USER_ALL("conquestclans.user.*"),

    // ─────────────────────────────────────────────
    // 🛠 Admin Permissions
    // ─────────────────────────────────────────────
    ADMIN_BASE("conquestclans.admin"),
    ADMIN_HELP("conquestclans.admin.help"),
    ADMIN_RELOAD("conquestclans.admin.reload"),
    ADMIN_ALL("conquestclans.admin.*");

    private final String node;

    PermissionModels(String node) {
        this.node = node;
    }

    /**
     * Returns the full permission string.
     *
     * @return The permission node (e.g. "conquestclans.user.help")
     */
    public String getNode() {
        return node;
    }

    /**
     * Returns the permission node as a string for direct use
     * in checks or logging.
     */
    @Override
    public String toString() {
        return node;
    }
}
