package org.conquestDragons.conquestDragons.dragonHandler;

import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.*;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys.*;

import java.util.Objects;
import java.util.Locale;

/**
 * Represents a complete difficulty preset for a dragon.
 *
 * This includes:
 *  - The difficultyKey (EASY / MEDIUM / HARD / BEDROCK / CUSTOM)
 *  - All tuning enums that describe how this difficulty behaves
 *
 * Loaded from:
 *  - defaultDifficultyValues.yml   (for preset EASY/HARD/etc.)
 *  - dragon-specific YAML          (for CUSTOM difficulties)
 */
public record DragonDifficultyModel(

        String id,                         // config key: "easy", "hard", "custom_shadowlord"
        String displayName,                // MiniMessage: "<red>Hard</red>"
        DragonDifficultyKey difficultyKey, // EASY / MEDIUM / HARD / BEDROCK / CUSTOM

        // ---- Full tuning set ----
        DragonSpeedKey speedKey,
        DragonAttackSpeedKey attackSpeedKey,
        DragonScaleStrength scaleStrengthKey,
        DragonBarrierStrengthKey barrierKey,
        DragonSummonSpeedKey summonSpeedKey,
        DragonSummonStrengthKey summonStrengthKey,
        DragonAIKey aiKey                 // NEW

) {

    public DragonDifficultyModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(difficultyKey, "difficultyKey");

        Objects.requireNonNull(speedKey, "speedKey");
        Objects.requireNonNull(attackSpeedKey, "attackSpeedKey");
        Objects.requireNonNull(scaleStrengthKey, "scaleStrengthKey");
        Objects.requireNonNull(barrierKey, "barrierKey");
        Objects.requireNonNull(summonSpeedKey, "summonSpeedKey");
        Objects.requireNonNull(summonStrengthKey, "summonStrengthKey");
        Objects.requireNonNull(aiKey, "aiKey");
    }

    // ---------------------------------------------------
    // Simple checks
    // ---------------------------------------------------
    public boolean isCustom() {
        return difficultyKey == DragonDifficultyKey.CUSTOM;
    }

    public boolean isPreset() {
        return difficultyKey != DragonDifficultyKey.CUSTOM;
    }

    // ---------------------------------------------------
    // Stable config ID
    // ---------------------------------------------------
    public String configId() {
        return id.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------
    // "with" helpers (immutable design)
    // ---------------------------------------------------
    public DragonDifficultyModel withDisplayName(String newName) {
        return new DragonDifficultyModel(
                this.id,
                newName,
                this.difficultyKey,
                this.speedKey,
                this.attackSpeedKey,
                this.scaleStrengthKey,
                this.barrierKey,
                this.summonSpeedKey,
                this.summonStrengthKey,
                this.aiKey
        );
    }

    public DragonDifficultyModel withKey(DragonDifficultyKey newKey) {
        return new DragonDifficultyModel(
                this.id,
                this.displayName,
                newKey,
                this.speedKey,
                this.attackSpeedKey,
                this.scaleStrengthKey,
                this.barrierKey,
                this.summonSpeedKey,
                this.summonStrengthKey,
                this.aiKey
        );
    }
}
