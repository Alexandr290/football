package com.ballgame.soccer.championship;

public enum ChampionshipType {
    LEAGUE,
    KNOCKOUT;

    public String display() {
        return this == LEAGUE ? "Лига" : "Плей-офф";
    }
}
