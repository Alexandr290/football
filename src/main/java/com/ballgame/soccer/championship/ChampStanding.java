package com.ballgame.soccer.championship;

public final class ChampStanding {

    private final String teamId;
    private int played;
    private int won;
    private int drawn;
    private int lost;
    private int goalsFor;
    private int goalsAgainst;
    private int points;

    public ChampStanding(String teamId) {
        this.teamId = teamId;
    }

    public String getTeamId() {
        return teamId;
    }

    public int getPlayed() {
        return played;
    }

    public int getWon() {
        return won;
    }

    public int getDrawn() {
        return drawn;
    }

    public int getLost() {
        return lost;
    }

    public int getGoalsFor() {
        return goalsFor;
    }

    public int getGoalsAgainst() {
        return goalsAgainst;
    }

    public int getGoalDifference() {
        return goalsFor - goalsAgainst;
    }

    public int getPoints() {
        return points;
    }

    public void applyResult(int scored, int conceded, int pointsWin, int pointsDraw, int pointsLoss) {
        played++;
        goalsFor += scored;
        goalsAgainst += conceded;
        if (scored > conceded) {
            won++;
            points += pointsWin;
        } else if (scored < conceded) {
            lost++;
            points += pointsLoss;
        } else {
            drawn++;
            points += pointsDraw;
        }
    }

    public void load(int played, int won, int drawn, int lost,
                     int goalsFor, int goalsAgainst, int points) {
        this.played = played;
        this.won = won;
        this.drawn = drawn;
        this.lost = lost;
        this.goalsFor = goalsFor;
        this.goalsAgainst = goalsAgainst;
        this.points = points;
    }
}
