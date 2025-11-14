package org.conquestDragons.conquestDragons.dragonHandler;

import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.*;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.difficultyKeys.*;

import java.util.Objects;

/**
 * Represents a complete difficulty definition for a dragon.
 *
 * Includes:
 *  - The difficultyKey (EASY / MEDIUM / HARD / BEDROCK / CUSTOM)
 *  - All tuning enums describing how the dragon behaves
 *
 * Loaded from:
 *  - defaultDifficultyValues.yml   (preset difficulties)
 *  - dragonData/*.yml             (custom difficulties)
 */
public record DragonDifficultyModel(

        String displayName,                // MiniMessage, e.g. "<red>Hard</red>"
        DragonDifficultyKey difficultyKey, // EASY / MEDIUM / HARD / BEDROCK / CUSTOM

        // ---- Full tuning set ----
        DragonSpeedKey speedKey,
        DragonAttackSpeedKey attackSpeedKey,
        DragonScaleStrength scaleStrengthKey,
        DragonBarrierStrengthKey barrierKey,
        DragonSummonSpeedKey summonSpeedKey,
        DragonSummonStrengthKey summonStrengthKey,
        DragonAIKey aiKey

) {

    public DragonDifficultyModel {
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
    // Immutability: with() helpers
    // ---------------------------------------------------
    public DragonDifficultyModel withDisplayName(String newName) {
        return new DragonDifficultyModel(
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
