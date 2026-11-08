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
    private final int shotsOnTarget;
    private final int shotsOffTarget;
    private final int chancesCreated;
    private final int chancesMissed;
    private final int fouls;
    private final int possessionPct;
    private final int passes;
    private final int passesCompleted;
    private final int passesReceived;
    private final int passAccuracyPct;
    private final int keyPasses;
    private final int crosses;
    private final int tackles;
    private final int tacklesWon;
    private final int interceptions;
    private final int dribbles;
    private final int duelsWon;
    private final int saves;
    private final int clearances;
    private final int touches;
    private final int yellowCards;
    private final int ownGoals;
    private final double distance;
    private final double maxSpeed;
    private final double rating;

    public ParticipantRecord(UUID uuid, String name, String team, boolean bot,
                             int goals, int assists, int shots, int fouls, int possessionPct,
                             int passes, int passesCompleted, int passesReceived,
                             int passAccuracyPct, double rating) {
        this(uuid, name, team, bot, goals, assists, shots, 0, 0, 0, 0, fouls, possessionPct,
                passes, passesCompleted, passesReceived, passAccuracyPct, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0.0, 0.0, rating);
    }

    public ParticipantRecord(UUID uuid, String name, String team, boolean bot,
                             int goals, int assists, int shots,
                             int shotsOnTarget, int shotsOffTarget,
                             int chancesCreated, int chancesMissed,
                             int fouls, int possessionPct,
                             int passes, int passesCompleted, int passesReceived,
                             int passAccuracyPct, int keyPasses, int crosses,
                             int tackles, int tacklesWon, int interceptions,
                             int dribbles, int duelsWon, int saves, int clearances,
                             int touches, int yellowCards, int ownGoals,
                             double distance, double maxSpeed, double rating) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
        this.bot = bot;
        this.goals = goals;
        this.assists = assists;
        this.shots = shots;
        this.shotsOnTarget = shotsOnTarget;
        this.shotsOffTarget = shotsOffTarget;
        this.chancesCreated = chancesCreated;
        this.chancesMissed = chancesMissed;
        this.fouls = fouls;
        this.possessionPct = possessionPct;
        this.passes = passes;
        this.passesCompleted = passesCompleted;
        this.passesReceived = passesReceived;
        this.passAccuracyPct = passAccuracyPct;
        this.keyPasses = keyPasses;
        this.crosses = crosses;
        this.tackles = tackles;
        this.tacklesWon = tacklesWon;
        this.interceptions = interceptions;
        this.dribbles = dribbles;
        this.duelsWon = duelsWon;
        this.saves = saves;
        this.clearances = clearances;
        this.touches = touches;
        this.yellowCards = yellowCards;
        this.ownGoals = ownGoals;
        this.distance = distance;
        this.maxSpeed = maxSpeed;
        this.rating = rating;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public String getTeam() { return team; }
    public boolean isBot() { return bot; }
    public int getGoals() { return goals; }
    public int getAssists() { return assists; }
    public int getShots() { return shots; }
    public int getShotsOnTarget() { return shotsOnTarget; }
    public int getShotsOffTarget() { return shotsOffTarget; }
    public int getChancesCreated() { return chancesCreated; }
    public int getChancesMissed() { return chancesMissed; }
    public int getFouls() { return fouls; }
    public int getPossessionPct() { return possessionPct; }
    public int getPasses() { return passes; }
    public int getPassesCompleted() { return passesCompleted; }
    public int getPassesReceived() { return passesReceived; }
    public int getPassAccuracyPct() { return passAccuracyPct; }
    public int getKeyPasses() { return keyPasses; }
    public int getCrosses() { return crosses; }
    public int getTackles() { return tackles; }
    public int getTacklesWon() { return tacklesWon; }
    public int getInterceptions() { return interceptions; }
    public int getDribbles() { return dribbles; }
    public int getDuelsWon() { return duelsWon; }
    public int getSaves() { return saves; }
    public int getClearances() { return clearances; }
    public int getTouches() { return touches; }
    public int getYellowCards() { return yellowCards; }
    public int getOwnGoals() { return ownGoals; }
    public double getDistance() { return distance; }
    public double getMaxSpeed() { return maxSpeed; }
    public double getRating() { return rating; }
}
