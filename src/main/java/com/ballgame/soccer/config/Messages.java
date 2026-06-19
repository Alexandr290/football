package com.ballgame.soccer.config;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.GameState;
import com.ballgame.soccer.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads localized strings from {@code lang/<code>.yml}, with optional overrides
 * from {@code config.yml} ({@code messages:} and {@code scoreboard:}).
 */
public final class Messages {

    private final SoccerPlugin plugin;
    private final Map<String, String> entries = new HashMap<>();
    private String prefix = "";
    private String scoreboardTitle = "&aSOCCER";
    private List<String> scoreboardLines = Collections.emptyList();
    private String language = "en";

    public Messages(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        entries.clear();
        FileConfiguration config = plugin.getConfig();
        language = config.getString("settings.language", "en").toLowerCase(Locale.ROOT);

        mergeLocale("en", true);
        if (!"en".equals(language)) {
            mergeLocale(language, false);
        }

        prefix = Text.color(entries.getOrDefault("prefix",
                config.getString("settings.prefix", "")));
        if (config.contains("settings.prefix")) {
            prefix = Text.color(config.getString("settings.prefix", ""));
        }

        ConfigurationSection messages = config.getConfigurationSection("messages");
        if (messages != null) {
            for (String key : messages.getKeys(false)) {
                entries.put(key, messages.getString(key));
            }
        }
        if (config.contains("scoreboard.title")) {
            scoreboardTitle = config.getString("scoreboard.title", scoreboardTitle);
        }
        List<String> configLines = config.getStringList("scoreboard.lines");
        if (!configLines.isEmpty()) {
            scoreboardLines = new ArrayList<>(configLines);
        }
    }

    private void mergeLocale(String code, boolean fallbackOnly) {
        FileConfiguration yaml = loadYaml(code);
        if (yaml == null) {
            return;
        }
        if (yaml.contains("prefix")) {
            putEntry("prefix", yaml.getString("prefix"), fallbackOnly);
        }
        ConfigurationSection messages = yaml.getConfigurationSection("messages");
        if (messages != null) {
            for (String key : messages.getKeys(false)) {
                putEntry(key, messages.getString(key), fallbackOnly);
            }
        }
        if (!fallbackOnly) {
            if (yaml.contains("scoreboard.title")) {
                scoreboardTitle = yaml.getString("scoreboard.title", scoreboardTitle);
            }
            if (yaml.isList("scoreboard.lines")) {
                scoreboardLines = new ArrayList<>(yaml.getStringList("scoreboard.lines"));
            }
        } else if (scoreboardLines.isEmpty() && yaml.isList("scoreboard.lines")) {
            scoreboardLines = new ArrayList<>(yaml.getStringList("scoreboard.lines"));
            scoreboardTitle = yaml.getString("scoreboard.title", scoreboardTitle);
        }
    }

    private void putEntry(String key, String value, boolean fallbackOnly) {
        if (fallbackOnly) {
            entries.putIfAbsent(key, value);
        } else {
            entries.put(key, value);
        }
    }

    private FileConfiguration loadYaml(String code) {
        File external = new File(new File(plugin.getDataFolder(), "lang"), code + ".yml");
        if (external.exists()) {
            return YamlConfiguration.loadConfiguration(external);
        }
        InputStream stream = plugin.getResource("lang/" + code + ".yml");
        if (stream == null) {
            if (!"en".equals(code)) {
                plugin.getLogger().warning("Language '" + code + "' not found, using English.");
            }
            return null;
        }
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    public String getLanguage() {
        return language;
    }

    public String prefix() {
        return prefix;
    }

    public String scoreboardTitle() {
        return scoreboardTitle;
    }

    public List<String> scoreboardLines() {
        return scoreboardLines;
    }

    public String raw(String key) {
        String value = entries.get(key);
        if (value != null) {
            return value;
        }
        plugin.getLogger().log(Level.FINE, "Missing message key: " + key);
        return "&cMissing message: " + key;
    }

    public String format(String key, Object... placeholders) {
        return Text.color(applyPlaceholders(raw(key), placeholders));
    }

    public String stateDisplay(GameState state) {
        return Text.color(raw("state-" + state.name().toLowerCase(Locale.ROOT)));
    }

    public void send(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(prefix + format(key, placeholders));
    }

    public void sendAll(java.util.Collection<? extends CommandSender> recipients,
                        String key, Object... placeholders) {
        String message = prefix + format(key, placeholders);
        for (CommandSender sender : recipients) {
            if (sender != null) {
                sender.sendMessage(message);
            }
        }
    }

    public void sendRaw(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(prefix + Text.color(message));
    }

    private String applyPlaceholders(String message, Object... placeholders) {
        if (placeholders.length == 0) {
            return message;
        }
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be key/value pairs.");
        }
        String result = message;
        for (int i = 0; i < placeholders.length; i += 2) {
            result = result.replace(
                    "{" + placeholders[i] + "}",
                    String.valueOf(placeholders[i + 1]));
        }
        return result;
    }
}
