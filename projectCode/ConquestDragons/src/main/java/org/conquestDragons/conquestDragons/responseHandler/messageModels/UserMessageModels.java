package org.conquestDragons.conquestDragons.responseHandler.messageModels;

/**
 * 🎮 UserMessageModels
 * Enum keys for referencing structured userMessages.yml paths (ConquestDragons).
 *
 * Mirrors userMessages.yml under:
 *   messages.user.*
 *
 * Naming format:
 *   CATEGORY_STAGE_TYPE
 *   e.g., EVENT_START, INITIAL_STAGE_TIMED, FINAL_STAGE_END
 */
public enum UserMessageModels {

    // =====================================================
    // 💡 GENERIC USER HELP
    // =====================================================

    USER_HELP_USAGE("messages.user.help-usage"),
    USER_HELP("messages.user.help"),

    // =====================================================
    // 🐉 EVENT LIFECYCLE
    // =====================================================

    EVENT_COUNTDOWN("messages.user.countdown"),

    EVENT_START("messages.user.EventStart"),
    EVENT_START_REMINDER("messages.user.EventStartReminder"),
    EVENT_STARTED("user.EventStarted"),

    // =====================================================
    // 🏟 LOBBY STAGE (start → timed → end)
    // =====================================================

    LOBBY_STAGE_START("messages.user.lobby-stage.start"),
    LOBBY_STAGE_TIMED("messages.user.lobby-stage.timed"),
    LOBBY_STAGE_END("messages.user.lobby-stage.end"),

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
