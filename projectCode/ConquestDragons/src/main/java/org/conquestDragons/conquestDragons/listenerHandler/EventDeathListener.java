package org.conquestDragons.conquestDragons.eventHandler;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.conquestDragons.conquestDragons.ConquestDragons;

import java.util.UUID;

public final class EventDeathListener implements Listener {

    private static EventDeathListener INSTANCE;

    private final ConquestDragons plugin;

    public EventDeathListener() {
        this.plugin = ConquestDragons.getInstance();
        INSTANCE = this;
    }

    public static EventDeathListener getInstance() {
        return INSTANCE;
    }

    /**
     * Intercepts lethal damage on players who are currently participants in an event.
     *
     * Flow:
     *  - If damage would kill the player AND they are an active participant in an event:
     *      • Cancel the damage (prevent real death)
     *      • Move them to the event's spectator list
     *      • Handle inventory according to event.keepInventory()
     *      • Teleport to dragon-spawn (spectator perch)
     *      • Put them into SPECTATOR gamemode
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageLethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // Would this hit actually kill the player?
        double finalHealth = player.getHealth() - event.getFinalDamage();
        if (finalHealth > 0.0) {
            return; // not lethal
        }

        UUID uuid = player.getUniqueId();

        // Only care if this player is currently a participant in an event
        EventModel dragonEvent = EventManager.findEventByParticipant(uuid);
        if (dragonEvent == null) {
            return;
        }

        // Optional: only apply this during "combat" stages, not LOBBY
        if (!dragonEvent.isRunning()) {
            return;
        }

        // Handle fake death
        event.setCancelled(true);

        // Keep them alive with a sliver of HP before we move them.
        player.setHealth(Math.max(1.0, Math.min(player.getMaxHealth(), 1.0)));

        // Move participant -> spectator
        dragonEvent.removeParticipant(uuid);
        dragonEvent.addSpectator(uuid);

        // Handle inventory drop depending on event.keepInventory()
        boolean keepInventory = dragonEvent.keepInventory();

        if (!keepInventory) {
            dropAndClearInventory(player);
        }
        // keepInventory == true → they keep their items.

        // Teleport them to dragon-spawn (spectator perch).
        // If dragon-spawn is not configured, fall back to completion-spawn.
        Location target = null;
        try {
            target = dragonEvent.dragonSpawn();
        } catch (Exception ignored) {
        }
        if (target == null) {
            try {
                target = dragonEvent.completionSpawn();
            } catch (Exception ignored) {
            }
        }

        if (target != null) {
            safeTeleport(player, target);
        } else {
            plugin.getLogger().warning("[ConquestDragons] No dragon-spawn or completion-spawn configured for event '"
                    + dragonEvent.id() + "'; spectator teleport skipped.");
        }

        // Put them into spectator mode so they can't keep fighting
        player.setGameMode(GameMode.SPECTATOR);

        // Optional: hook in a message model later if you want
        // MessageResponseManager.send(
        //         player,
        //         UserMessageModels.EVENT_FAKE_DEATH_TO_SPECTATOR,
        //         Map.of("eventName", dragonEvent.id())
        // );
    }

    private void dropAndClearInventory(Player player) {
        World world = player.getWorld();
        Location loc = player.getLocation();

        // Main contents
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            world.dropItemNaturally(loc, stack.clone());
        }

        // Armor contents
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            world.dropItemNaturally(loc, stack.clone());
        }

        // Off-hand
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            world.dropItemNaturally(loc, offhand.clone());
        }

        // Now clear everything so they don't still have items while spectating
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
    }

    private void safeTeleport(Player player, Location target) {
        if (target == null) return;
        if (target.getWorld() == null) {
            plugin.getLogger().warning("[ConquestDragons] Tried to teleport " + player.getName()
                    + " to a location with null world.");
            return;
        }

        player.teleport(target);
    }
}
