package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
    public void onQuit(PlayerQuitEvent event) {
        Game game = plugin.games().getGame(event.getPlayer());
        if (game != null) {
            game.removePlayer(event.getPlayer(), true);
            plugin.games().forget(event.getPlayer());
        }
    }
}
