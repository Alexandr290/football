package com.ballgame.soccer.mechanics;

import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Positions at the instant a teammate plays the ball (pass or shot). Offside
 * is judged from this snapshot when an attacker later becomes involved.
 */
public final class OffsideSnapshot {

    /** A participant without a Bukkit player (match bot) and where it stood. */
    public record Body(UUID id, TeamSide team, Location feet) {
    }

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
        return capture(axis, attackingTeam, playedBy, ballLoc, participants, Collections.emptyList());
    }

    public static OffsideSnapshot capture(OffsideChecker.PitchAxis axis, TeamSide attackingTeam,
                                          UUID playedBy, Location ballLoc,
                                          Map<UUID, SoccerPlayer> participants,
                                          Collection<Body> bots) {
        if (axis == null || ballLoc == null) {
            return null;
        }
        double ballProgress = axis.attackProgress(attackingTeam, ballLoc);
        Map<UUID, Double> attackers = new HashMap<>();
        List<Double> opponents = new java.util.ArrayList<>();

        for (Map.Entry<UUID, SoccerPlayer> entry : participants.entrySet()) {
            SoccerPlayer sp = entry.getValue();
            if (sp.isReferee() || sp.getTeam() == null) {
                continue;
            }
            Player p = sp.getBukkitPlayer();
            if (p == null || !p.isOnline()) {
                continue;
            }
            record(axis, attackingTeam, entry.getKey(), sp.getTeam(), p.getLocation(), attackers, opponents);
        }
        if (bots != null) {
            for (Body body : bots) {
                if (body != null && body.team() != null) {
                    record(axis, attackingTeam, body.id(), body.team(), body.feet(), attackers, opponents);
                }
            }
        }
        return new OffsideSnapshot(axis, attackingTeam, playedBy, ballProgress,
                attackers, opponents);
    }

    private static void record(OffsideChecker.PitchAxis axis, TeamSide attackingTeam, UUID id,
                               TeamSide team, Location feet, Map<UUID, Double> attackers,
                               List<Double> opponents) {
        double progress = axis.attackProgress(attackingTeam, feet);
        if (Double.isNaN(progress)) {
            return;
        }
        if (team == attackingTeam) {
            attackers.put(id, progress);
        } else {
            opponents.add(progress);
        }
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
