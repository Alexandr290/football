package com.ballgame.soccer.network;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Receives forwarded quick-join payloads from lobby servers via BungeeCord. */
final class NetworkJoinListener implements PluginMessageListener {

    private final SoccerPlugin plugin;

    NetworkJoinListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!NetworkJoinRequest.CHANNEL.equals(channel)) {
            return;
        }
        plugin.network().receiveJoinRequest(message);
    }
}
