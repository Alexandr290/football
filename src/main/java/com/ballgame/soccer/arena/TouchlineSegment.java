package com.ballgame.soccer.arena;

import com.ballgame.soccer.util.Cuboid;
import com.ballgame.soccer.util.Locations;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

/**
 * A touchline segment marked with pos1/pos2. {@link #inwardX}/{@link #inwardZ}
 * point from the line toward the pitch (field centre at setup time).
 */
public final class TouchlineSegment {

    private final Location from;
    private final Location to;
    private final double inwardX;
    private final double inwardZ;

    public TouchlineSegment(Location from, Location to, double inwardX, double inwardZ) {
        this.from = from.clone();
        this.to = to.clone();
        this.inwardX = inwardX;
        this.inwardZ = inwardZ;
    }

    public static TouchlineSegment fromMarkedPoints(Location pos1, Location pos2, Cuboid field) {
        Location a = pos1.clone();
        Location b = pos2.clone();
        double midX = (a.getX() + b.getX()) * 0.5;
        double midZ = (a.getZ() + b.getZ()) * 0.5;

        double inX;
        double inZ;
        if (field != null) {
            Location center = field.getCenter();
            inX = center.getX() - midX;
            inZ = center.getZ() - midZ;
        } else {
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            inX = -dz;
            inZ = dx;
        }
        double len = Math.hypot(inX, inZ);
        if (len < 1e-6) {
            inX = 0;
            inZ = 1;
        } else {
            inX /= len;
            inZ /= len;
        }
        return new TouchlineSegment(a, b, inX, inZ);
    }

    public Location from() {
        return from.clone();
    }

    public Location to() {
        return to.clone();
    }

    public Vector inward() {
        return new Vector(inwardX, 0, inwardZ);
    }

    public Vector outward() {
        return inward().multiply(-1);
    }

    public Location pointAt(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return new Location(
                from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * t,
                from.getY() + (to.getY() - from.getY()) * t,
                from.getZ() + (to.getZ() - from.getZ()) * t);
    }

    public double length() {
        return Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    public void save(ConfigurationSection section) {
        Locations.save(section, "from", from);
        Locations.save(section, "to", to);
        section.set("inward-x", inwardX);
        section.set("inward-z", inwardZ);
    }

    public static TouchlineSegment load(ConfigurationSection section) {
        Location from = Locations.load(section, "from");
        Location to = Locations.load(section, "to");
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return null;
        }
        if (!from.getWorld().equals(to.getWorld())) {
            return null;
        }
        return new TouchlineSegment(
                from,
                to,
                section.getDouble("inward-x", 0),
                section.getDouble("inward-z", 1));
    }
}
