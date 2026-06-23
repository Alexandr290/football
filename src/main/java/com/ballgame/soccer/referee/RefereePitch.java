package com.ballgame.soccer.referee;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Pitch geometry for referee positioning: goal-to-goal axis, touchline offsets,
 * and clamping inside the field footprint.
 */
final class RefereePitch {

    private static final double INSET = 0.55;

    private final Cuboid field;
    private final double axisX;
    private final double axisZ;
    private final double perpX;
    private final double perpZ;
    private final double originX;
    private final double originZ;
    private final double axisLengthSq;
    private final double floorY;

    private RefereePitch(Cuboid field, Location redGoal, Location blueGoal) {
        this.field = field;
        this.floorY = field.getMinY() + 0.05;

        double dx = blueGoal.getX() - redGoal.getX();
        double dz = blueGoal.getZ() - redGoal.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) {
            axisX = 0;
            axisZ = 1;
        } else {
            axisX = dx / len;
            axisZ = dz / len;
        }
        perpX = -axisZ;
        perpZ = axisX;
        originX = redGoal.getX();
        originZ = redGoal.getZ();
        axisLengthSq = len * len;
    }

    static RefereePitch fromArena(Arena arena) {
        Cuboid field = arena.getField();
        if (field == null) {
            return null;
        }
        Location red = goalCenter(arena, TeamSide.RED);
        Location blue = goalCenter(arena, TeamSide.BLUE);
        if (red == null || blue == null) {
            return null;
        }
        return new RefereePitch(field, red, blue);
    }

    private static Location goalCenter(Arena arena, TeamSide side) {
        Cuboid goal = arena.getGoal(side);
        if (goal == null) {
            return null;
        }
        return goal.getCenter();
    }

    /** 0 = red goal line, 1 = blue goal line. */
    double axisT(Location loc) {
        if (loc == null || axisLengthSq < 1e-4) {
            return 0.5;
        }
        double vx = loc.getX() - originX;
        double vz = loc.getZ() - originZ;
        double t = (vx * axisX + vz * axisZ) / Math.sqrt(axisLengthSq);
        return Math.max(0.0, Math.min(1.0, t));
    }

    Location atAxis(double t, double perpOffset) {
        double along = Math.sqrt(axisLengthSq) * Math.max(0.0, Math.min(1.0, t));
        double x = originX + axisX * along + perpX * perpOffset;
        double z = originZ + axisZ * along + perpZ * perpOffset;
        return clampHorizontal(x, z);
    }

    Location touchlineLeft(double t) {
        return atAxis(t, -touchlineDistance());
    }

    Location touchlineRight(double t) {
        return atAxis(t, touchlineDistance());
    }

    double touchlineDistance() {
        boolean touchlinesOnX = field.getSizeX() >= field.getSizeZ();
        double halfWidth = touchlinesOnX
                ? (field.getMaxZ() - field.getMinZ()) / 2.0
                : (field.getMaxX() - field.getMinX()) / 2.0;
        return Math.max(1.5, halfWidth - INSET);
    }

    Location clampHorizontal(double x, double z) {
        x = Math.max(field.getMinX() + INSET, Math.min(field.getMaxX() - INSET, x));
        z = Math.max(field.getMinZ() + INSET, Math.min(field.getMaxZ() - INSET, z));
        double y = Math.max(floorY, field.getMinY() + 0.05);
        return new Location(field.getWorld(), x, y, z);
    }

    Location clamp(Location loc) {
        if (loc == null) {
            return null;
        }
        return clampHorizontal(loc.getX(), loc.getZ());
    }

    Location lerp(Location from, Location to, double alpha) {
        if (from == null || to == null) {
            return to;
        }
        alpha = Math.max(0.0, Math.min(1.0, alpha));
        double x = from.getX() + (to.getX() - from.getX()) * alpha;
        double z = from.getZ() + (to.getZ() - from.getZ()) * alpha;
        return clampHorizontal(x, z);
    }

    float yawToward(Location from, Location target) {
        if (from == null || target == null) {
            return 0f;
        }
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1e-6) {
            return from.getYaw();
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    float yawAlongAxis(boolean forward) {
        float base = (float) Math.toDegrees(Math.atan2(-axisX, axisZ));
        return forward ? base : base + 180f;
    }

    Vector axisVector() {
        return new Vector(axisX, 0, axisZ);
    }

    Location midfield() {
        return atAxis(0.5, 0);
    }
}
