package org.conquestDragons.conquestDragons.responseHandler.messageModels;

/**
 * 💬 GenericMessageModels
 * Enum keys for referencing structured genericMessages.yml paths (ConquestClans).
 *
 * Mirrors the current genericMessages.yml:
 *   messages.not-player
 *   messages.unknown-command
 *   messages.command-on-cooldown
 *   messages.gui-button-cooldown
 *   messages.interaction-cooldown
 *   messages.no-permission
 */
public enum GenericMessageModels {

    // ⛔ NOT A PLAYER (Console-only)
    NOT_PLAYER("messages.not-player"),

    // 🌀 UNKNOWN USER COMMAND
    UNKNOWN_COMMAND("messages.unknown-command"),

    // ⏱️ COMMAND COOLDOWN
    COMMAND_ON_COOLDOWN("messages.command-on-cooldown"),

    // 🖱️ GUI BUTTON COOLDOWN
    GUI_BUTTON_COOLDOWN("messages.gui-button-cooldown"),

    // 🤝 INTERACTION COOLDOWN
    INTERACTION_COOLDOWN("messages.interaction-cooldown"),

    // 🚫 MISSING PERMISSION
    NO_PERMISSION("messages.no-permission");

    private final String path;

    GenericMessageModels(String path) {
        this.path = path;
    }

    /**
     * Returns the config path inside genericMessages.yml.
     */
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return path;
    }
}
