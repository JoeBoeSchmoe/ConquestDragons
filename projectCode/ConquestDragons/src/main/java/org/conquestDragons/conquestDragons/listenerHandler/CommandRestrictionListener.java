package org.conquestDragons.conquestDragons.listenerHandler;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.ConfigFile;
import org.conquestDragons.conquestDragons.eventHandler.EventManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.responseHandler.MessageResponseManager;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.UserMessageModels;

import java.util.*;
import java.util.stream.Collectors;

public final class CommandRestrictionListener implements Listener {

    private final boolean worldWhitelistEnabled;
    private final Set<String> allowedWorlds;

    private final boolean whitelistEnabled;
    private final Set<String> globalWhitelist;
    private final Set<String> participantWhitelist;
    private final Set<String> spectatorWhitelist;

    public CommandRestrictionListener() {
        FileConfiguration config = ConfigFile.getConfig();

        // command-restrictions.*
        this.worldWhitelistEnabled = config.getBoolean("command-restrictions.whitelist-worlds", false);
        this.allowedWorlds = new HashSet<>(
                config.getStringList("command-restrictions.allowed-worlds")
                        .stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet())
        );

        this.whitelistEnabled = config.getBoolean("command-restrictions.whitelist-enabled", true);

        this.globalWhitelist = normalizeCommands(
                config.getStringList("command-restrictions.global-whitelist")
        );

        // 🔧 match YAML: command-restrictions.participant-whitelist
        this.participantWhitelist = normalizeCommands(
                config.getStringList("command-restrictions.participant-whitelist")
        );

        this.spectatorWhitelist = normalizeCommands(
                config.getStringList("command-restrictions.spectator-whitelist")
        );
    }

    private Set<String> normalizeCommands(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String trimmed = s.trim();
            if (!trimmed.startsWith("/")) {
                trimmed = "/" + trimmed;
            }
            out.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Helper to decide if a command should be allowed given a whitelist.
     *
     * Supports:
     *  - Exact base command match ("/dragons")
     *  - Exact full command match ("/dragons spectate leave")
     *  - Prefix match, e.g. whitelist "/dragons" → allows "/dragons spectate leave"
     */
    private boolean isWhitelisted(Set<String> whitelist,
                                  String baseCommand,
                                  String fullMessageLower) {
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }

        // Exact base-command match
        if (whitelist.contains(baseCommand)) {
            return true;
        }

        // Exact full-line match (e.g. "/dragons spectate leave")
        if (whitelist.contains(fullMessageLower)) {
            return true;
        }

        // Prefix match: "/dragons" in whitelist → allows "/dragons spectate leave"
        for (String allowed : whitelist) {
            if (fullMessageLower.equals(allowed)) {
                return true;
            }
            if (fullMessageLower.startsWith(allowed + " ")) {
                return true;
            }
        }

        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!whitelistEnabled) {
            return; // system turned off
        }

        Player player = event.getPlayer();

        // World restriction (optional)
        if (worldWhitelistEnabled) {
            String worldName = player.getWorld().getName().toLowerCase(Locale.ROOT);
            if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(worldName)) {
                return; // outside managed worlds, don't touch commands
            }
        }

        String msg = event.getMessage();          // e.g. "/dragons spectate leave"
        if (msg == null || msg.isBlank()) return;

        String fullLower = msg.trim().toLowerCase(Locale.ROOT);

        String[] split = fullLower.split("\\s+");
        if (split.length == 0) return;

        // Base command (with leading slash, normalized) e.g. "/dragons"
        String baseCommand = split[0]; // already lowercased above

        // Global whitelist – always allowed
        if (isWhitelisted(globalWhitelist, baseCommand, fullLower)) {
            return;
        }

        // Check if player is in an active event (participant or spectator)
        EventContext context = resolveEventContext(player);
        if (context == null) {
            return; // no event context → don't restrict
        }

        // Decide which role-specific whitelist to use
        Set<String> roleWhitelist;
        if (context.participant()) {
            roleWhitelist = participantWhitelist;
        } else if (context.spectator()) {
            roleWhitelist = spectatorWhitelist;
        } else {
            roleWhitelist = Collections.emptySet();
        }

        // If the role whitelist allows this command, let it pass
        if (isWhitelisted(roleWhitelist, baseCommand, fullLower)) {
            return;
        }

        // Otherwise block and notify
        event.setCancelled(true);

        String eventName = context.event().displayName(); // or event().id()

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("command", baseCommand);
        placeholders.put("eventName", eventName);

        MessageResponseManager.send(
                player,
                UserMessageModels.COMMAND_RESTRICTED_DURING_EVENT,
                placeholders
        );
    }

    private EventContext resolveEventContext(Player player) {
        UUID uuid = player.getUniqueId();

        EventModel participantEvent = EventManager.findEventByParticipant(uuid);
        if (participantEvent != null) {
            return new EventContext(participantEvent, true, false);
        }

        EventModel spectatorEvent = EventManager.findEventBySpectator(uuid);
        if (spectatorEvent != null) {
            return new EventContext(spectatorEvent, false, true);
        }

        return null;
    }

    // simple record-style holder
    private record EventContext(EventModel event, boolean participant, boolean spectator) {}
}
