package com.ballgame.soccer.championship;

import java.util.UUID;

public final class ChampFixture {

    private final String id;
    private final int round;
    private String teamAId;
    private String teamBId;
    private FixtureState state;
    private int scoreA;
    private int scoreB;
    private String winnerTeamId;
    private String nextFixtureId;

    public ChampFixture(String id, int round, String teamAId, String teamBId) {
        this.id = id;
        this.round = round;
        this.teamAId = teamAId;
        this.teamBId = teamBId;
        this.state = FixtureState.PENDING;
    }

    public String getId() {
        return id;
    }

    public int getRound() {
        return round;
    }

    public String getTeamAId() {
        return teamAId;
    }

    public String getTeamBId() {
        return teamBId;
    }

    public void setTeamAId(String teamAId) {
        this.teamAId = teamAId;
    }

    public void setTeamBId(String teamBId) {
        this.teamBId = teamBId;
    }

    public FixtureState getState() {
        return state;
    }

    public void setState(FixtureState state) {
        this.state = state;
    }

    public int getScoreA() {
        return scoreA;
    }

    public int getScoreB() {
        return scoreB;
    }

    public void setScores(int scoreA, int scoreB) {
        this.scoreA = scoreA;
        this.scoreB = scoreB;
    }

    public String getWinnerTeamId() {
        return winnerTeamId;
    }

    public void setWinnerTeamId(String winnerTeamId) {
        this.winnerTeamId = winnerTeamId;
    }

    public String getNextFixtureId() {
        return nextFixtureId;
    }

    public void setNextFixtureId(String nextFixtureId) {
        this.nextFixtureId = nextFixtureId;
    }

    public boolean involvesTeam(String teamId) {
        return teamId != null && (teamId.equals(teamAId) || teamId.equals(teamBId));
    }

    public boolean involvesPlayer(Championship championship, UUID playerId) {
        ChampTeam a = championship.getTeam(teamAId);
        ChampTeam b = championship.getTeam(teamBId);
        return (a != null && a.contains(playerId)) || (b != null && b.contains(playerId));
    }

    public boolean isBye() {
        return teamAId == null || teamBId == null;
    }
}
