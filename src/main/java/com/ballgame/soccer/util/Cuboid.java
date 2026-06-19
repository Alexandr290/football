package com.ballgame.soccer.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

/**
 * An axis-aligned cuboid region defined by two corners in a single world.
 * Used for arena bounds and goal boxes. Immutable.
 */
public final class Cuboid {

    private final World world;
    private final double minX, minY, minZ;
    private final double maxX, maxY, maxZ;

    public Cuboid(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null
                || !a.getWorld().equals(b.getWorld())) {
            throw new IllegalArgumentException("Both corners must be in the same (non-null) world.");
        }
        this.world = a.getWorld();
        this.minX = Math.min(a.getX(), b.getX());
        this.minY = Math.min(a.getY(), b.getY());
        this.minZ = Math.min(a.getZ(), b.getZ());
        this.maxX = Math.max(a.getX(), b.getX());
        this.maxY = Math.max(a.getY(), b.getY());
        this.maxZ = Math.max(a.getZ(), b.getZ());
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        return contains(loc.getX(), loc.getY(), loc.getZ());
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * Horizontal-only containment (ignores the Y axis). Useful for pitch
     * boundaries, where the play area is defined by its footprint and a ball
     * lofted into the air should still count as "in play".
     */
    public boolean containsHorizontally(double x, double z) {
        return x >= minX && x <= maxX
                && z >= minZ && z <= maxZ;
    }

    public Vector contains2DClamp(Vector point) {
        double x = Math.max(minX, Math.min(maxX, point.getX()));
        double y = Math.max(minY, Math.min(maxY, point.getY()));
        double z = Math.max(minZ, Math.min(maxZ, point.getZ()));
        return new Vector(x, y, z);
    }

    public Location getCenter() {
        return new Location(world,
                (minX + maxX) / 2.0,
                (minY + maxY) / 2.0,
                (minZ + maxZ) / 2.0);
    }

    public World getWorld() {
        return world;
    }

    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }

    public double getSizeX() { return maxX - minX; }
    public double getSizeY() { return maxY - minY; }
    public double getSizeZ() { return maxZ - minZ; }

    public void save(ConfigurationSection section) {
        section.set("world", world.getName());
        section.set("min", new double[]{minX, minY, minZ});
        section.set("max", new double[]{maxX, maxY, maxZ});
    }

    public static Cuboid load(ConfigurationSection section, World fallbackWorld) {
        String worldName = section.getString("world");
        World w = worldName != null ? org.bukkit.Bukkit.getWorld(worldName) : fallbackWorld;
        if (w == null) {
            return null;
        }
        java.util.List<Double> min = section.getDoubleList("min");
        java.util.List<Double> max = section.getDoubleList("max");
        if (min.size() < 3 || max.size() < 3) {
            return null;
        }
        Location a = new Location(w, min.get(0), min.get(1), min.get(2));
        Location b = new Location(w, max.get(0), max.get(1), max.get(2));
        return new Cuboid(a, b);
    }
}
