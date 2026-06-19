package com.ballgame.soccer.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Helpers for serializing and deserializing {@link Location}s to and from
 * configuration sections in a compact, human-readable way.
 */
public final class Locations {

    private Locations() {
    }

    public static void save(ConfigurationSection section, String key, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        ConfigurationSection s = section.createSection(key);
        s.set("world", loc.getWorld().getName());
        s.set("x", round(loc.getX()));
        s.set("y", round(loc.getY()));
        s.set("z", round(loc.getZ()));
        s.set("yaw", round(loc.getYaw()));
        s.set("pitch", round(loc.getPitch()));
    }

    public static Location load(ConfigurationSection section, String key) {
        if (section == null || !section.isConfigurationSection(key)) {
            return null;
        }
        ConfigurationSection s = section.getConfigurationSection(key);
        if (s == null) {
            return null;
        }
        String worldName = s.getString("world");
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world,
                s.getDouble("x"),
                s.getDouble("y"),
                s.getDouble("z"),
                (float) s.getDouble("yaw"),
                (float) s.getDouble("pitch"));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
