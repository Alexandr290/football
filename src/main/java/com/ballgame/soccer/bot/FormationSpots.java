package com.ballgame.soccer.bot;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Places players on the pitch from formation slot depth/lane coordinates.
 */
public final class FormationSpots {

    private FormationSpots() {
    }

    public static Location spawnForSlot(Arena arena, TeamSide team, BotFormation formation, int slotIndex) {
        if (arena == null || formation == null) {
            return null;
        }
        Location ownGoal = goalCenter(arena, team);
        Location oppGoal = goalCenter(arena, team.opposite());
        if (ownGoal == null || oppGoal == null) {
            return arena.getTeamSpawn(team);
        }

        PitchFrame frame = pitchFrame(arena, ownGoal, oppGoal);
        BotFormation.FormationSlot slot = formation.slot(slotIndex);

        double depthFrac = slot.depth() * 1.85 - 0.92;
        double laneFrac = clamp(slot.lane(), -0.98, 0.98);

        Location spot = frame.center().clone()
                .add(frame.forward().clone().multiply(depthFrac * frame.halfLength() * 0.92))
                .add(frame.lateral().clone().multiply(laneFrac * frame.halfWidth() * 0.82));
        spot.setY(groundY(arena, ownGoal));

        Vector face = frame.forward().clone();
        if (face.lengthSquared() > 1e-6) {
            face.normalize();
            spot.setYaw(yawFromDirection(face));
        }
        spot.setPitch(0f);
        return clampToField(arena, spot);
    }

    public static Location strikerSpotForHuman(Arena arena, TeamSide team, BotFormation formation) {
        return spawnForSlot(arena, team, formation, formation.strikerSlotIndex());
    }

    private static Location goalCenter(Arena arena, TeamSide defendingTeam) {
        Cuboid goal = arena.getGoal(defendingTeam);
        if (goal != null) {
            return goal.getCenter();
        }
        return arena.getTeamSpawn(defendingTeam) != null
                ? arena.getTeamSpawn(defendingTeam)
                : arena.getBallSpawn();
    }

    private static double groundY(Arena arena, Location ref) {
        Cuboid field = arena.getField();
        if (field != null) {
            return field.getMinY() + 0.05;
        }
        return ref != null ? ref.getY() : 64.0;
    }

    private static PitchFrame pitchFrame(Arena arena, Location ownGoal, Location oppGoal) {
        Vector forward = flatDirection(ownGoal, oppGoal);
        if (forward.lengthSquared() < 1e-6) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        Vector lateral = new Vector(-forward.getZ(), 0, forward.getX());

        Cuboid field = arena.getField();
        Location center;
        double halfLength;
        double halfWidth;
        if (field != null) {
            center = field.getCenter();
            double sx = field.getSizeX();
            double sz = field.getSizeZ();
            halfLength = 0.5 * (Math.abs(forward.getX()) * sx + Math.abs(forward.getZ()) * sz);
            halfWidth = 0.5 * (Math.abs(lateral.getX()) * sx + Math.abs(lateral.getZ()) * sz);
        } else {
            center = ownGoal.clone().add(oppGoal.clone().subtract(ownGoal).multiply(0.5));
            halfLength = horizontalDistance(ownGoal, oppGoal) * 0.5;
            halfWidth = halfLength * 0.6;
        }
        if (ownGoal != null) {
            center.setY(ownGoal.getY());
        }
        return new PitchFrame(forward, lateral, center,
                Math.max(6.0, halfLength), Math.max(4.0, halfWidth));
    }

    private static Location clampToField(Arena arena, Location loc) {
        Cuboid field = arena.getField();
        if (field == null || loc == null) {
            return loc;
        }
        double inset = 2.0;
        loc.setX(Math.max(field.getMinX() + inset, Math.min(field.getMaxX() - inset, loc.getX())));
        loc.setZ(Math.max(field.getMinZ() + inset, Math.min(field.getMaxZ() - inset, loc.getZ())));
        return loc;
    }

    private static Vector flatDirection(Location from, Location to) {
        if (from == null || to == null) {
            return new Vector();
        }
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        return v;
    }

    private static double horizontalDistance(Location a, Location b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static float yawFromDirection(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private record PitchFrame(Vector forward, Vector lateral, Location center,
                              double halfLength, double halfWidth) {
    }
}
