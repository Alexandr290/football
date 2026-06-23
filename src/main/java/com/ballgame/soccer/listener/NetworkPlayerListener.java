package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Applies a pending cross-server quick join after the player lands on the game backend. */
public final class NetworkPlayerListener implements Listener {

    private final SoccerPlugin plugin;

    public NetworkPlayerListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.network().isGame()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.network().applyPendingJoin(event.getPlayer()));
    }
}
