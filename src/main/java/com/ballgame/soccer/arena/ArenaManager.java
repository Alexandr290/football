package com.ballgame.soccer.arena;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Owns the lifecycle of every {@link Arena}: creation, lookup, deletion and
 * persistence to {@code arenas.yml}.
 */
public final class ArenaManager {

    private final SoccerPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final File file;

    public ArenaManager(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        arenas.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("arenas");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                Arena arena = Arena.load(key, section);
                arenas.put(key.toLowerCase(Locale.ROOT), arena);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to load arena '" + key + "'", ex);
            }
        }
        plugin.getLogger().info("Loaded " + arenas.size() + " arena(s).");
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        ConfigurationSection root = config.createSection("arenas");
        for (Arena arena : arenas.values()) {
            arena.save(root.createSection(arena.getName()));
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder.");
            }
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save arenas.yml", ex);
        }
    }

    public Arena create(String name) {
        if (arenas.containsKey(name.toLowerCase(Locale.ROOT))) {
            return null;
        }
        Arena arena = new Arena(name);
        arenas.put(name.toLowerCase(Locale.ROOT), arena);
        return arena;
    }

    public boolean delete(String name) {
        return arenas.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public Arena get(String name) {
        if (name == null) {
            return null;
        }
        return arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String name) {
        return name != null && arenas.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Arena> getArenas() {
        return arenas.values();
    }

    public List<String> getArenaNames() {
        List<String> names = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            names.add(arena.getName());
        }
        return names;
    }
}
