package org.conquestDragons.conquestDragons.listenerHandler;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.conquestDragons.conquestDragons.eventHandler.EventManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.eventHandler.EventStageKey;

import java.util.UUID;

/**
 * 🧱 EventRegionManager
 *
 * Enforces per-stage player regions for active events.
 * If a participant leaves their current stage's region, they are snapped
 * back to that stage's spawn. If the stage has no explicit StageArea,
 * falls back to the global dragonRegion/dragonSpawn.
 *
 * Example YAML (per stage):
 *
 *   LOBBY:
 *     region:
 *       world: "world"
 *       corner-a:
 *         x: 90.0
 *         y: 64.0
 *         z: 90.0
 *       corner-b:
 *         x: 160.0
 *         y: 80.0
 *         z: 160.0
 *     spawn:
 *       world: "world"
 *       x: 125.5
 *       y: 66.0
 *       z: 125.5
 *       yaw: 0.0
 *       pitch: 0.0
 */
public final class EventRegionManager implements Listener {

    /**
     * Called whenever a player moves. If they are participating in an event
     * and leave their current stage's region, snap them back to that stage's
     * spawn (or dragonSpawn as a fallback).
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Ignore tiny movements (head rotation only)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        // Find the event this player is currently part of
        EventModel currentEvent = EventManager.all().stream()
                .filter(e -> e.isParticipant(uuid))
                .findFirst()
                .orElse(null);

        if (currentEvent == null) {
            return; // player is not in any event
        }

        // If you only want enforcement while running, uncomment:
        // if (!currentEvent.isRunning()) {
        //     return;
        // }

        EventStageKey stageKey = currentEvent.currentStageKey();

        // Determine which region + spawn to enforce:
        //  - Prefer the current stage's StageArea (if configured)
        //  - Otherwise fall back to global dragonRegion/dragonSpawn
        EventModel.StageArea stageArea =
                (stageKey != null) ? currentEvent.stageAreaOrNull(stageKey) : null;

        EventModel.EventRegion region;
        Location spawn;

        if (stageArea != null) {
            region = stageArea.region();
            spawn = stageArea.spawn();
        } else {
            region = currentEvent.dragonRegion();
            spawn = currentEvent.dragonSpawn();
        }

        // Already inside region → nothing to do
        if (region.contains(to)) {
            return;
        }

        // Outside region → snap back to spawn.
        // Using setTo() avoids firing a second move event.
        event.setTo(spawn);
    }
}
