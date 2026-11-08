package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.gui.MainHubMenu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Tracks which players loaded the stadium crowd resource pack.
 */
public final class CrowdPackListener implements Listener {

    private final SoccerPlugin plugin;

    public CrowdPackListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                plugin.crowdPack().markAccepted(player);
                plugin.messages().send(player, "crowd-pack-loaded");
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.hubListener().giveMenuButton(player);
                    MainHubMenu.open(plugin, player);
                }, 5L);
            }
            case DECLINED -> {
                plugin.crowdPack().markDeclined(player);
                plugin.messages().send(player, "crowd-pack-declined");
                Bukkit.getScheduler().runTask(plugin, () -> MainHubMenu.open(plugin, player));
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                plugin.crowdPack().markDeclined(player);
                plugin.messages().send(player, "crowd-pack-failed");
                Bukkit.getScheduler().runTask(plugin, () -> MainHubMenu.open(plugin, player));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.crowdPack().forget(event.getPlayer());
    }
}
