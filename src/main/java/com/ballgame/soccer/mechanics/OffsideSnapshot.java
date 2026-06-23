package com.ballgame.soccer.mechanics;

import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Positions at the instant a teammate plays the ball (pass or shot). Offside
 * is judged from this snapshot when an attacker later becomes involved.
 */
public final class OffsideSnapshot {

    private final OffsideChecker.PitchAxis axis;
    private final TeamSide attackingTeam;
    private final UUID playedBy;
    private final double ballProgress;
    private final Map<UUID, Double> attackerProgressById;
    private final List<Double> opponentProgresses;

    private OffsideSnapshot(OffsideChecker.PitchAxis axis, TeamSide attackingTeam, UUID playedBy,
                            double ballProgress, Map<UUID, Double> attackerProgressById,
                            List<Double> opponentProgresses) {
        this.axis = axis;
        this.attackingTeam = attackingTeam;
        this.playedBy = playedBy;
        this.ballProgress = ballProgress;
        this.attackerProgressById = attackerProgressById;
        this.opponentProgresses = opponentProgresses;
    }

    public static OffsideSnapshot capture(OffsideChecker.PitchAxis axis, TeamSide attackingTeam,
                                          UUID playedBy, Location ballLoc,
                                          Map<UUID, SoccerPlayer> participants) {
        if (axis == null || ballLoc == null) {
            return null;
        }
        double ballProgress = axis.attackProgress(attackingTeam, ballLoc);
        Map<UUID, Double> attackers = new HashMap<>();
        List<Double> opponents = new java.util.ArrayList<>();

        for (Map.Entry<UUID, SoccerPlayer> entry : participants.entrySet()) {
            SoccerPlayer sp = entry.getValue();
            Player p = sp.getBukkitPlayer();
            if (p == null || !p.isOnline()) {
                continue;
            }
            Location feet = p.getLocation();
            double progress = axis.attackProgress(attackingTeam, feet);
            if (Double.isNaN(progress)) {
                continue;
            }
            if (sp.getTeam() == attackingTeam) {
                attackers.put(entry.getKey(), progress);
            } else {
                opponents.add(progress);
            }
        }
        return new OffsideSnapshot(axis, attackingTeam, playedBy, ballProgress,
                attackers, opponents);
    }

    public TeamSide getAttackingTeam() {
        return attackingTeam;
    }

    public UUID getPlayedBy() {
        return playedBy;
    }

    public OffsideChecker.PitchAxis getAxis() {
        return axis;
    }

    public boolean wasOffside(UUID playerId) {
        if (playerId == null || playerId.equals(playedBy)) {
            return false;
        }
        Double progress = attackerProgressById.get(playerId);
        if (progress == null) {
            return false;
        }
        return OffsideChecker.wasInOffsidePosition(
                axis, attackingTeam, progress, ballProgress, opponentProgresses);
    }
}
