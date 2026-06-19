package com.ballgame.soccer.championship;

public enum ChampionshipState {
    REGISTRATION,
    ACTIVE,
    FINISHED;

    public String display() {
        return switch (this) {
            case REGISTRATION -> "Registration";
            case ACTIVE -> "In Progress";
            case FINISHED -> "Finished";
        };
    }
}
