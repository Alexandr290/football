package com.ballgame.soccer.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persisted summary of one finished match. */
public final class MatchRecord {

    private final String id;
    private final long finishedAt;
    private final String arenaName;
    private final int redScore;
    private final int blueScore;
    private final String winner;
    private final int redPossessionPct;
    private final int bluePossessionPct;
    private final int redShots;
    private final int blueShots;
    private final int redGoals;
    private final int blueGoals;
    private final int redFouls;
    private final int blueFouls;
    private final int redPasses;
    private final int bluePasses;
    private final List<ParticipantRecord> participants;

    public MatchRecord(String id, long finishedAt, String arenaName,
                       int redScore, int blueScore, String winner,
                       int redPossessionPct, int bluePossessionPct,
                       int redShots, int blueShots, int redGoals, int blueGoals,
                       int redFouls, int blueFouls, int redPasses, int bluePasses,
                       List<ParticipantRecord> participants) {
        this.id = id;
        this.finishedAt = finishedAt;
        this.arenaName = arenaName;
        this.redScore = redScore;
        this.blueScore = blueScore;
        this.winner = winner;
        this.redPossessionPct = redPossessionPct;
        this.bluePossessionPct = bluePossessionPct;
        this.redShots = redShots;
        this.blueShots = blueShots;
        this.redGoals = redGoals;
        this.blueGoals = blueGoals;
        this.redFouls = redFouls;
        this.blueFouls = blueFouls;
        this.redPasses = redPasses;
        this.bluePasses = bluePasses;
        this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
    }

    public String getId() { return id; }
    public long getFinishedAt() { return finishedAt; }
    public String getArenaName() { return arenaName; }
    public int getRedScore() { return redScore; }
    public int getBlueScore() { return blueScore; }
    public String getWinner() { return winner; }
    public int getRedPossessionPct() { return redPossessionPct; }
    public int getBluePossessionPct() { return bluePossessionPct; }
    public int getRedShots() { return redShots; }
    public int getBlueShots() { return blueShots; }
    public int getRedGoals() { return redGoals; }
    public int getBlueGoals() { return blueGoals; }
    public int getRedFouls() { return redFouls; }
    public int getBlueFouls() { return blueFouls; }
    public int getRedPasses() { return redPasses; }
    public int getBluePasses() { return bluePasses; }
    public List<ParticipantRecord> getParticipants() { return participants; }

    public ParticipantRecord findParticipant(String uuid) {
        for (ParticipantRecord p : participants) {
            if (p.getUuid().toString().equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    public List<ParticipantRecord> participantsForTeam(String team) {
        List<ParticipantRecord> list = new ArrayList<>();
        for (ParticipantRecord p : participants) {
            if (p.getTeam().equalsIgnoreCase(team)) {
                list.add(p);
            }
        }
        return list;
    }
}
