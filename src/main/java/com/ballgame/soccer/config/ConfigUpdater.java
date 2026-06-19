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
        if (mergeSections(defaults, current)) {
            try {
                current.save(file);
                plugin.getLogger().info("config.yml updated with new default options.");
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save merged config.yml", ex);
            }
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
