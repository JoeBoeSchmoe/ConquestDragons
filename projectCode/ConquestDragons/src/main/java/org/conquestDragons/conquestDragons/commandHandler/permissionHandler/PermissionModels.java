package org.conquestDragons.conquestDragons.commandHandler.permissionHandler;

/**
 * 🔐 PermissionModels
 * Enum of all permission nodes used in ConquestDragons.
 *
 * Centralized permission definitions for consistent use across the project.
 */
public enum PermissionModels {

    // ─────────────────────────────────────────────
    // 🎮 User Permissions
    // ─────────────────────────────────────────────
    USER_BASECOMMAND("conquestdragons.user.basecommand"),
    USER_HELP("conquestdragons.user.help"),

    USER_JOIN("conquestdragons.user.join"),
    USER_LEAVE("conquestdragons.user.leave"),

    USER_SPECTATE("conquestdragons.user.spectate"),
    USER_SPECTATE_LEAVE("conquestdragons.user.spectate.leave"),

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

    /** Returns the full permission node string. */
    public String getNode() {
        return node;
    }

    @Override
    public String toString() {
        return node;
    }
}
