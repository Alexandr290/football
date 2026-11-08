package com.ballgame.soccer.championship;

public enum ChampionshipState {
    REGISTRATION,
    ACTIVE,
    FINISHED;

    public String display() {
        return switch (this) {
            case REGISTRATION -> "Регистрация";
            case ACTIVE -> "Идёт";
            case FINISHED -> "Завершён";
        };
    }
}
