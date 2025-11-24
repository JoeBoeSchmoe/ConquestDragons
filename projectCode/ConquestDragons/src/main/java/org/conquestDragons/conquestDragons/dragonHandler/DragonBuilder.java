package org.conquestDragons.conquestDragons.dragonHandler;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonGlowColorHealthKey;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Builder for spawning configured EnderDragon instances from a DragonModel.
 *
 * Responsibilities:
 *  - Spawn an EnderDragon with:
 *      - Max health from DragonModel.maxHealth() (with safety + engine fallback).
 *      - Glow enabled and glowProfileKey stored in PDC.
 *      - Bossbar profile + bossbar name stored in PDC.
 *      - All DragonDifficultyModel knobs stored in PDC.
 *  - NOT creating any vanilla End dragon "fog" / boss battle.
 *    (Bossbars will be handled separately based on regions.)
 *
 *  NEW:
 *   - Applies a small random horizontal offset around the configured spawnLocation
 *     so multiple dragons are not stacked on the exact same block.
 *   - Forces the dragon into a flying phase immediately so it starts moving
 *     instead of hovering idle until damaged.
 */
public final class DragonBuilder {

    /**
     * Paper 1.21.x hard cap for health values.
     * If you try to set health above this, you'll get:
     *  "Health value (X) must be between 0 and 1024.0"
     */
    private static final double ENGINE_MAX_HEALTH = 1024.0;

    /**
     * Max horizontal offset (in blocks) applied randomly on X/Z
     * around the configured spawnLocation.
     */
    private static final double RANDOM_SPAWN_OFFSET_BLOCKS = 4.0;

    private final ConquestDragons plugin;

    private DragonModel model;
    private Location spawnLocation;

    private DragonBuilder(ConquestDragons plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Entry point for creating a new builder.
     */
    public static DragonBuilder create(ConquestDragons plugin) {
        return new DragonBuilder(plugin);
    }

    /**
     * Attach the DragonModel that defines health, difficulty, glow profile, etc.
     */
    public DragonBuilder model(DragonModel model) {
        this.model = Objects.requireNonNull(model, "model");
        return this;
    }

    /**
     * Set the spawn location for this dragon.
     */
    public DragonBuilder spawnAt(Location location) {
        this.spawnLocation = Objects.requireNonNull(location, "spawnLocation");
        return this;
    }

    /**
     * Spawn the EnderDragon in the world with:
     *  - Max health from DragonModel (with safety fallback + engine cap).
     *  - Glow enabled and glowProfileKey stored in PDC.
     *  - Bossbar profile + bossbar name stored in PDC.
     *  - All difficulty tuning knobs stored in PDC.
     *  - No vanilla End dragon boss-fog or boss battle.
     *
     *  NEW:
     *   - Applies a small random horizontal offset around spawnLocation.
     *   - Immediately sets the dragon phase to CIRCLING so it starts moving.
     *
     * @return the spawned EnderDragon instance
     */
    public EnderDragon spawn() {
        if (model == null) {
            throw new IllegalStateException("DragonModel must be set before calling spawn()");
        }
        if (spawnLocation == null) {
            throw new IllegalStateException("spawnLocation must be set before calling spawn()");
        }

        // ---------------------------------------------------
        // Apply a small random horizontal offset around spawnLocation
        // ---------------------------------------------------
        Location finalSpawnLocation = applyRandomHorizontalOffset(spawnLocation);
        World world = finalSpawnLocation.getWorld();
        if (world == null) {
            throw new IllegalStateException("spawnLocation has no world attached");
        }

        Logger log = plugin.getLogger();

        // ---------------------------------------------------
        // Basic debug: what does the model actually say?
        // ---------------------------------------------------
        double rawMaxHealth = model.maxHealth();
        DragonGlowColorHealthKey rawGlowProfile    = model.glowProfileKey();
        DragonGlowColorHealthKey rawBossbarProfile = model.bossbarProfileKey();

        // Safety: ensure we never end up with <= 0 HP,
        // even if the model loader / YAML is wrong.
        double defendedMax = rawMaxHealth;
        if (defendedMax <= 0.0) {
            // Vanilla dragon default is 200.0 – this is a sane fallback.
            defendedMax = 200.0;
//            log.warning("[ConquestDragons] DragonModel '" + model.configId()
//                    + "' reported invalid maxHealth=" + rawMaxHealth
//                    + " -> using fallback " + defendedMax + " instead.");
        }

        // Engine cap: Paper 1.21.x hard-limits health to 1024.0
        if (defendedMax > ENGINE_MAX_HEALTH) {
//            log.warning("[ConquestDragons] DragonModel '" + model.configId()
//                    + "' requested maxHealth=" + defendedMax
//                    + " which exceeds engine cap " + ENGINE_MAX_HEALTH
//                    + ". Clamping to " + ENGINE_MAX_HEALTH
//                    + " to avoid IllegalArgumentException.");
            defendedMax = ENGINE_MAX_HEALTH;
        }

        DragonGlowColorHealthKey glowProfileKey = (rawGlowProfile != null)
                ? rawGlowProfile
                : DragonGlowColorHealthKey.SIMPLE;

        DragonGlowColorHealthKey bossbarProfileKey = (rawBossbarProfile != null)
                ? rawBossbarProfile
                : DragonGlowColorHealthKey.SIMPLE;

        // Difficulty (for extra visibility in logs)
        DragonDifficultyModel diff = model.difficulty();

//        log.info("[ConquestDragons] Spawning dragon configId=" + model.configId()
//                + ", displayName=" + model.displayName()
//                + ", requestedMaxHealth=" + rawMaxHealth
//                + ", appliedMaxHealth=" + defendedMax
//                + ", glowProfile=" + glowProfileKey
//                + ", bossbarProfile=" + bossbarProfileKey
//                + ", difficultyKey=" + (diff != null ? diff.difficultyKey() : "null")
//                + ", world=" + world.getName()
//                + ", xyz=" + finalSpawnLocation.getBlockX() + ","
//                + finalSpawnLocation.getBlockY() + ","
//                + finalSpawnLocation.getBlockZ()
//                + " (with random offset)");

        // ---------------------------------------------------
        // Spawn as a standalone EnderDragon entity.
        // ---------------------------------------------------
        EnderDragon dragon = (EnderDragon) world.spawnEntity(finalSpawnLocation, EntityType.ENDER_DRAGON);

        // ---------------------------------------------------
        // Force the dragon to be "active" and moving
        // ---------------------------------------------------
        try {
            // CIRCLING is the usual flying pattern around a point in the End.
            // This helps avoid the "standing still until damaged" behavior.
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
        } catch (NoSuchMethodError ignored) {
            // In case of API differences, just fail silently.
        }

        // ---------------------------------------------------
        // Identity / Name
        // ---------------------------------------------------
        dragon.customName(
                MiniMessage.miniMessage().deserialize(model.displayName())
        );
        dragon.setCustomNameVisible(true);
        dragon.setRemoveWhenFarAway(false);

        // ---------------------------------------------------
        // Health (with defended + capped maxHealth)
        // ---------------------------------------------------
        if (dragon.getAttribute(Attribute.MAX_HEALTH) != null) {
            dragon.getAttribute(Attribute.MAX_HEALTH).setBaseValue(defendedMax);
        }

        dragon.setHealth(defendedMax);

        // Extra log just to be sure what ended up on the entity:
        double finalAttr = dragon.getAttribute(Attribute.MAX_HEALTH) != null
                ? dragon.getAttribute(Attribute.MAX_HEALTH).getValue()
                : dragon.getHealth();

//        log.info("[ConquestDragons] Spawned dragon entity=" + dragon.getUniqueId()
//                + " -> entityMaxHealth=" + finalAttr
//                + ", currentHealth=" + dragon.getHealth());

        // ---------------------------------------------------
        // Glow / glowProfileKey
        // ---------------------------------------------------
        dragon.setGlowing(true);

        PersistentDataContainer pdc = dragon.getPersistentDataContainer();

        // Store glow profile
        NamespacedKey glowKey = new NamespacedKey(plugin, "dragon_glow_profile");
        pdc.set(glowKey, PersistentDataType.STRING, glowProfileKey.name());

        // Store bossbar profile
        NamespacedKey bossbarProfilePdcKey = new NamespacedKey(plugin, "dragon_bossbar_profile");
        pdc.set(bossbarProfilePdcKey, PersistentDataType.STRING, bossbarProfileKey.name());

        // Store bossbar display name (MiniMessage string)
        NamespacedKey bossbarNameKey = new NamespacedKey(plugin, "dragon_bossbar_name_mm");
        pdc.set(bossbarNameKey, PersistentDataType.STRING, model.displayName());

        // Store dragon config id for runtime lookup
        NamespacedKey idKey = new NamespacedKey(plugin, "dragon_id");
        pdc.set(idKey, PersistentDataType.STRING, model.configId());

        // ---------------------------------------------------
        // Difficulty data → entity PDC
        // ---------------------------------------------------
        if (diff != null) {
            NamespacedKey diffKeyKey     = new NamespacedKey(plugin, "difficulty_key");
            NamespacedKey diffDisplayKey = new NamespacedKey(plugin, "difficulty_display_name");
            pdc.set(diffKeyKey,     PersistentDataType.STRING, diff.difficultyKey().name());
            pdc.set(diffDisplayKey, PersistentDataType.STRING, diff.displayName());

            NamespacedKey speedKey       = new NamespacedKey(plugin, "difficulty_speed");
            NamespacedKey atkSpeedKey    = new NamespacedKey(plugin, "difficulty_attack_speed");
            NamespacedKey scaleKey       = new NamespacedKey(plugin, "difficulty_scale_strength");
            NamespacedKey barrierKey     = new NamespacedKey(plugin, "difficulty_barrier_strength");
            NamespacedKey summonSpeedKey = new NamespacedKey(plugin, "difficulty_summon_speed");
            NamespacedKey summonStrKey   = new NamespacedKey(plugin, "difficulty_summon_strength");
            NamespacedKey aiKey          = new NamespacedKey(plugin, "difficulty_ai");

            pdc.set(speedKey,       PersistentDataType.STRING, diff.speedKey().name());
            pdc.set(atkSpeedKey,    PersistentDataType.STRING, diff.attackSpeedKey().name());
            pdc.set(scaleKey,       PersistentDataType.STRING, diff.scaleStrengthKey().name());
            pdc.set(barrierKey,     PersistentDataType.STRING, diff.barrierKey().name());
            pdc.set(summonSpeedKey, PersistentDataType.STRING, diff.summonSpeedKey().name());
            pdc.set(summonStrKey,   PersistentDataType.STRING, diff.summonStrengthKey().name());
            pdc.set(aiKey,          PersistentDataType.STRING, diff.aiKey().name());
        } else {
            log.warning("[ConquestDragons] DragonModel '" + model.configId()
                    + "' has null difficulty() – difficulty PDC not written.");
        }

        // ---------------------------------------------------
        // No fog / no vanilla boss battle
        // ---------------------------------------------------

        return dragon;
    }

    /**
     * Apply a small random horizontal offset around the base location.
     * Y is kept identical; X/Z are perturbed within ±RANDOM_SPAWN_OFFSET_BLOCKS.
     */
    private static Location applyRandomHorizontalOffset(Location base) {
        if (base == null || base.getWorld() == null) {
            return base;
        }

        double radius = RANDOM_SPAWN_OFFSET_BLOCKS;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        double offsetX = rnd.nextDouble(-radius, radius);
        double offsetZ = rnd.nextDouble(-radius, radius);

        Location clone = base.clone();
        clone.add(offsetX, 0.0, offsetZ);
        return clone;
    }
}
