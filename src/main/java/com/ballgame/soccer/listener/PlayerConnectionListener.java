package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.gui.MainHubMenu;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cleanly removes players from their match when they disconnect, restoring their
 * saved inventory/state so nothing is lost.
 */
public final class PlayerConnectionListener implements Listener {

    private final SoccerPlugin plugin;

    public PlayerConnectionListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.games().syncServerViewers();
            if (!event.getPlayer().isOnline()) {
                return;
            }
            plugin.hubListener().giveMenuButton(event.getPlayer());
            MainHubMenu.open(plugin, event.getPlayer());
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.matchSetup().leave(event.getPlayer().getUniqueId());
        Game game = plugin.games().getGame(event.getPlayer());
        if (game != null) {
            game.removePlayer(event.getPlayer(), true);
            plugin.games().forget(event.getPlayer());
        }
    }
}
