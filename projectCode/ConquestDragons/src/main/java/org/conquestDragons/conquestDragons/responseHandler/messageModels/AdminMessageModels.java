package org.conquestDragons.conquestDragons.responseHandler.messageModels;

/**
 * 👑 AdminMessageModels
 * Enum keys for referencing structured adminMessages.yml paths (ConquestDragons).
 *
 * Mirrors the current adminMessages.yml:
 *   messages.admin.help-usage
 *   messages.admin.help
 *   messages.admin.reload-success
 */
public enum AdminMessageModels {

    // 💡 ADMIN HELP USAGE (nudge)
    ADMIN_HELP_USAGE("messages.admin.help-usage"),

    // 🛠 ADMIN HELP (page)
    ADMIN_HELP("messages.admin.help"),

    // 🔄 ADMIN RELOAD SUCCESS
    RELOAD_SUCCESS("messages.admin.reload-success");

    private final String path;

    AdminMessageModels(String path) {
        this.path = path;
    }

    /**
     * Returns the config path inside adminMessages.yml.
     */
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return path;
    }
}
