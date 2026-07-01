package com.ballgame.soccer.stats;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.logging.Level;

/**
 * Loads, caches and persists lifetime {@link PlayerStats} to {@code stats.yml},
 * and produces sorted leaderboards.
 */
public final class StatsManager {

    private final SoccerPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();

    public StatsManager(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public void load() {
        cache.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key);
                PlayerStats stats = new PlayerStats(uuid, s.getString("name", "?"));
                stats.addGames(s.getInt("games", 0));
                stats.addWinsRaw(s.getInt("wins", 0));
                stats.addLossesRaw(s.getInt("losses", 0));
                stats.addDrawsRaw(s.getInt("draws", 0));
                stats.addGoals(s.getInt("goals", 0));
                stats.addAssists(s.getInt("assists", 0));
                stats.addOwnGoals(s.getInt("own-goals", 0));
                stats.addTackles(s.getInt("tackles", 0));
                stats.addSaves(s.getInt("saves", 0));
                stats.addCleanSheetsRaw(s.getInt("clean-sheets", 0));
                cache.put(uuid, stats);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING, "Bad stats entry: " + key, ex);
            }
        }
        plugin.getLogger().info("Loaded stats for " + cache.size() + " player(s).");
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        ConfigurationSection root = config.createSection("players");
        for (PlayerStats stats : cache.values()) {
            ConfigurationSection s = root.createSection(stats.getUuid().toString());
            s.set("name", stats.getName());
            s.set("games", stats.getGamesPlayed());
            s.set("wins", stats.getWins());
            s.set("losses", stats.getLosses());
            s.set("draws", stats.getDraws());
            s.set("goals", stats.getGoals());
            s.set("assists", stats.getAssists());
            s.set("own-goals", stats.getOwnGoals());
            s.set("tackles", stats.getTackles());
            s.set("saves", stats.getSaves());
            s.set("clean-sheets", stats.getCleanSheets());
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save stats.yml", ex);
        }
    }

    public PlayerStats get(UUID uuid, String name) {
        return cache.computeIfAbsent(uuid, id -> new PlayerStats(id, name));
    }

    public PlayerStats peek(UUID uuid) {
        return cache.get(uuid);
    }

    public List<PlayerStats> leaderboard(ToIntFunction<PlayerStats> key, int limit) {
        List<PlayerStats> all = new ArrayList<>();
        for (PlayerStats stats : cache.values()) {
            if (key.applyAsInt(stats) > 0) {
                all.add(stats);
            }
        }
        all.sort(Comparator.comparingInt(key).reversed());
        if (all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    /** Removes one player's lifetime stats from memory and {@code stats.yml}. */
    public boolean reset(UUID uuid) {
        if (cache.remove(uuid) == null) {
            return false;
        }
        save();
        return true;
    }

    /** Clears all stored player stats. Returns how many records were removed. */
    public int resetAll() {
        int count = cache.size();
        cache.clear();
        save();
        return count;
    }
}
