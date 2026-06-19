package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
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
            }
            case DECLINED -> {
                plugin.crowdPack().markDeclined(player);
                plugin.messages().send(player, "crowd-pack-declined");
            }
            case FAILED_DOWNLOAD -> {
                plugin.crowdPack().markDeclined(player);
                plugin.messages().send(player, "crowd-pack-failed");
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
