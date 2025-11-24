package org.conquestDragons.conquestDragons.commandHandler.subcommandHandler;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.conquestDragons.conquestDragons.commandHandler.permissionHandler.PermissionManager;
import org.conquestDragons.conquestDragons.commandHandler.permissionHandler.PermissionModels;
import org.conquestDragons.conquestDragons.eventHandler.EventManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.eventHandler.EventStageKey;
import org.conquestDragons.conquestDragons.eventHandler.EventSequenceManager;
import org.conquestDragons.conquestDragons.responseHandler.MessageResponseManager;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.GenericMessageModels;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.UserMessageModels;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 🎮 UserCommandManager
 * Handles all non-admin /dragons subcommands with a switch-based dispatcher.
 */
public class UserCommandManager {

    private UserCommandManager() {}

    public static boolean handle(Player player, String subcommand, String[] args) {
        switch (subcommand.toLowerCase()) {
            case "help":
                return handleHelp(player, args);
            case "join":
                return handleJoin(player, args);
            case "leave":
                return handleLeave(player, args);
            case "spectate":
                return handleSpectate(player, args);

            default:
                MessageResponseManager.send(player, GenericMessageModels.UNKNOWN_COMMAND);
                return true;
        }
    }

    // --------------------
    // Handlers
    // --------------------

    private static boolean handleHelp(Player player, String[] args) {
        if (!PermissionManager.has(player, PermissionModels.USER_HELP)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) { /* default 1 */ }
        }

        MessageResponseManager.sendUserHelpPage(player, "messages.user.help", page);
        return true;
    }

    /**
     * /dragons spectate <eventName>
     * /dragons spectate leave
     *
     * Behaviour:
     *  - /dragons spectate leave
     *      • Checks USER_SPECTATE_LEAVE
     *      • If not currently spectating any event → "not spectating"
     *      • Otherwise:
     *          - teleport to completionSpawn (if defined)
     *          - set gamemode back to SURVIVAL if currently SPECTATOR
     *          - remove from spectators
     *          - send "spectate leave success"
     *
     *  - /dragons spectate <eventName>
     *      • Checks USER_SPECTATE
     *      • Requires explicit eventName (no auto-selection)
     *      • If player is participating in ANY event → use COMMAND_RESTRICTED_DURING_EVENT
     *      • If event is unknown, disabled, or not running → "spectate join not available"
     *      • If already spectating an event:
     *          - if same → "already spectating"
     *          - if different → "already spectating" (must leave first)
     *      • Otherwise:
     *          - add spectator
     *          - set gamemode to SPECTATOR
     *          - teleport to dragon-spawn (if configured, else fallback)
     *          - send "spectate join success"
     */
    private static boolean handleSpectate(Player player, String[] args) {
        // /dragons spectate leave
        if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
            if (!PermissionManager.has(player, PermissionModels.USER_SPECTATE_LEAVE)) {
                MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
                return true;
            }

            final UUID uuid = player.getUniqueId();

            // Find event this player is currently spectating
            EventModel spectatedEvent = EventManager.findEventBySpectator(uuid);
            if (spectatedEvent == null) {
                // Not spectating anything
                MessageResponseManager.send(player, UserMessageModels.SPECTATE_LEAVE_NOT_SPECTATING);
                return true;
            }

            // Teleport them out to completion spawn if configured
            Location completionSpawn = spectatedEvent.completionSpawn();
            if (completionSpawn != null) {
                player.teleport(completionSpawn);
            }

            // Ensure they are no longer in spectator mode.
            // (Matches EventSequenceManager.scheduleSpectatorCompletionTeleport behaviour)
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }

            // Remove from spectators
            spectatedEvent.removeSpectator(uuid);

            // Success message with {eventName}
            MessageResponseManager.send(
                    player,
                    UserMessageModels.SPECTATE_LEAVE_SUCCESS,
                    Map.of("eventName", spectatedEvent.id())
            );
            return true;
        }

        // /dragons spectate <eventName>
        if (!PermissionManager.has(player, PermissionModels.USER_SPECTATE)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        final UUID uuid = player.getUniqueId();

        // Require an explicit event name for spectating
        if (args.length < 2) {
            // Reuse join usage as a generic "specify event" hint
            MessageResponseManager.send(player, UserMessageModels.USER_JOIN_USAGE);
            return true;
        }

        String targetEventId = args[1];

        // Block spectate if they are currently a participant in ANY event
        EventModel participantEvent = EventManager.findEventByParticipant(uuid);
        if (participantEvent != null) {
            MessageResponseManager.send(
                    player,
                    UserMessageModels.COMMAND_RESTRICTED_DURING_EVENT,
                    Map.of(
                            "command", "/dragons spectate " + targetEventId,
                            "eventName", participantEvent.id()
                    )
            );
            return true;
        }

        // Lookup target event
        EventModel targetEvent = EventManager.getOrNull(targetEventId);
        if (targetEvent == null || !targetEvent.enabled() || !targetEvent.isRunning()) {
            // Unknown, disabled, or not currently running → not spectatable
            MessageResponseManager.send(
                    player,
                    UserMessageModels.SPECTATE_JOIN_NOT_AVAILABLE,
                    Map.of("eventName", targetEventId)
            );
            return true;
        }

        // Check if already spectating something
        EventModel existingSpectate = EventManager.findEventBySpectator(uuid);
        if (existingSpectate != null) {
            // If already spectating this exact event, say so
            MessageResponseManager.send(
                    player,
                    UserMessageModels.SPECTATE_JOIN_ALREADY_SPECTATING,
                    Map.of("eventName", existingSpectate.id())
            );
            return true;
        }

        // Actually add as spectator
        targetEvent.addSpectator(uuid);

        // Put them into spectator gamemode for the duration of spectating
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }

        // Teleport to dragon-spawn first; fall back to initialStageSpawn if needed
        Location spectateLocation = targetEvent.dragonSpawn();
        if (spectateLocation == null) {
            spectateLocation = targetEvent.initialStageSpawn();
        }
        if (spectateLocation != null) {
            player.teleport(spectateLocation);
        }

        // Success
        MessageResponseManager.send(
                player,
                UserMessageModels.SPECTATE_JOIN_SUCCESS,
                Map.of("eventName", targetEvent.id())
        );
        return true;
    }

    /**
     * /dragons join [eventName]
     *
     * Behaviour:
     *  - Checks permission.
     *  - If player is already in any event → send "already in event".
     *  - If eventName is omitted AND there is exactly 1 enabled event,
     *    auto-assume that event.
     *  - If 0 enabled events → usage hint.
     *  - If >1 enabled events and no arg → usage hint.
     *  - If eventName given but not found/disabled → "event hasn't started" with {eventName}.
     *  - If schedule says UPCOMING → "event hasn't started" with {eventName}.
     *  - If schedule says CLOSED → "join window closed" with {eventName}.
     *  - On success:
     *      * add participant
     *      * teleport to INITIAL stage spawn (if configured)
     *      * send "join success" with {eventName}.
     */
    private static boolean handleJoin(Player player, String[] args) {
        if (!PermissionManager.has(player, PermissionModels.USER_JOIN)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        final UUID uuid = player.getUniqueId();

        // 1) Check if player is already in ANY event
        EventModel currentEvent = EventManager.all().stream()
                .filter(event -> event.isParticipant(uuid))
                .findFirst()
                .orElse(null);

        if (currentEvent != null) {
            MessageResponseManager.send(
                    player,
                    UserMessageModels.USER_JOIN_ALREADY_IN_EVENT,
                    Map.of("eventName", currentEvent.id())
            );
            return true;
        }

        // 2) Resolve target event id
        String targetEventId;

        if (args.length >= 2) {
            // Explicit: /dragons join <eventName>
            targetEventId = args[1];
        } else {
            // No arg → try auto-select single enabled event
            List<EventModel> enabledEvents = EventManager.all().stream()
                    .filter(EventModel::enabled)
                    .toList();

            if (enabledEvents.isEmpty()) {
                // No enabled events at all → just show usage
                MessageResponseManager.send(player, UserMessageModels.USER_JOIN_USAGE);
                return true;
            }

            if (enabledEvents.size() > 1) {
                // More than one → require explicit event name
                MessageResponseManager.send(player, UserMessageModels.USER_JOIN_USAGE);
                return true;
            }

            // Exactly one enabled event → auto-pick
            targetEventId = enabledEvents.get(0).id();
        }

        // 3) Lookup event by id
        EventModel targetEvent = EventManager.getOrNull(targetEventId);
        if (targetEvent == null || !targetEvent.enabled()) {
            // Unknown / disabled event → treat as "not started / unavailable"
            MessageResponseManager.send(
                    player,
                    UserMessageModels.USER_JOIN_NOT_STARTED,
                    Map.of("eventName", targetEventId)
            );
            return true;
        }

        // 4) Check schedule / join window state
        EventSequenceManager seq = EventSequenceManager.getInstance();
        if (seq != null) {
            EventSequenceManager.JoinWindowState state = seq.queryJoinWindowState(targetEvent);

            switch (state) {
                case UPCOMING -> {
                    // Join window has not opened yet
                    MessageResponseManager.send(
                            player,
                            UserMessageModels.USER_JOIN_NOT_STARTED,
                            Map.of("eventName", targetEvent.id())
                    );
                    return true;
                }
                case CLOSED -> {
                    // Join window is over for this run
                    MessageResponseManager.send(
                            player,
                            UserMessageModels.USER_JOIN_WINDOW_CLOSED,
                            Map.of("eventName", targetEvent.id())
                    );
                    return true;
                }
                case UNSCHEDULED -> {
                    // No runtime schedule info; safest is to block and say "not started"
                    MessageResponseManager.send(
                            player,
                            UserMessageModels.USER_JOIN_NOT_STARTED,
                            Map.of("eventName", targetEvent.id())
                    );
                    return true;
                }
                case OPEN -> {
                    // Fall through and allow join
                }
            }
        }
        // If seq == null, we skip gating and behave like "always open" (or you can hard-block here if you prefer)

        // 5) Double-check not participant (race-safety)
        if (targetEvent.isParticipant(uuid)) {
            MessageResponseManager.send(
                    player,
                    UserMessageModels.USER_JOIN_ALREADY_IN_EVENT,
                    Map.of("eventName", targetEvent.id())
            );
            return true;
        }

        // 6) Actually join the event (uses UUID)
        targetEvent.addParticipant(uuid);

        // 7) Teleport to INITIAL stage spawn (if defined)
        //    Assumes EventModel#initialStageSpawn() exists.
        Location initialSpawn = targetEvent.initialStageSpawn();
        if (initialSpawn != null) {
            player.teleport(initialSpawn);
        } else {
            // If you want, you can log a warning here instead:
            // ConquestDragons.getInstance().getLogger().warning(
            //         "No initialStageSpawn configured for event '" + targetEvent.id() + "'."
            // );
        }

        // 8) Success with {eventName}
        MessageResponseManager.send(
                player,
                UserMessageModels.USER_JOIN_SUCCESS,
                Map.of("eventName", targetEvent.id())
        );
        return true;
    }

    /**
     * /dragons leave
     *
     * Behaviour:
     *  - Checks permission.
     *  - Finds the event the player is currently participating in.
     *  - If none → "not in event".
     *  - If event disallows leaving at this time → "cannot leave during event".
     *  - Otherwise:
     *      * teleport to completion spawn (if configured)
     *      * remove from participants
     *      * send "leave success" with {eventName}.
     */
    private static boolean handleLeave(Player player, String[] args) {
        if (!PermissionManager.has(player, PermissionModels.USER_LEAVE)) {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
            return true;
        }

        final UUID uuid = player.getUniqueId();

        // 1) Find the event this player is currently part of
        EventModel currentEvent = EventManager.all().stream()
                .filter(event -> event.isParticipant(uuid))
                .findFirst()
                .orElse(null);

        if (currentEvent == null) {
            // Player isn't in any event
            MessageResponseManager.send(player, UserMessageModels.USER_LEAVE_NOT_IN_EVENT);
            return true;
        }

        // 2) Check if leaving is currently allowed (hook for stage/lock logic)
        if (!canLeaveNow(currentEvent, uuid)) {
            MessageResponseManager.send(
                    player,
                    UserMessageModels.USER_LEAVE_BLOCKED_DURING_EVENT,
                    Map.of("stageName", String.valueOf(currentEvent.currentStageKey()))
            );
            return true;
        }

        // 3) Teleport to completion spawn (if configured)
        //    Assumes EventModel#completionSpawn() returns a Location or null.
        Location completionSpawn = currentEvent.completionSpawn();
        if (completionSpawn != null) {
            player.teleport(completionSpawn);
        } else {
            // Optional: log if missing
            // ConquestDragons.getInstance().getLogger().warning(
            //         "No completionSpawn configured for event '" + currentEvent.id() + "'."
            // );
        }

        // 4) Remove the player from the event
        currentEvent.removeParticipant(uuid);

        // 5) Success with {eventName}
        MessageResponseManager.send(
                player,
                UserMessageModels.USER_LEAVE_SUCCESS,
                Map.of("eventName", currentEvent.id())
        );
        return true;
    }

    /**
     * Players may ONLY leave when the event is in the LOBBY stage.
     *
     * Any other stage is treated as "event ongoing" and will block leaving.
     * If for some reason the stage is null (misconfigured runtime), we
     * allow leaving to avoid trapping players.
     */
    private static boolean canLeaveNow(EventModel event, UUID playerId) {
        EventStageKey stageKey = event.currentStageKey();

        // Fail-open if we somehow don't know the stage
        if (stageKey == null) {
            return true;
        }

        // Only LOBBY is safe to leave
        return stageKey == EventStageKey.LOBBY;
    }

    // --------------------
    // Utilities (used by CommandManager)
    // --------------------

    public static boolean sendUsageHint(Player player) {
        if (PermissionManager.has(player, PermissionModels.USER_HELP)) {
            MessageResponseManager.send(player, UserMessageModels.USER_HELP_USAGE);
        } else {
            MessageResponseManager.send(player, GenericMessageModels.NO_PERMISSION);
        }
        return true;
    }
}
