package org.conquestDragons.conquestDragons.listenerHandler;

import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.conquestDragons.conquestDragons.dragonHandler.DragonBossbarManager;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;

import java.util.Objects;
import java.util.UUID;

/**
 * DragonDamageListener
 *
 * Routes all PLAYER-SOURCED damage dealt to tracked event dragons
 * into the owning EventModel's damage tracking.
 *
 * Responsibilities:
 *  - Listen to EntityDamageByEntityEvent.
 *  - Filter for EnderDragon targets that belong to a running EventModel.
 *  - Resolve the real attacking player (melee, bow/projectile, tamed pets).
 *  - Call event.recordDamage(playerUUID, amount).
 *
 *  All aggregation & leaderboard logic lives in EventModel:
 *      event.damageSnapshot()
 *      event.topDamage(int maxEntries)
 */
public final class DragonDamageListener implements Listener {

    private static DragonDamageListener INSTANCE;

    public DragonDamageListener() {
        // Instance is set when the plugin constructs this via registerListeners(...)
        INSTANCE = this;
    }

    public static DragonDamageListener getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------------
    // Event handler
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDamaged(EntityDamageByEntityEvent event) {
        // Only care about EnderDragon victims
        Entity victim = event.getEntity();
        if (!(victim instanceof EnderDragon dragon)) {
            return;
        }

        // Resolve the true attacking player (melee, projectile, pet-owner, etc.)
        Player damager = resolvePlayerDamager(event.getDamager());
        if (damager == null) {
            return; // not player-sourced damage
        }

        DragonBossbarManager bossbarManager = DragonBossbarManager.getInstance();
        if (bossbarManager == null) {
            return;
        }

        // Dragon must be part of a tracked event
        EventModel dragonEvent = bossbarManager.findEventForDragon(dragon);
        if (dragonEvent == null) {
            return;
        }

        // Only track while the event is actually running
        if (!dragonEvent.isRunning()) {
            return;
        }

        UUID playerId = damager.getUniqueId();

        // Optional: only count damage from registered participants
        if (!dragonEvent.isParticipant(playerId)) {
            return;
        }

        double damage = event.getFinalDamage();
        if (damage <= 0.0D) {
            return;
        }

        // Delegate aggregation to EventModel's runtime damage map
        dragonEvent.recordDamage(playerId, damage);
    }

    // ---------------------------------------------------------------------
    // Damage resolution helpers
    // ---------------------------------------------------------------------

    /**
     * Best-effort resolution of a player "source" for an attacking entity.
     *
     * Supports:
     *  - Direct player melee
     *  - Player-fired projectiles (arrows, tridents, etc.)
     *  - Tamed entities (wolves, etc.) that have a Player owner
     */
    private Player resolvePlayerDamager(Entity attacker) {
        if (attacker instanceof Player player) {
            return player;
        }

        if (attacker instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player playerShooter) {
                return playerShooter;
            }
        }

        if (attacker instanceof Tameable tameable) {
            if (tameable.getOwner() instanceof Player owner) {
                return owner;
            }
        }

        // Extend here for other special cases if ever needed
        return null;
    }
}
