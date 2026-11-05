package com.ballgame.soccer.history;

import java.util.UUID;

/** Snapshot of one human or bot participant at the end of a match. */
public final class ParticipantRecord {

    private final UUID uuid;
    private final String name;
    private final String team;
    private final boolean bot;
    private final int goals;
    private final int assists;
    private final int shots;
    private final int fouls;
    private final int possessionPct;
    private final int passes;
    private final int passesCompleted;
    private final int passesReceived;
    private final int passAccuracyPct;
    private final double rating;

    public ParticipantRecord(UUID uuid, String name, String team, boolean bot,
                             int goals, int assists, int shots, int fouls, int possessionPct,
                             int passes, int passesCompleted, int passesReceived,
                             int passAccuracyPct, double rating) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
        this.bot = bot;
        this.goals = goals;
        this.assists = assists;
        this.shots = shots;
        this.fouls = fouls;
        this.possessionPct = possessionPct;
        this.passes = passes;
        this.passesCompleted = passesCompleted;
        this.passesReceived = passesReceived;
        this.passAccuracyPct = passAccuracyPct;
        this.rating = rating;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public String getTeam() { return team; }
    public boolean isBot() { return bot; }
    public int getGoals() { return goals; }
    public int getAssists() { return assists; }
    public int getShots() { return shots; }
    public int getFouls() { return fouls; }
    public int getPossessionPct() { return possessionPct; }
    public int getPasses() { return passes; }
    public int getPassesCompleted() { return passesCompleted; }
    public int getPassesReceived() { return passesReceived; }
    public int getPassAccuracyPct() { return passAccuracyPct; }
    public double getRating() { return rating; }
}
