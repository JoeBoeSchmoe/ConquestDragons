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
 */
public final class DragonBuilder {

    /**
     * Paper 1.21.x hard cap for health values.
     * If you try to set health above this, you'll get:
     *  "Health value (X) must be between 0 and 1024.0"
     */
    private static final double ENGINE_MAX_HEALTH = 1024.0;

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
     * @return the spawned EnderDragon instance
     */
    public EnderDragon spawn() {
        if (model == null) {
            throw new IllegalStateException("DragonModel must be set before calling spawn()");
        }
        if (spawnLocation == null) {
            throw new IllegalStateException("spawnLocation must be set before calling spawn()");
        }

        World world = spawnLocation.getWorld();
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
            log.warning("[ConquestDragons] DragonModel '" + model.configId()
                    + "' reported invalid maxHealth=" + rawMaxHealth
                    + " -> using fallback " + defendedMax + " instead.");
        }

        // Engine cap: Paper 1.21.x hard-limits health to 1024.0
        if (defendedMax > ENGINE_MAX_HEALTH) {
            log.warning("[ConquestDragons] DragonModel '" + model.configId()
                    + "' requested maxHealth=" + defendedMax
                    + " which exceeds engine cap " + ENGINE_MAX_HEALTH
                    + ". Clamping to " + ENGINE_MAX_HEALTH
                    + " to avoid IllegalArgumentException.");
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

        log.info("[ConquestDragons] Spawning dragon configId=" + model.configId()
                + ", displayName=" + model.displayName()
                + ", requestedMaxHealth=" + rawMaxHealth
                + ", appliedMaxHealth=" + defendedMax
                + ", glowProfile=" + glowProfileKey
                + ", bossbarProfile=" + bossbarProfileKey
                + ", difficultyKey=" + (diff != null ? diff.difficultyKey() : "null")
                + ", world=" + world.getName()
                + ", xyz=" + spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ());

        // ---------------------------------------------------
        // Spawn as a standalone EnderDragon entity.
        // ---------------------------------------------------
        EnderDragon dragon = (EnderDragon) world.spawnEntity(spawnLocation, EntityType.ENDER_DRAGON);

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

        // This is where your 2500.0 was previously throwing:
        // Paper enforces 0..1024, so we always use defendedMax here.
        dragon.setHealth(defendedMax);

        // Extra log just to be sure what ended up on the entity:
        double finalAttr = dragon.getAttribute(Attribute.MAX_HEALTH) != null
                ? dragon.getAttribute(Attribute.MAX_HEALTH).getValue()
                : dragon.getHealth();

        log.info("[ConquestDragons] Spawned dragon entity=" + dragon.getUniqueId()
                + " -> entityMaxHealth=" + finalAttr
                + ", currentHealth=" + dragon.getHealth());

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

        // Store bossbar display name (MiniMessage string so you can reconstruct Component later)
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
        // We do NOT:
        //  - attach this dragon to the End's DragonBattle
        //  - create a Bukkit BossBar here
        //
        // DragonBossbarManager will:
        //  - detect this dragon via EventSequenceManager + trackDragon(...)
        //  - read 'dragon_bossbar_name_mm' & 'dragon_bossbar_profile'
        //  - create/manage the BossBar on its own.

        return dragon;
    }
}
