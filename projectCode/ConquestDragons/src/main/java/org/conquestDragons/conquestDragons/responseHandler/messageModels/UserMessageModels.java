package org.conquestDragons.conquestDragons.responseHandler.messageModels;

/**
 * 🎮 UserMessageModels
 * Enum keys for referencing structured userMessages.yml paths (ConquestDragons).
 *
 * Mirrors userMessages.yml under:
 *   messages.user.*
 */
public enum UserMessageModels {

    // =====================================================
    // 💡 GENERIC USER HELP
    // =====================================================

    USER_HELP_USAGE("messages.user.help-usage"),
    USER_HELP("messages.user.help"),

    // =====================================================
    // 🐉 JOIN COMMAND MESSAGES
    // =====================================================

    USER_JOIN_USAGE("messages.user.join-usage"),
    USER_JOIN_SUCCESS("messages.user.join-success"),
    USER_JOIN_ALREADY_IN_EVENT("messages.user.join-already-in-event"),
    USER_JOIN_NOT_STARTED("messages.user.join-not-started"),
    USER_JOIN_WINDOW_CLOSED("messages.user.join-window-closed"),

    // =====================================================
    // 🐉 LEAVE COMMAND MESSAGES
    // =====================================================

    USER_LEAVE_SUCCESS("messages.user.leave-success"),
    USER_LEAVE_NOT_IN_EVENT("messages.user.leave-not-in-event"),
    USER_LEAVE_BLOCKED_DURING_EVENT("messages.user.leave-blocked-during-event"),


    // =====================================================
    // 👁 SPECTATE JOIN MESSAGES
    // =====================================================

    SPECTATE_JOIN_SUCCESS("messages.user.spectate-join-success"),
    SPECTATE_JOIN_ALREADY_SPECTATING("messages.user.spectate-join-already-spectating"),
    SPECTATE_JOIN_NOT_AVAILABLE("messages.user.spectate-join-not-available"),

    // =====================================================
    // 👁 SPECTATE LEAVE MESSAGES
    // =====================================================

    SPECTATE_LEAVE_SUCCESS("messages.user.spectate-leave-success"),
    SPECTATE_LEAVE_NOT_SPECTATING("messages.user.spectate-leave-not-spectating"),

    // =====================================================
    // ❌ COMMAND RESTRICTION DURING EVENT
    // =====================================================

    COMMAND_RESTRICTED_DURING_EVENT("messages.user.command-restricted-during-event"),

    // =====================================================
    // 🐉 EVENT LIFECYCLE
    // =====================================================

    EVENT_COUNTDOWN("messages.user.countdown"),

    EVENT_START("messages.user.EventStart"),
    EVENT_START_REMINDER("messages.user.EventStartReminder"),
    EVENT_STARTED("messages.user.EventStarted"),

    // =====================================================
    // 🏟 LOBBY STAGE (start → timed → end)
    // =====================================================

    LOBBY_STAGE_START("messages.user.lobby-stage.start"),
    LOBBY_STAGE_TIMED("messages.user.lobby-stage.timed"),
    LOBBY_STAGE_END("messages.user.lobby-stage.end"),

    EVENT_WIN("messages.user.event-win"),
    EVENT_DEFEAT("messages.user.event-defeat"),

    // =====================================================
    // ⚔ INITIAL STAGE (start → timed → end)
    // =====================================================

    INITIAL_STAGE_START("messages.user.initial-stage.start"),
    INITIAL_STAGE_TIMED("messages.user.initial-stage.timed"),
    INITIAL_STAGE_END("messages.user.initial-stage.end"),

    // =====================================================
    // 🟥 IN BELLY STAGE (start → timed → end)
    // =====================================================

    IN_BELLY_STAGE_START("messages.user.in-belly-stage.start"),
    IN_BELLY_STAGE_TIMED("messages.user.in-belly-stage.timed"),
    IN_BELLY_STAGE_END("messages.user.in-belly-stage.end"),

    // =====================================================
    // 🟦 POST BELLY STAGE (start → timed → end)
    // =====================================================

    POST_BELLY_STAGE_START("messages.user.post-belly-stage.start"),
    POST_BELLY_STAGE_TIMED("messages.user.post-belly-stage.timed"),
    POST_BELLY_STAGE_END("messages.user.post-belly-stage.end"),

    // =====================================================
    // 🟪 FINAL STAGE (start → timed → end)
    // =====================================================

    FINAL_STAGE_START("messages.user.final-stage.start"),
    FINAL_STAGE_TIMED("messages.user.final-stage.timed"),
    FINAL_STAGE_END("messages.user.final-stage.end");

    // =====================================================
    // INTERNAL FIELDS
    // =====================================================

    private final String path;

    UserMessageModels(String path) {
        this.path = path;
    }

    /** Returns the config path used inside userMessages.yml */
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return path;
    }
}
