package com.ballgame.soccer.stats;

import java.util.UUID;

/**
 * Lifetime, persistent statistics for a single player across all matches.
 */
public final class PlayerStats {

    private final UUID uuid;
    private String name;

    private int gamesPlayed;
    private int wins;
    private int losses;
    private int draws;
    private int goals;
    private int assists;
    private int ownGoals;
    private int tackles;
    private int saves;
    private int cleanSheets;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUuid() { return uuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getGamesPlayed() { return gamesPlayed; }
    public void addGame() { gamesPlayed++; }
    public void addGames(int amount) { gamesPlayed += amount; }

    public int getWins() { return wins; }
    public void addWin() { wins++; }
    public void addWinsRaw(int amount) { wins += amount; }

    public int getLosses() { return losses; }
    public void addLoss() { losses++; }
    public void addLossesRaw(int amount) { losses += amount; }

    public int getDraws() { return draws; }
    public void addDraw() { draws++; }
    public void addDrawsRaw(int amount) { draws += amount; }

    public int getGoals() { return goals; }
    public void addGoals(int amount) { goals += amount; }

    public int getAssists() { return assists; }
    public void addAssists(int amount) { assists += amount; }

    public int getOwnGoals() { return ownGoals; }
    public void addOwnGoals(int amount) { ownGoals += amount; }

    public int getTackles() { return tackles; }
    public void addTackles(int amount) { tackles += amount; }

    public int getSaves() { return saves; }
    public void addSaves(int amount) { saves += amount; }

    public int getCleanSheets() { return cleanSheets; }
    public void addCleanSheet() { cleanSheets++; }
    public void addCleanSheetsRaw(int amount) { cleanSheets += amount; }

    public double getWinRate() {
        if (gamesPlayed == 0) {
            return 0.0;
        }
        return (double) wins / gamesPlayed * 100.0;
    }
}
