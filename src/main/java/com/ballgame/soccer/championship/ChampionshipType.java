package com.ballgame.soccer.championship;

public enum ChampionshipType {
    LEAGUE,
    KNOCKOUT;

    public String display() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
