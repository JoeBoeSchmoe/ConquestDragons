package org.conquestDragons.conquestDragons.dragonHandler;

import net.kyori.adventure.text.Component;
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

/**
 * Builder for spawning configured EnderDragon instances from a DragonModel.
 *
 * Responsibilities:
 *  - Spawn an EnderDragon with:
 *      - No vanilla AI (we replace it with our own logic).
 *      - Max health from DragonModel.maxHealth().
 *      - Glow enabled and glowProfileKey stored in PDC.
 *      - Bossbar profile + bossbar name stored in PDC.
 *      - All DragonDifficultyModel knobs stored in PDC.
 *  - NOT creating any vanilla End dragon "fog" / boss battle.
 *    (Bossbars will be handled separately based on regions.)
 */
public final class DragonBuilder {

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
     *  - No vanilla AI
     *  - Max health from DragonModel
     *  - Glow enabled and glowProfileKey stored in PDC
     *  - Bossbar profile + bossbar name stored in PDC
     *  - All difficulty tuning knobs stored in PDC
     *  - No vanilla End dragon boss-fog or boss battle
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

        // Spawn as a standalone EnderDragon entity.
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
        // Disable vanilla AI
        // ---------------------------------------------------
        //dragon.setAI(false);

        // ---------------------------------------------------
        // Health
        // ---------------------------------------------------
        double maxHealth = model.maxHealth();

        if (dragon.getAttribute(Attribute.MAX_HEALTH) != null) {
            dragon.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth);
        }
        dragon.setHealth(maxHealth);

        // ---------------------------------------------------
        // Glow / glowProfileKey
        // ---------------------------------------------------
        DragonGlowColorHealthKey glowProfileKey = model.glowProfileKey();
        DragonGlowColorHealthKey bossbarProfileKey = model.bossbarProfileKey();

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
        DragonDifficultyModel diff = model.difficulty();

        // Core difficulty key + display name
        NamespacedKey diffKeyKey = new NamespacedKey(plugin, "difficulty_key");
        NamespacedKey diffDisplayKey = new NamespacedKey(plugin, "difficulty_display_name");
        pdc.set(diffKeyKey, PersistentDataType.STRING, diff.difficultyKey().name());
        pdc.set(diffDisplayKey, PersistentDataType.STRING, diff.displayName());

        // Tuning knobs (enums, stored by name)
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

        // ---------------------------------------------------
        // No fog / no vanilla boss battle
        // ---------------------------------------------------
        // We do NOT:
        //  - attach this dragon to the End's DragonBattle
        //  - create a Bukkit BossBar here
        //
        // A separate region-based bossbar manager can:
        //  - detect this dragon by "dragon_id"
        //  - read "dragon_bossbar_name_mm" and "dragon_bossbar_profile"
        //  - create/manage the BossBar on its own.

        return dragon;
    }
}
