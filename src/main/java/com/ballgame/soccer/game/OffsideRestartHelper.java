package com.ballgame.soccer.game;

import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.mechanics.OffsideChecker;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;

/** Geometry, visuals, and opponent restraint for offside free-kick restarts. */
final class OffsideRestartHelper {

    private OffsideRestartHelper() {
    }

    static SoccerPlayer findNearestTaker(Map<UUID, SoccerPlayer> players, TeamSide team, Location spot) {
        SoccerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (SoccerPlayer sp : players.values()) {
            if (sp.getTeam() != team) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            double dist = horizontalDistance(player.getLocation(), spot);
            if (dist < best) {
                best = dist;
                nearest = sp;
            }
        }
        return nearest;
    }

    static Location computeTakerSpot(Location ballSpot, OffsideChecker.PitchAxis axis, TeamSide takingTeam) {
        Vector attack = axis != null ? axis.attackDirection(takingTeam) : new Vector(0, 0, 1);
        if (attack.lengthSquared() < 1e-6) {
            attack = new Vector(0, 0, 1);
        }
        attack.normalize();

        Location pos = ballSpot.clone().subtract(attack.clone().multiply(1.15));
        pos.setY(ballSpot.getY());
        pos.setYaw(yawFromDirection(attack));
        pos.setPitch(0f);
        return pos;
    }

    static float yawFromDirection(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    static double horizontalDistance(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return Double.MAX_VALUE;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    static void applyTakerVisuals(Player player, SoccerSettings settings) {
        if (settings.offsideTakerGlow) {
            player.setGlowing(true);
        }
        player.setWalkSpeed(0.24f);
    }

    static void clearParticipantMovement(Player player) {
        player.setGlowing(false);
        player.setWalkSpeed(0.2f);
    }

    static void spawnBallMarker(Location ballLoc, TeamSide takingTeam) {
        if (ballLoc == null || ballLoc.getWorld() == null) {
            return;
        }
        Location center = ballLoc.clone().add(0, 0.12, 0);
        ballLoc.getWorld().spawnParticle(Particle.END_ROD, center, 2, 0.18, 0.04, 0.18, 0.01);
        Color dust = takingTeam == TeamSide.RED ? Color.fromRGB(220, 40, 40) : Color.fromRGB(40, 80, 220);
        ballLoc.getWorld().spawnParticle(
                Particle.DUST,
                center,
                3,
                0.22,
                0.05,
                0.22,
                0,
                new Particle.DustOptions(dust, 1.0f));
    }

    static void restrainOpponent(Player opponent, Location ballLoc, SoccerSettings settings) {
        if (!settings.offsideRestrainOpponents || ballLoc == null) {
            return;
        }
        double dist = horizontalDistance(opponent.getLocation(), ballLoc);
        double radius = settings.offsideOpponentRestrainRadius;
        double minGap = settings.offsideOpponentMinGap;
        if (dist > radius) {
            opponent.setWalkSpeed(0.2f);
            return;
        }
        opponent.setWalkSpeed(0.06f);
        if (dist < minGap && dist > 0.05) {
            Vector push = opponent.getLocation().toVector().subtract(ballLoc.toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                push.normalize().multiply(0.24).setY(0.06);
                opponent.setVelocity(push);
            }
        }
    }
}
