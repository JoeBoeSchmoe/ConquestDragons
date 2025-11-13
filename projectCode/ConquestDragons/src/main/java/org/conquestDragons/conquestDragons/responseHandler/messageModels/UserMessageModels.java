package org.conquestDragons.conquestDragons.responseHandler.messageModels;

/**
 * 🎮 UserMessageModels
 * Enum keys for referencing structured userMessages.yml paths (ConquestClans).
 *
 * Mirrors the current userMessages.yml:
 *   messages.user.help-usage
 *   messages.user.help
 */
public enum UserMessageModels {

    // 💡 USER HELP USAGE (nudge)
    USER_HELP_USAGE("messages.user.help-usage"),

    // 📘 USER HELP (page)
    USER_HELP("messages.user.help");

    private final String path;

    UserMessageModels(String path) {
        this.path = path;
    }

    /**
     * Returns the config path inside userMessages.yml.
     */
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return path;
    }
}
