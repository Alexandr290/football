package com.ballgame.soccer.fifa;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

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
import java.util.Objects;
import java.util.logging.Level;

/**
 * Catalog of real-world clubs. Bundled entries are loaded first; valid entries
 * in plugins/AdvancedSoccer/fifa/clubs.yml replace matching bundled clubs.
 */
public final class FifaClubCatalog {

    private static final String RESOURCE_PATH = "fifa/clubs.yml";

    private final Plugin plugin;
    private volatile Map<String, FifaClub> clubs = Collections.emptyMap();
    private volatile Map<String, FifaLeague> leagues = Collections.emptyMap();

    public FifaClubCatalog(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void load() {
        reload();
    }

    public synchronized void reload() {
        LinkedHashMap<String, FifaClub> loaded = new LinkedHashMap<>();
        YamlConfiguration bundled = loadBundled();
        if (bundled != null) {
            readClubs(bundled, loaded, "bundled " + RESOURCE_PATH);
        }

        File external = new File(plugin.getDataFolder(), RESOURCE_PATH);
        if (external.isFile()) {
            try {
                YamlConfiguration override = YamlConfiguration.loadConfiguration(external);
                readClubs(override, loaded, external.getPath());
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not read club catalog override; bundled clubs remain active", ex);
            }
        }

        LinkedHashMap<String, FifaLeague> leagueIndex = new LinkedHashMap<>();
        for (FifaClub club : loaded.values()) {
            leagueIndex.putIfAbsent(club.getLeagueCode(), club.getLeague());
        }
        clubs = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        leagues = Collections.unmodifiableMap(leagueIndex);
        plugin.getLogger().info("Loaded FIFA club catalog: " + clubs.size()
                + " clubs in " + leagues.size() + " leagues");
    }

    private YamlConfiguration loadBundled() {
        try (InputStream input = plugin.getResource(RESOURCE_PATH)) {
            if (input == null) {
                plugin.getLogger().warning("Bundled club catalog is missing: " + RESOURCE_PATH);
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not load bundled club catalog", ex);
            return null;
        }
    }

    private void readClubs(YamlConfiguration yaml, Map<String, FifaClub> destination, String source) {
        ConfigurationSection root = yaml.getConfigurationSection("clubs");
        if (root == null) {
            plugin.getLogger().warning("Club catalog has no 'clubs' section: " + source);
            return;
        }
        for (String rawCode : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(rawCode);
                FifaClub club = section != null ? parseClub(rawCode, section) : null;
                if (club == null) {
                    plugin.getLogger().warning("Ignoring malformed club '" + rawCode + "' in " + source);
                    continue;
                }
                destination.put(club.getCode(), club);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Ignoring malformed club '" + rawCode + "' in " + source, ex);
            }
        }
    }

    private FifaClub parseClub(String rawCode, ConfigurationSection section) {
        String code = normalizedCode(rawCode);
        String name = clean(section.getString("name"));
        String nameRu = clean(section.getString("name-ru"));
        String leagueCode = normalizedCode(section.getString("league-code"));
        String leagueName = clean(section.getString("league-name"));
        String leagueNameRu = clean(section.getString("league-name-ru"));
        String primaryColor = clean(section.getString("primary-color"));
        String secondaryColor = clean(section.getString("secondary-color"));
        if (code == null || name == null || nameRu == null || leagueCode == null
                || leagueName == null || primaryColor == null || secondaryColor == null) {
            return null;
        }

        List<FifaPlayer> squad = new ArrayList<>();
        List<?> entries = section.getList("squad");
        if (entries != null) {
            int defaultNumber = 1;
            for (Object entry : entries) {
                FifaPlayer player = parsePlayer(entry, defaultNumber++);
                if (player != null) {
                    squad.add(player);
                }
            }
        }
        if (!validStartingSquad(squad)) {
            return null;
        }

        FifaLeague league = new FifaLeague(leagueCode, leagueName,
                leagueNameRu != null ? leagueNameRu : leagueName);
        return new FifaClub(code, name, nameRu, league, primaryColor, secondaryColor, squad);
    }

    private FifaPlayer parsePlayer(Object entry, int defaultNumber) {
        if (entry instanceof String text) {
            String[] fields = text.split("\\|", 3);
            if (fields.length < 2) {
                return null;
            }
            String rawPosition = clean(fields[0]);
            String name = clean(fields[1]);
            if (rawPosition == null || name == null || !validPosition(rawPosition)) {
                return null;
            }
            int number = fields.length == 3 ? asInt(fields[2], defaultNumber) : defaultNumber;
            return new FifaPlayer(name, number, FifaPosition.parse(rawPosition), true, null);
        }
        if (entry instanceof Map<?, ?> map) {
            String name = clean(asString(map.get("name")));
            String rawPosition = clean(asString(map.get("pos")));
            if (name == null || rawPosition == null || !validPosition(rawPosition)) {
                return null;
            }
            int number = asInt(map.get("number"), defaultNumber);
            boolean starter = asBoolean(map.get("starter"), true);
            String skin = clean(asString(map.get("skin")));
            return new FifaPlayer(name, number, FifaPosition.parse(rawPosition), starter, skin);
        }
        if (entry instanceof ConfigurationSection player) {
            String name = clean(player.getString("name"));
            String rawPosition = clean(player.getString("pos"));
            if (name == null || rawPosition == null || !validPosition(rawPosition)) {
                return null;
            }
            return new FifaPlayer(name, player.getInt("number", defaultNumber),
                    FifaPosition.parse(rawPosition), player.getBoolean("starter", true),
                    clean(player.getString("skin")));
        }
        return null;
    }

    private static boolean validStartingSquad(List<FifaPlayer> squad) {
        int starters = 0;
        int goalkeepers = 0;
        int outfield = 0;
        for (FifaPlayer player : squad) {
            if (!player.isStarter()) {
                continue;
            }
            starters++;
            if (player.getPosition() == FifaPosition.GK) {
                goalkeepers++;
            } else {
                outfield++;
            }
        }
        return starters >= 11 && goalkeepers == 1 && outfield >= 10;
    }

    private static boolean validPosition(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return value.equals("GK") || value.equals("DF") || value.equals("MF") || value.equals("FW");
    }

    private static String normalizedCode(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        return value.matches("[A-Z0-9_]{2,16}") ? value : null;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value).trim()) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null ? Boolean.parseBoolean(String.valueOf(value)) : fallback;
    }

    public FifaClub get(String code) {
        String normalized = normalizedCode(code);
        return normalized != null ? clubs.get(normalized) : null;
    }

    public Collection<FifaClub> all() {
        return clubs.values();
    }

    public Collection<FifaLeague> leagues() {
        return leagues.values();
    }

    public List<FifaClub> clubsInLeague(String leagueCode) {
        String normalized = normalizedCode(leagueCode);
        if (normalized == null) {
            return Collections.emptyList();
        }
        List<FifaClub> result = new ArrayList<>();
        for (FifaClub club : clubs.values()) {
            if (club.getLeagueCode().equals(normalized)) {
                result.add(club);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<FifaClub> clubsInLeague(FifaLeague league) {
        return league != null ? clubsInLeague(league.getCode()) : Collections.emptyList();
    }

    public int size() {
        return clubs.size();
    }

    public boolean isEmpty() {
        return clubs.isEmpty();
    }
}
