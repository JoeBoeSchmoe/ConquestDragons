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
 *
 * If a player (participant OR spectator) leaves their current stage's region, they
 * are snapped back to that stage's spawn. If the stage has no explicit StageArea,
 * falls back to the global dragonRegion/dragonSpawn.
 *
 * Region enforcement is only active while:
 *   - the join window is open (LOBBY) OR
 *   - the event is running combat stages (isRunning() == true).
 *
 * After the event ends and EventSequenceManager marks:
 *   - event.setRunning(false);
 *   - event.setJoinWindowOpen(false);
 * this listener stops enforcing bounds so players can freely leave.
 */
public final class EventRegionManager implements Listener {

    /**
     * Called whenever a player moves. If they are in an event (participant or
     * spectator) and leave that event's current stage region, snap them back
     * to that stage's spawn (or dragonSpawn as a fallback).
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

        // Find any event this player is involved in:
        //  - as a participant OR
        //  - as a spectator
        EventModel currentEvent = EventManager.all().stream()
                .filter(e -> e.isParticipant(uuid) || e.isSpectator(uuid))
                .findFirst()
                .orElse(null);

        if (currentEvent == null) {
            return; // player is not in any event context
        }

        // ✅ Only enforce region while event is "active":
        //    - during join window (LOBBY) OR while running combat stages.
        if (!currentEvent.isRunning() && !currentEvent.isJoinWindowOpen()) {
            return;
        }

        EventStageKey stageKey = currentEvent.currentStageKey();
        if (stageKey == null) {
            return;
        }

        // Determine which region + spawn to enforce:
        //  - Prefer the current stage's StageArea (if configured)
        //  - Otherwise fall back to global dragonRegion/dragonSpawn
        EventModel.StageArea stageArea = currentEvent.stageAreaOrNull(stageKey);

        EventModel.EventRegion region;
        Location spawn;

        if (stageArea != null) {
            region = stageArea.region();
            spawn = stageArea.spawn();
        } else {
            region = currentEvent.dragonRegion();
            spawn = currentEvent.dragonSpawn();
        }

        // If we have no region or no spawn, don't enforce anything
        if (region == null || spawn == null) {
            return;
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
