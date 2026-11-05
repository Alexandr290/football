package com.ballgame.soccer.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Merges missing keys from the bundled defaults into the on-disk config and
 * language files without overwriting values the server owner already set.
 * When {@code config-version} in the on-disk file is behind the bundled
 * version, targeted migrations bring forward keys whose bundled defaults changed
 * (only when the server still has the previous default value).
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static void updateAll(JavaPlugin plugin) {
        mergeConfig(plugin);
        mergeLang(plugin, "en");
        mergeLang(plugin, "ru");
    }

    public static void mergeConfig(JavaPlugin plugin) {
        FileConfiguration defaults = loadResourceYaml(plugin, "config.yml");
        if (defaults == null) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "config.yml");
        FileConfiguration current = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;

        int currentVersion = current.getInt("config-version", 0);
        int targetVersion = defaults.getInt("config-version", 0);
        if (currentVersion < targetVersion) {
            applyConfigMigrations(current, currentVersion, targetVersion);
            current.set("config-version", targetVersion);
            changed = true;
        }

        if (mergeSections(defaults, current)) {
            changed = true;
        }

        if (changed) {
            try {
                current.save(file);
                plugin.getLogger().info("config.yml updated (version " + currentVersion + " -> " + targetVersion + ").");
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save merged config.yml", ex);
            }
        }
    }

    /**
     * Apply versioned migrations. Each step only overwrites a key when the
     * on-disk value still matches the old bundled default, so custom tuning is
     * preserved.
     */
    private static void applyConfigMigrations(FileConfiguration config, int from, int to) {
        if (from < 21 && to >= 21) {
            migrateToV21(config);
        }
        if (from < 24 && to >= 24) {
            migrateToV24(config);
        }
    }

    /** Bot squad cap raised to 11 per side. */
    private static void migrateToV24(FileConfiguration config) {
        if (config.getInt("bots.max-solo-count", 10) == 10) {
            config.set("bots.max-solo-count", 11);
        }
    }

    /** Bot squad size raised to 10 per side; team cap raised to fit player + allies. */
    private static void migrateToV21(FileConfiguration config) {
        if (config.getInt("game.max-players-per-team", 5) == 5) {
            config.set("game.max-players-per-team", 11);
        }
        if (config.getInt("bots.max-solo-count", 5) == 5) {
            config.set("bots.max-solo-count", 10);
        }
        if (config.getInt("bots.default-solo-count", 3) == 3) {
            config.set("bots.default-solo-count", 4);
        }
        if (!config.contains("bots.default-teammate-count")) {
            config.set("bots.default-teammate-count", 0);
        }
    }

    public static void mergeLang(JavaPlugin plugin, String code) {
        String resourcePath = "lang/" + code + ".yml";
        FileConfiguration defaults = loadResourceYaml(plugin, resourcePath);
        if (defaults == null) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        FileConfiguration current = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        if (mergeSections(defaults, current)) {
            try {
                current.save(file);
                plugin.getLogger().info("lang/" + code + ".yml updated with new translations.");
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save lang/" + code + ".yml", ex);
            }
        }
    }

    /**
     * Copy keys from {@code defaults} into {@code target} only when missing.
     *
     * @return true if anything was added
     */
    private static boolean mergeSections(ConfigurationSection defaults, ConfigurationSection target) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                changed = true;
                continue;
            }
            if (defaults.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                if (mergeSections(
                        defaults.getConfigurationSection(key),
                        target.getConfigurationSection(key))) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static FileConfiguration loadResourceYaml(JavaPlugin plugin, String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
