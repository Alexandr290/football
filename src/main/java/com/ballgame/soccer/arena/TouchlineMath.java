package com.ballgame.soccer.arena;

import org.bukkit.Location;

/** 2D segment math for custom touchline crossing and throw-in confinement. */
public final class TouchlineMath {

    private TouchlineMath() {
    }

    /**
     * Returns {@code t} in {@code [0,1]} along the path {@code prev -> curr} where
     * the path crosses the segment, or {@code null} if it does not cross.
     */
    public static Double pathCrossesSegment(Location prev, Location curr, TouchlineSegment segment) {
        if (prev == null || curr == null || segment == null) {
            return null;
        }
        if (prev.getWorld() == null || curr.getWorld() == null
                || !prev.getWorld().equals(curr.getWorld())) {
            return null;
        }
        Location a = segment.from();
        Location b = segment.to();
        return segmentIntersect(
                prev.getX(), prev.getZ(), curr.getX(), curr.getZ(),
                a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    public static Double segmentIntersect(
            double x1, double z1, double x2, double z2,
            double x3, double z3, double x4, double z4) {
        double d1x = x2 - x1;
        double d1z = z2 - z1;
        double d2x = x4 - x3;
        double d2z = z4 - z3;
        double cross = d1x * d2z - d1z * d2x;
        if (Math.abs(cross) < 1e-10) {
            return null;
        }
        double dx = x3 - x1;
        double dz = z3 - z1;
        double t = (dx * d2z - dz * d2x) / cross;
        double u = (dx * d1z - dz * d1x) / cross;
        if (t >= 0.0 && t <= 1.0 && u >= 0.0 && u <= 1.0) {
            return t;
        }
        return null;
    }

    public static Location interpolate(Location from, Location to, double t) {
        return new Location(
                from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * t,
                from.getY() + (to.getY() - from.getY()) * t,
                from.getZ() + (to.getZ() - from.getZ()) * t);
    }

    /** Parameter {@code t} in {@code [0,1]} of {@code point} projected onto {@code a -> b}. */
    public static double projectParam(Location point, Location a, Location b) {
        if (point == null || a == null || b == null) {
            return 0.5;
        }
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1e-8) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0,
                ((point.getX() - a.getX()) * dx + (point.getZ() - a.getZ()) * dz) / lenSq));
    }

    public static double dotXZ(Location from, Location to, double vx, double vz) {
        return (to.getX() - from.getX()) * vx + (to.getZ() - from.getZ()) * vz;
    }
}
