package com.ballgame.soccer.championship;

public enum FixtureState {
    PENDING,
    LIVE,
    COMPLETED;

    public String display() {
        return switch (this) {
            case PENDING -> "Запланирован";
            case LIVE -> "Идёт";
            case COMPLETED -> "Завершён";
        };
    }
}
