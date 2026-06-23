package com.ballgame.soccer.network;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;

/** Sends BungeeCord Connect / Forward plugin messages. */
final class BungeeCordConnector {

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final SoccerPlugin plugin;

    BungeeCordConnector(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    void registerOutgoing() {
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, BUNGEE_CHANNEL)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        }
    }

    void unregisterOutgoing() {
        if (plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, BUNGEE_CHANNEL)) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        }
    }

    boolean forwardJoinRequest(Player player, String targetServer, NetworkJoinRequest request) {
        try {
            byte[] payload = request.encode();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Forward");
            out.writeUTF(targetServer);
            out.writeUTF(NetworkJoinRequest.CHANNEL);
            out.writeShort(payload.length);
            out.write(payload);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to forward quick-join request.", ex);
            return false;
        }
    }

    boolean connect(Player player, String targetServer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(targetServer);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to connect player via BungeeCord.", ex);
            return false;
        }
    }
}
