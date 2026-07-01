package com.ballgame.soccer.game;

public enum BotDifficulty {
    EASY("champ-bots-difficulty-easy"),
    MEDIUM("champ-bots-difficulty-medium"),
    HARD("champ-bots-difficulty-hard");

    private final String langKey;

    BotDifficulty(String langKey) {
        this.langKey = langKey;
    }

    public String langKey() {
        return langKey;
    }

    public BotDifficulty previous() {
        return switch (this) {
            case EASY -> HARD;
            case MEDIUM -> EASY;
            case HARD -> MEDIUM;
        };
    }

    public BotDifficulty next() {
        return switch (this) {
            case EASY -> MEDIUM;
            case MEDIUM -> HARD;
            case HARD -> EASY;
        };
    }
}
