package com.ballgame.soccer.fifa;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Static FIFA World Cup 2026 national-team catalog (bundled YAML).
 */
public final class FifaCatalog {

    private final SoccerPlugin plugin;
    private final Map<String, FifaNation> nations = new LinkedHashMap<>();

    public FifaCatalog(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        nations.clear();
        YamlConfiguration yaml = loadYaml();
        if (yaml == null) {
            plugin.getLogger().warning("FIFA catalog missing: fifa/nations.yml");
            return;
        }
        ConfigurationSection root = yaml.getConfigurationSection("nations");
        if (root == null) {
            plugin.getLogger().warning("FIFA catalog has no 'nations' section");
            return;
        }
        for (String code : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(code);
            if (section == null) {
                continue;
            }
            FifaNation nation = parseNation(code.toUpperCase(Locale.ROOT), section);
            if (nation != null) {
                nations.put(nation.getCode(), nation);
            }
        }
        plugin.getLogger().info("Loaded FIFA catalog: " + nations.size() + " nations");
    }

    private YamlConfiguration loadYaml() {
        File dataFile = new File(plugin.getDataFolder(), "fifa/nations.yml");
        if (dataFile.isFile()) {
            return YamlConfiguration.loadConfiguration(dataFile);
        }
        try (InputStream in = plugin.getResource("fifa/nations.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to load FIFA catalog", ex);
            return null;
        }
    }

    private FifaNation parseNation(String code, ConfigurationSection section) {
        String name = section.getString("name", code);
        String nameRu = section.getString("name-ru", name);
        String confederation = section.getString("confederation", "");
        int rank = section.getInt("fifa-rank", 99);
        String group = section.getString("group", "?");
        String primary = section.getString("primary-color", "&f");
        String secondary = section.getString("secondary-color", "&7");
        List<FifaPlayer> squad = new ArrayList<>();
        List<?> rawSquad = section.getList("squad");
        if (rawSquad != null) {
            for (Object entry : rawSquad) {
                FifaPlayer player = parsePlayer(entry);
                if (player != null) {
                    squad.add(player);
                }
            }
        }
        if (squad.isEmpty()) {
            squad.addAll(fallbackSquad(name));
        }
        return new FifaNation(code, name, nameRu, confederation, rank, group, primary, secondary, squad);
    }

    @SuppressWarnings("unchecked")
    private FifaPlayer parsePlayer(Object entry) {
        if (entry instanceof Map<?, ?> map) {
            Object nameObj = map.get("name");
            String name = nameObj != null ? String.valueOf(nameObj) : "Player";
            int number = asInt(map.get("number"), 0);
            Object posObj = map.get("pos");
            FifaPosition pos = FifaPosition.parse(posObj != null ? String.valueOf(posObj) : "MF");
            boolean starter = asBool(map.get("starter"), true);
            Object skinObj = map.get("skin");
            String skin = skinObj != null ? String.valueOf(skinObj) : null;
            if (skin != null && (skin.isBlank() || "null".equalsIgnoreCase(skin))) {
                skin = null;
            }
            return new FifaPlayer(name, number, pos, starter, skin);
        }
        if (entry instanceof ConfigurationSection section) {
            return new FifaPlayer(
                    section.getString("name", "Player"),
                    section.getInt("number", 0),
                    FifaPosition.parse(section.getString("pos", "MF")),
                    section.getBoolean("starter", true),
                    section.getString("skin", null));
        }
        return null;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }

    private static List<FifaPlayer> fallbackSquad(String nationName) {
        List<FifaPlayer> list = new ArrayList<>();
        list.add(new FifaPlayer(nationName + " GK", 1, FifaPosition.GK, true, null));
        for (int i = 2; i <= 11; i++) {
            FifaPosition pos = i <= 5 ? FifaPosition.DF : (i <= 8 ? FifaPosition.MF : FifaPosition.FW);
            list.add(new FifaPlayer(nationName + " " + i, i, pos, true, null));
        }
        return list;
    }

    public FifaNation get(String code) {
        if (code == null) {
            return null;
        }
        return nations.get(code.toUpperCase(Locale.ROOT));
    }

    public Collection<FifaNation> all() {
        return Collections.unmodifiableCollection(nations.values());
    }

    public List<FifaNation> sortedByRank() {
        List<FifaNation> list = new ArrayList<>(nations.values());
        list.sort((a, b) -> Integer.compare(a.getFifaRank(), b.getFifaRank()));
        return list;
    }

    public boolean isEmpty() {
        return nations.isEmpty();
    }

    public int size() {
        return nations.size();
    }
}
