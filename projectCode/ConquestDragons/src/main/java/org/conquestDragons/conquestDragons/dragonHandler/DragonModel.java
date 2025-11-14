package org.conquestDragons.conquestDragons.dragonHandler;

import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonDifficultyKey;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonGlowColorHealthKey;

import java.util.Locale;
import java.util.Objects;

/**
 * Represents a fully defined dragon template/config.
 *
 * Loaded from:
 *  - default dragon registry / dragon-specific YAML
 *
 * Contains:
 *  - Identity         (id, displayName)
 *  - Difficulty       (DragonDifficultyModel, including difficultyKey + tuning enums)
 *  - Health color keys (glow + bossbar profile keys)
 */
public record DragonModel(

        String id,                              // e.g. "shadowlord", "fire_dragon"
        String displayName,                     // MiniMessage: "<dark_red>Shadowlord</dark_red>"

        DragonDifficultyModel difficulty,       // full difficulty preset (includes difficultyKey)

        DragonGlowColorHealthKey glowProfileKey,    // SIMPLE / DETAILED (for entity glow)
        DragonGlowColorHealthKey bossbarProfileKey  // SIMPLE / DETAILED (for bossbar colors)

) {

    public DragonModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(glowProfileKey, "glowProfileKey");
        Objects.requireNonNull(bossbarProfileKey, "bossbarProfileKey");
    }

    // ---------------------------------------------------
    // Convenience accessors
    // ---------------------------------------------------

    /** Stable lowercase config id. */
    public String configId() {
        return id.toLowerCase(Locale.ROOT);
    }

    /** Forward difficulty key from the embedded difficulty model. */
    public DragonDifficultyKey difficultyKey() {
        return difficulty.difficultyKey();
    }

    public boolean isCustomDifficulty() {
        return difficulty.isCustom();
    }

    // ---------------------------------------------------
    // "with" helpers (immutability)
    // ---------------------------------------------------

    public DragonModel withDisplayName(String newDisplayName) {
        return new DragonModel(
                this.id,
                newDisplayName,
                this.difficulty,
                this.glowProfileKey,
                this.bossbarProfileKey
        );
    }

    public DragonModel withDifficulty(DragonDifficultyModel newDifficulty) {
        return new DragonModel(
                this.id,
                this.displayName,
                Objects.requireNonNull(newDifficulty, "newDifficulty"),
                this.glowProfileKey,
                this.bossbarProfileKey
        );
    }

    public DragonModel withGlowProfile(DragonGlowColorHealthKey newGlowProfileKey) {
        return new DragonModel(
                this.id,
                this.displayName,
                this.difficulty,
                Objects.requireNonNull(newGlowProfileKey, "newGlowProfileKey"),
                this.bossbarProfileKey
        );
    }

    public DragonModel withBossbarProfile(DragonGlowColorHealthKey newBossbarProfileKey) {
        return new DragonModel(
                this.id,
                this.displayName,
                this.difficulty,
                this.glowProfileKey,
                Objects.requireNonNull(newBossbarProfileKey, "newBossbarProfileKey")
        );
    }
}
