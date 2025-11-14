package org.conquestDragons.conquestDragons.dragonHandler.keyHandler;

public enum DragonDifficultyKey {
    EASY,
    MEDIUM,
    HARD,
    BEDROCK,
    CUSTOM;

    public boolean isCustom() {
        return this == CUSTOM;
    }

    public boolean isPreset() {
        return this != CUSTOM;
    }
}
