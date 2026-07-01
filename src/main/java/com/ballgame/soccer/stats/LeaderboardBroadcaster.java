package com.ballgame.soccer.stats;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/** Periodically posts rotating stat leaderboards to global chat. */
public final class LeaderboardBroadcaster {

    private final SoccerPlugin plugin;
    private BukkitTask task;
    private int categoryIndex;

    public LeaderboardBroadcaster(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        SoccerSettings settings = plugin.settings();
        if (settings.leaderboardBroadcastInterval <= 0) {
            return;
        }
        List<LeaderboardCategory> categories = settings.leaderboardBroadcastCategories;
        if (categories.isEmpty()) {
            return;
        }
        long intervalTicks = settings.leaderboardBroadcastInterval * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::broadcastNext, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void restart() {
        start();
    }

    private void broadcastNext() {
        SoccerSettings settings = plugin.settings();
        List<LeaderboardCategory> categories = settings.leaderboardBroadcastCategories;
        if (categories.isEmpty()) {
            return;
        }
        LeaderboardCategory category = categories.get(
                Math.floorMod(categoryIndex++, categories.size()));
        ChatLeaderboard.broadcastCategory(plugin, category, settings.leaderboardEntries);
    }
}
