package com.ballgame.soccer;

import com.ballgame.soccer.arena.ArenaManager;
import com.ballgame.soccer.command.SoccerCommand;
import com.ballgame.soccer.config.ConfigUpdater;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.championship.ChampionshipManager;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.crowd.CrowdResourcePack;
import com.ballgame.soccer.game.GameManager;
import com.ballgame.soccer.listener.BallProtectionListener;
import com.ballgame.soccer.listener.ChampionshipGuiListener;
import com.ballgame.soccer.listener.CrowdPackListener;
import com.ballgame.soccer.listener.PlayerActionListener;
import com.ballgame.soccer.listener.PlayerConnectionListener;
import com.ballgame.soccer.stats.StatsManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Plugin entry point. Owns and wires together every manager and exposes them via
 * simple accessors so the rest of the code never reaches into Bukkit's plugin
 * registry.
 */
public final class SoccerPlugin extends JavaPlugin {

    private SoccerSettings settings;
    private Messages messages;
    private ArenaManager arenaManager;
    private GameManager gameManager;
    private StatsManager statsManager;
    private ChampionshipManager championshipManager;
    private CrowdResourcePack crowdPack;
    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpdater.updateAll(this);

        this.messages = new Messages(this);
        this.settings = new SoccerSettings(this);
        reloadAll();

        this.arenaManager = new ArenaManager(this);
        this.arenaManager.load();

        this.statsManager = new StatsManager(this);
        this.statsManager.load();

        this.championshipManager = new ChampionshipManager(this);
        this.championshipManager.load();

        this.crowdPack = new CrowdResourcePack(this);
        this.crowdPack.start();

        this.gameManager = new GameManager(this);
        this.gameManager.start();

        registerListeners();
        registerCommands();
        scheduleAutoSave();

        getLogger().info("AdvancedSoccer enabled. Lace up!");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        if (gameManager != null) {
            gameManager.stop();
        }
        if (arenaManager != null) {
            arenaManager.save();
        }
        if (statsManager != null) {
            statsManager.save();
        }
        if (championshipManager != null) {
            championshipManager.save();
        }
        if (crowdPack != null) {
            crowdPack.stop();
        }
        getLogger().info("AdvancedSoccer disabled.");
    }

    public void reloadAll() {
        ConfigUpdater.updateAll(this);
        reloadConfig();
        messages.reload();
        settings.reload();
        if (crowdPack != null) {
            crowdPack.stop();
            crowdPack.start();
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerActionListener(this), this);
        getServer().getPluginManager().registerEvents(new BallProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ChampionshipGuiListener(), this);
        getServer().getPluginManager().registerEvents(new CrowdPackListener(this), this);
    }

    private void registerCommands() {
        SoccerCommand command = new SoccerCommand(this);
        if (getCommand("soccer") != null) {
            getCommand("soccer").setExecutor(command);
            getCommand("soccer").setTabCompleter(command);
        }
    }

    private void scheduleAutoSave() {
        long interval = Math.max(60, settings.autoSaveInterval) * 20L;
        autoSaveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            arenaManager.save();
            statsManager.save();
            if (championshipManager != null) {
                championshipManager.save();
            }
        }, interval, interval);
    }

    public SoccerSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public ArenaManager arenas() {
        return arenaManager;
    }

    public GameManager games() {
        return gameManager;
    }

    public StatsManager stats() {
        return statsManager;
    }

    public ChampionshipManager championships() {
        return championshipManager;
    }

    public CrowdResourcePack crowdPack() {
        return crowdPack;
    }
}
