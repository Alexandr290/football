package com.ballgame.soccer.history;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
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
import java.util.logging.Level;

/** Loads and persists finished match records to {@code matches.yml}. */
public final class MatchHistoryManager {

    private static final int MAX_STORED = 200;

    private final SoccerPlugin plugin;
    private final File file;
    private final Map<String, MatchRecord> cache = new ConcurrentHashMap<>();

    public MatchHistoryManager(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "matches.yml");
    }

    public void load() {
        cache.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("matches");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                cache.put(key, loadRecord(key, s));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Bad match record: " + key, ex);
            }
        }
        plugin.getLogger().info("Loaded " + cache.size() + " match record(s).");
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        ConfigurationSection root = config.createSection("matches");
        List<MatchRecord> sorted = new ArrayList<>(cache.values());
        sorted.sort(Comparator.comparingLong(MatchRecord::getFinishedAt).reversed());
        int limit = Math.min(sorted.size(), MAX_STORED);
        for (int i = 0; i < limit; i++) {
            MatchRecord record = sorted.get(i);
            saveRecord(root.createSection(record.getId()), record);
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save matches.yml", ex);
        }
    }

    public void recordFinishedMatch(Game game, TeamSide winner) {
        String id = UUID.randomUUID().toString();
        long finishedAt = System.currentTimeMillis();
        String arenaName = game.getArena().getName();

        long totalPoss = game.getRedPossessionTicks() + game.getBluePossessionTicks();
        int redPossPct = possessionPct(game.getRedPossessionTicks(), totalPoss);
        int bluePossPct = totalPoss == 0 ? 0 : 100 - redPossPct;

        List<ParticipantRecord> participants = new ArrayList<>();
        int redShots = 0;
        int blueShots = 0;
        int redPasses = 0;
        int bluePasses = 0;

        for (SoccerPlayer sp : game.getAllParticipants()) {
            if (sp.isReferee()) {
                continue;
            }
            int possPct = possessionPct(sp.getPossessionTicks(), totalPoss);
            double rating = sp.matchRating(totalPoss);
            String team = sp.getTeam() == TeamSide.RED ? "RED" : "BLUE";
            participants.add(new ParticipantRecord(
                    sp.getUuid(), sp.getName(), team, sp.isBot(),
                    sp.getGoals(), sp.getAssists(), sp.getShots(), sp.getFouls(), possPct,
                    sp.getPasses(), sp.getPassesCompleted(), sp.getPassesReceived(),
                    sp.passAccuracyPercent(), rating));

            if (sp.getTeam() == TeamSide.RED) {
                redShots += sp.getShots();
                redPasses += sp.getPasses();
            } else {
                blueShots += sp.getShots();
                bluePasses += sp.getPasses();
            }
        }

        String winnerKey = winner == null ? "DRAW" : winner.name();
        MatchRecord record = new MatchRecord(
                id, finishedAt, arenaName,
                game.getRedScore(), game.getBlueScore(), winnerKey,
                redPossPct, bluePossPct,
                redShots, blueShots, game.getRedScore(), game.getBlueScore(),
                game.getRedPenaltyFouls(), game.getBluePenaltyFouls(),
                redPasses, bluePasses, participants);
        cache.put(id, record);
        trimIfNeeded();
        save();
    }

    public List<MatchRecord> listRecent() {
        List<MatchRecord> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparingLong(MatchRecord::getFinishedAt).reversed());
        return list;
    }

    public MatchRecord get(String id) {
        return cache.get(id);
    }

    private void trimIfNeeded() {
        if (cache.size() <= MAX_STORED) {
            return;
        }
        List<MatchRecord> sorted = listRecent();
        for (int i = MAX_STORED; i < sorted.size(); i++) {
            cache.remove(sorted.get(i).getId());
        }
    }

    private static int possessionPct(long ticks, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round((double) ticks / total * 100.0);
    }

    private static MatchRecord loadRecord(String id, ConfigurationSection s) {
        List<ParticipantRecord> participants = new ArrayList<>();
        ConfigurationSection players = s.getConfigurationSection("participants");
        if (players != null) {
            for (String pKey : players.getKeys(false)) {
                ConfigurationSection p = players.getConfigurationSection(pKey);
                if (p == null) {
                    continue;
                }
                participants.add(new ParticipantRecord(
                        UUID.fromString(pKey),
                        p.getString("name", "?"),
                        p.getString("team", "RED"),
                        p.getBoolean("bot", false),
                        p.getInt("goals", 0),
                        p.getInt("assists", 0),
                        p.getInt("shots", 0),
                        p.getInt("fouls", 0),
                        p.getInt("possession-pct", 0),
                        p.getInt("passes", 0),
                        p.getInt("passes-completed", 0),
                        p.getInt("passes-received", 0),
                        p.getInt("pass-accuracy-pct", 0),
                        p.getDouble("rating", 5.0)));
            }
        }
        return new MatchRecord(
                id,
                s.getLong("finished-at", 0L),
                s.getString("arena", "?"),
                s.getInt("red-score", 0),
                s.getInt("blue-score", 0),
                s.getString("winner", "DRAW"),
                s.getInt("red-possession-pct", 0),
                s.getInt("blue-possession-pct", 0),
                s.getInt("red-shots", 0),
                s.getInt("blue-shots", 0),
                s.getInt("red-goals", 0),
                s.getInt("blue-goals", 0),
                s.getInt("red-fouls", 0),
                s.getInt("blue-fouls", 0),
                s.getInt("red-passes", 0),
                s.getInt("blue-passes", 0),
                participants);
    }

    private static void saveRecord(ConfigurationSection s, MatchRecord record) {
        s.set("finished-at", record.getFinishedAt());
        s.set("arena", record.getArenaName());
        s.set("red-score", record.getRedScore());
        s.set("blue-score", record.getBlueScore());
        s.set("winner", record.getWinner());
        s.set("red-possession-pct", record.getRedPossessionPct());
        s.set("blue-possession-pct", record.getBluePossessionPct());
        s.set("red-shots", record.getRedShots());
        s.set("blue-shots", record.getBlueShots());
        s.set("red-goals", record.getRedGoals());
        s.set("blue-goals", record.getBlueGoals());
        s.set("red-fouls", record.getRedFouls());
        s.set("blue-fouls", record.getBlueFouls());
        s.set("red-passes", record.getRedPasses());
        s.set("blue-passes", record.getBluePasses());

        ConfigurationSection players = s.createSection("participants");
        for (ParticipantRecord p : record.getParticipants()) {
            ConfigurationSection ps = players.createSection(p.getUuid().toString());
            ps.set("name", p.getName());
            ps.set("team", p.getTeam());
            ps.set("bot", p.isBot());
            ps.set("goals", p.getGoals());
            ps.set("assists", p.getAssists());
            ps.set("shots", p.getShots());
            ps.set("fouls", p.getFouls());
            ps.set("possession-pct", p.getPossessionPct());
            ps.set("passes", p.getPasses());
            ps.set("passes-completed", p.getPassesCompleted());
            ps.set("passes-received", p.getPassesReceived());
            ps.set("pass-accuracy-pct", p.getPassAccuracyPct());
            ps.set("rating", p.getRating());
        }
    }
}
