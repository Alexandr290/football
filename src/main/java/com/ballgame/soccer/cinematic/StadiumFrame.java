package com.ballgame.soccer.cinematic;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * Pitch-space coordinates for cutscenes: {@code along} runs from the red goal to
 * the blue goal, {@code across} points from the centre toward the "main stand"
 * touchline (where the teams walk out), {@code up} is height above the turf.
 * Every shot is authored in these units, so the show fits any arena size and
 * orientation.
 */
public record StadiumFrame(World world, Vector center, Vector forward, Vector side,
                           double halfLength, double halfWidth, double groundY) {

    public static StadiumFrame of(Arena arena) {
        Cuboid field = arena.getField();
        Cuboid redGoal = arena.getRedGoal();
        Cuboid blueGoal = arena.getBlueGoal();
        if (field == null || field.getWorld() == null) {
            return null;
        }
        Location fieldCenter = field.getCenter();
        Vector forward;
        if (redGoal != null && blueGoal != null) {
            forward = blueGoal.getCenter().toVector().subtract(redGoal.getCenter().toVector());
            forward.setY(0);
        } else {
            forward = field.getSizeX() >= field.getSizeZ() ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
        }
        if (forward.lengthSquared() < 1.0e-6) {
            forward = new Vector(0, 0, 1);
        }
        // Snap to the dominant axis: pitches are axis-aligned cuboids.
        if (Math.abs(forward.getX()) >= Math.abs(forward.getZ())) {
            forward = new Vector(Math.signum(forward.getX()), 0, 0);
        } else {
            forward = new Vector(0, 0, Math.signum(forward.getZ()));
        }
        Vector lateral = new Vector(-forward.getZ(), 0, forward.getX());
        double sx = field.getSizeX();
        double sz = field.getSizeZ();
        double halfLength = 0.5 * (Math.abs(forward.getX()) * sx + Math.abs(forward.getZ()) * sz);
        double halfWidth = 0.5 * (Math.abs(lateral.getX()) * sx + Math.abs(lateral.getZ()) * sz);

        // Main stand = the touchline nearest the spectator spot (or lobby); the
        // teams walk out from there and line up facing it.
        Location reference = arena.getSpectator() != null ? arena.getSpectator() : arena.getLobby();
        double sideSign = -1.0;
        if (reference != null && reference.getWorld() != null
                && reference.getWorld().equals(field.getWorld())) {
            Vector rel = reference.toVector().subtract(fieldCenter.toVector());
            double dot = rel.dot(lateral);
            if (Math.abs(dot) > 0.5) {
                sideSign = Math.signum(dot);
            }
        }
        Vector center = fieldCenter.toVector();
        double groundY = field.getMinY() + 0.05;
        center.setY(groundY);
        return new StadiumFrame(field.getWorld(), center, forward,
                lateral.multiply(sideSign), Math.max(6.0, halfLength), Math.max(4.0, halfWidth), groundY);
    }

    /** World position for pitch coordinates. */
    public Location at(double along, double across, double up) {
        Vector v = center.clone()
                .add(forward.clone().multiply(along))
                .add(side.clone().multiply(across));
        v.setY(groundY + up);
        return v.toLocation(world);
    }

    public double alongOf(Location loc) {
        return loc.toVector().subtract(center).dot(forward);
    }

    public double acrossOf(Location loc) {
        return loc.toVector().subtract(center).dot(side);
    }

    /** Camera pose at {@code eye} looking at {@code target}. */
    public static Location look(Location eye, Location target) {
        Location pose = eye.clone();
        Vector dir = target.toVector().subtract(eye.toVector());
        if (dir.lengthSquared() > 1.0e-6) {
            pose.setDirection(dir);
        }
        return pose;
    }

    public static Location lerp(Location a, Location b, double t) {
        return new Location(a.getWorld(),
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t);
    }

    /** Smooth acceleration and deceleration (smootherstep). */
    public static double ease(double t) {
        double x = Math.max(0.0, Math.min(1.0, t));
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    /** Gentle ease-out — fast start, long settle (crane / push-in). */
    public static double easeOut(double t) {
        double x = Math.max(0.0, Math.min(1.0, t));
        return 1.0 - Math.pow(1.0 - x, 3.0);
    }
}
