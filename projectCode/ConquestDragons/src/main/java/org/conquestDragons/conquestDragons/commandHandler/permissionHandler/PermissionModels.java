package org.conquestDragons.conquestDragons.commandHandler.permissionHandler;

/**
 * 🔐 PermissionModels
 * Enum of all permission nodes used in ConquestDragons.
 *
 * This centralizes all permission identifiers for easier reference,
 * maintainability, and consistency across the project.
 *
 * Based on ConquestDragons’s PermissionModels design.
 */
public enum PermissionModels {

    // ─────────────────────────────────────────────
    // 🎮 User Permissions
    // ─────────────────────────────────────────────
    USER_BASECOMMAND("conquestdragons.user.basecommand"),
    USER_HELP("conquestdragons.user.help"),
    USER_ALL("conquestdragons.user.*"),

    // ─────────────────────────────────────────────
    // 🛠 Admin Permissions
    // ─────────────────────────────────────────────
    ADMIN_BASE("conquestdragons.admin"),
    ADMIN_HELP("conquestdragons.admin.help"),
    ADMIN_RELOAD("conquestdragons.admin.reload"),
    ADMIN_ALL("conquestdragons.admin.*");

    private final String node;

    PermissionModels(String node) {
        this.node = node;
    }

    /**
     * Returns the full permission string.
     *
     * @return The permission node (e.g. "conquestdragons.user.help")
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
