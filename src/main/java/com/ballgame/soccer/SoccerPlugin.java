package com.ballgame.soccer;

import com.ballgame.soccer.arena.ArenaManager;
import com.ballgame.soccer.ball.BallOrphanCleanup;
import com.ballgame.soccer.bot.BotNpcDiagnostics;
import com.ballgame.soccer.bot.CitizensSupport;
import com.ballgame.soccer.replay.MannequinSupport;
import com.ballgame.soccer.command.LeaderboardCommand;
import com.ballgame.soccer.command.SoccerCommand;
import com.ballgame.soccer.config.ConfigUpdater;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.championship.ChampionshipManager;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.fifa.FifaCatalog;
import com.ballgame.soccer.crowd.CrowdResourcePack;
import com.ballgame.soccer.game.GameManager;
import com.ballgame.soccer.listener.NetworkPlayerListener;
import com.ballgame.soccer.network.NetworkManager;
import com.ballgame.soccer.listener.BallProtectionListener;
import com.ballgame.soccer.listener.ChampionshipGuiListener;
import com.ballgame.soccer.listener.ChampionshipRenameListener;
import com.ballgame.soccer.listener.CrowdPackListener;
import com.ballgame.soccer.listener.PlayerActionListener;
import com.ballgame.soccer.listener.PlayerRefereeListener;
import com.ballgame.soccer.listener.PlayerConnectionListener;
import com.ballgame.soccer.stats.LeaderboardBroadcaster;
import com.ballgame.soccer.stats.StatsManager;
import com.ballgame.soccer.history.MatchHistoryManager;
import org.bukkit.Bukkit;
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
    private MatchHistoryManager matchHistoryManager;
    private ChampionshipManager championshipManager;
    private FifaCatalog fifaCatalog;
    private CrowdResourcePack crowdPack;
    private NetworkManager networkManager;
    private LeaderboardBroadcaster leaderboardBroadcaster;
    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpdater.updateAll(this);

        this.messages = new Messages(this);
        this.settings = new SoccerSettings(this);
        reloadAll();

        this.networkManager = new NetworkManager(this);
        this.networkManager.reload(settings);
        this.networkManager.start();

        this.arenaManager = new ArenaManager(this);
        this.arenaManager.load();

        this.statsManager = new StatsManager(this);
        this.statsManager.load();

        this.matchHistoryManager = new MatchHistoryManager(this);
        this.matchHistoryManager.load();

        this.fifaCatalog = new FifaCatalog(this);
        this.fifaCatalog.load();

        this.championshipManager = new ChampionshipManager(this);
        this.championshipManager.load();

        this.crowdPack = new CrowdResourcePack(this);
        this.crowdPack.start();

        this.gameManager = new GameManager(this);
        this.gameManager.start();
        scheduleOrphanBallCleanup();

        this.leaderboardBroadcaster = new LeaderboardBroadcaster(this);
        this.leaderboardBroadcaster.start();

        registerListeners();
        registerCommands();
        scheduleAutoSave();

        BotNpcDiagnostics.logAtEnable(getLogger());
        if (settings.botsEnabled) {
            getLogger().info("Bot visual mode: " + settings.botVisualType
                    + " | Citizens=" + CitizensSupport.isAvailable()
                    + " | Mannequin=" + MannequinSupport.isAvailable());
        }

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
        if (leaderboardBroadcaster != null) {
            leaderboardBroadcaster.stop();
        }
        if (arenaManager != null) {
            arenaManager.save();
        }
        if (statsManager != null) {
            statsManager.save();
        }
        if (matchHistoryManager != null) {
            matchHistoryManager.save();
        }
        if (championshipManager != null) {
            championshipManager.save();
        }
        if (crowdPack != null) {
            crowdPack.stop();
        }
        if (networkManager != null) {
            networkManager.stop();
        }
        getLogger().info("AdvancedSoccer disabled.");
    }

    public void reloadAll() {
        ConfigUpdater.updateAll(this);
        reloadConfig();
        messages.reload();
        settings.reload();
        if (networkManager != null) {
            networkManager.reload(settings);
        }
        if (crowdPack != null) {
            crowdPack.stop();
            crowdPack.start();
        }
        if (fifaCatalog != null) {
            fifaCatalog.load();
        }
        if (leaderboardBroadcaster != null) {
            leaderboardBroadcaster.restart();
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerActionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerRefereeListener(this), this);
        getServer().getPluginManager().registerEvents(new BallProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new NetworkPlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ChampionshipGuiListener(), this);
        getServer().getPluginManager().registerEvents(new ChampionshipRenameListener(this), this);
        getServer().getPluginManager().registerEvents(new CrowdPackListener(this), this);
    }

    private void registerCommands() {
        SoccerCommand command = new SoccerCommand(this);
        if (getCommand("soccer") != null) {
            getCommand("soccer").setExecutor(command);
            getCommand("soccer").setTabCompleter(command);
        }
        LeaderboardCommand leaderboardCommand = new LeaderboardCommand(this);
        if (getCommand("leaderboard") != null) {
            getCommand("leaderboard").setExecutor(leaderboardCommand);
            getCommand("leaderboard").setTabCompleter(leaderboardCommand);
        }
    }

    /** Scans configured arenas for stray ball entities left from older builds. */
    private void scheduleOrphanBallCleanup() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int removed = BallOrphanCleanup.cleanupAllArenas(this);
            if (removed > 0) {
                getLogger().info("Removed " + removed + " orphaned soccer ball(s) from arenas.");
            }
            int cameras = com.ballgame.soccer.cinematic.CameraRig.cleanupOrphans(this);
            if (cameras > 0) {
                getLogger().info("Removed " + cameras + " orphaned cinematic camera(s).");
            }
        }, 40L);
    }

    private void scheduleAutoSave() {
        long interval = Math.max(60, settings.autoSaveInterval) * 20L;
        autoSaveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            arenaManager.save();
            statsManager.save();
            if (matchHistoryManager != null) {
                matchHistoryManager.save();
            }
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

    public MatchHistoryManager matchHistory() {
        return matchHistoryManager;
    }

    public ChampionshipManager championships() {
        return championshipManager;
    }

    public FifaCatalog fifaCatalog() {
        return fifaCatalog;
    }

    public CrowdResourcePack crowdPack() {
        return crowdPack;
    }

    public NetworkManager network() {
        return networkManager;
    }
}
