package com.ballgame.soccer.championship;

public enum FixtureState {
    PENDING,
    LIVE,
    COMPLETED;

    public String display() {
        return switch (this) {
            case PENDING -> "Scheduled";
            case LIVE -> "Live";
            case COMPLETED -> "Full Time";
        };
    }
}
