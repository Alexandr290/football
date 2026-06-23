package com.ballgame.soccer.game;

import com.ballgame.soccer.arena.ThrowInGeometry;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;

/** Auto taker selection, touchline confinement, and visuals for throw-ins. */
final class ThrowInRestartHelper {

    private ThrowInRestartHelper() {
    }

    static SoccerPlayer findNearestTaker(Map<UUID, SoccerPlayer> players, TeamSide team, Location spot) {
        SoccerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (SoccerPlayer sp : players.values()) {
            if (sp.getTeam() != team || sp.isBot()) {
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

    static Location computeTakerSpot(Location spot, ThrowInGeometry geometry) {
        if (spot == null) {
            return null;
        }
        Vector inward = geometry.inward().clone();
        if (inward.lengthSquared() < 1e-6) {
            inward = new Vector(0, 0, 1);
        }
        inward.normalize();
        Location pos = spot.clone();
        pos.setYaw(yawFromDirection(inward));
        pos.setPitch(0f);
        return pos;
    }

    static float yawFromDirection(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    static Location clampTakerPosition(Location current, Location anchor, ThrowInGeometry geometry,
                                       Cuboid field, double wanderBlocks) {
        if (current == null || anchor == null || field == null || geometry == null) {
            return current;
        }
        double inset = 0.28;
        double x = current.getX();
        double y = current.getY();
        double z = current.getZ();

        if (geometry.touchlineOnX()) {
            double lineX = geometry.exitFromMin() ? field.getMinX() + inset : field.getMaxX() - inset;
            x = lineX;
            double lateral = z - anchor.getZ();
            if (Math.abs(lateral) > wanderBlocks) {
                z = anchor.getZ() + Math.signum(lateral) * wanderBlocks;
            }
            z = Math.max(field.getMinZ() + inset, Math.min(field.getMaxZ() - inset, z));
            if (geometry.exitFromMin()) {
                x = Math.max(lineX, x);
            } else {
                x = Math.min(lineX, x);
            }
        } else {
            double lineZ = geometry.exitFromMin() ? field.getMinZ() + inset : field.getMaxZ() - inset;
            z = lineZ;
            double lateral = x - anchor.getX();
            if (Math.abs(lateral) > wanderBlocks) {
                x = anchor.getX() + Math.signum(lateral) * wanderBlocks;
            }
            x = Math.max(field.getMinX() + inset, Math.min(field.getMaxX() - inset, x));
            if (geometry.exitFromMin()) {
                z = Math.max(lineZ, z);
            } else {
                z = Math.min(lineZ, z);
            }
        }

        Location clamped = new Location(current.getWorld(), x, y, z, current.getYaw(), current.getPitch());
        clamped.setYaw(anchor.getYaw());
        return clamped;
    }

    static boolean hasCrossedTouchlineOutward(Location loc, ThrowInGeometry geometry, Cuboid field) {
        if (loc == null || field == null || geometry == null) {
            return false;
        }
        double margin = 0.12;
        if (geometry.touchlineOnX()) {
            if (geometry.exitFromMin()) {
                return loc.getX() < field.getMinX() + margin;
            }
            return loc.getX() > field.getMaxX() - margin;
        }
        if (geometry.exitFromMin()) {
            return loc.getZ() < field.getMinZ() + margin;
        }
        return loc.getZ() > field.getMaxZ() - margin;
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
        if (settings.throwInTakerGlow) {
            player.setGlowing(true);
        }
        player.setWalkSpeed(0.22f);
    }

    static void clearParticipantMovement(Player player) {
        player.setGlowing(false);
        player.setWalkSpeed(0.2f);
    }

    static void spawnBallMarker(Location ballLoc, TeamSide team) {
        if (ballLoc == null || ballLoc.getWorld() == null) {
            return;
        }
        Location center = ballLoc.clone().add(0, 0.12, 0);
        ballLoc.getWorld().spawnParticle(Particle.END_ROD, center, 2, 0.16, 0.04, 0.16, 0.01);
        Color dust = team == TeamSide.RED ? Color.fromRGB(255, 210, 60) : Color.fromRGB(120, 180, 255);
        ballLoc.getWorld().spawnParticle(
                Particle.DUST,
                center,
                3,
                0.18,
                0.04,
                0.18,
                0,
                new Particle.DustOptions(dust, 1.0f));
    }
}
