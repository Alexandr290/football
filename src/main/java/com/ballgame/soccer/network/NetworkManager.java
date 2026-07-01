package com.ballgame.soccer.network;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.gui.QuickJoinModeMenu;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.logging.Level;

/**
 * Cross-server soccer flow for BungeeCord networks.
 * <p>
 * Lobby backends forward join requests and connect players to the game backend.
 * The game backend sends players back to the lobby when they leave or a match ends.
 */
public final class NetworkManager {

    private final SoccerPlugin plugin;
    private final PendingJoinStore pendingJoins;
    private final BungeeCordConnector bungee;

    private NetworkRole role = NetworkRole.STANDALONE;
    private boolean enabled;
    private String gameServer = "soccer";
    private String lobbyServer = "lobby";
    private int pendingJoinTimeoutSeconds = 45;
    private boolean returnToLobbyOnLeave = true;
    private boolean returnToLobbyOnMatchEnd = true;

    public NetworkManager(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.pendingJoins = new PendingJoinStore(plugin);
        this.bungee = new BungeeCordConnector(plugin);
    }

    public void reload(SoccerSettings settings) {
        enabled = settings.networkEnabled;
        role = settings.networkRole;
        gameServer = settings.networkGameServer;
        lobbyServer = settings.networkLobbyServer;
        pendingJoinTimeoutSeconds = settings.networkPendingJoinTimeoutSeconds;
        returnToLobbyOnLeave = settings.networkReturnToLobbyOnLeave;
        returnToLobbyOnMatchEnd = settings.networkReturnToLobbyOnMatchEnd;
    }

    public void start() {
        bungee.registerOutgoing();
        if (enabled && role == NetworkRole.GAME) {
            if (!plugin.getServer().getMessenger().isIncomingChannelRegistered(plugin, NetworkJoinRequest.CHANNEL)) {
                plugin.getServer().getMessenger().registerIncomingPluginChannel(
                        plugin, NetworkJoinRequest.CHANNEL, new NetworkJoinListener(plugin));
            }
        }
        if (enabled) {
            plugin.getLogger().info("Network mode enabled — role=" + role
                    + (role == NetworkRole.LOBBY ? ", game-server=" + gameServer : "")
                    + (role == NetworkRole.GAME ? ", lobby-server=" + lobbyServer : ""));
        }
    }

    public void stop() {
        pendingJoins.clear();
        if (plugin.getServer().getMessenger().isIncomingChannelRegistered(plugin, NetworkJoinRequest.CHANNEL)) {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                    plugin, NetworkJoinRequest.CHANNEL);
        }
        bungee.unregisterOutgoing();
    }

    public boolean isLobby() {
        return enabled && role == NetworkRole.LOBBY;
    }

    public boolean isGame() {
        return enabled && role == NetworkRole.GAME;
    }

    public boolean redirectQuickJoin(Player player, TeamSide preferredTeam) {
        if (!isLobby()) {
            return false;
        }

        NetworkJoinRequest request;
        if (preferredTeam != null) {
            request = NetworkJoinRequest.joinTeams(player.getUniqueId(), preferredTeam);
        } else if (plugin.settings().quickjoinTeamPick) {
            request = NetworkJoinRequest.openModeMenu(player.getUniqueId());
        } else {
            request = NetworkJoinRequest.joinTeams(player.getUniqueId(), null);
        }
        return transferToGameServer(player, request);
    }

    public boolean redirectJoin(Player player, String arenaName, TeamSide team) {
        if (!isLobby()) {
            return false;
        }
        if (arenaName == null || arenaName.isBlank()) {
            plugin.messages().send(player, "network-misconfigured");
            return true;
        }
        return transferToGameServer(player, NetworkJoinRequest.joinArena(player.getUniqueId(), arenaName, team));
    }

    public void returnToLobbyAfterLeave(Player player) {
        if (!isGame() || !returnToLobbyOnLeave || player == null || !player.isOnline()) {
            return;
        }
        connectToLobby(player);
    }

    /** Called when a player joins a new match so stale end-of-match routing cannot grab them. */
    public void clearLobbyReturn(Player player) {
        // Lobby return eligibility is tied to GameManager#getGame; joining updates that mapping.
    }

    public void returnToLobbyAfterMatchEnd(Game source, Collection<Player> players) {
        if (!isGame() || !returnToLobbyOnMatchEnd || source == null || players == null) {
            return;
        }
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (plugin.games().getGame(player) != source) {
                continue;
            }
            connectToLobby(player);
        }
    }

    void receiveJoinRequest(byte[] payload) {
        if (!isGame()) {
            return;
        }
        try {
            NetworkJoinRequest request = NetworkJoinRequest.decode(payload);
            pendingJoins.store(request, pendingJoinTimeoutSeconds);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Invalid cross-server join payload.", ex);
        }
    }

    public void applyPendingJoin(Player player) {
        if (!isGame()) {
            return;
        }
        NetworkJoinRequest request = pendingJoins.take(player.getUniqueId());
        if (request == null) {
            return;
        }
        applyJoin(player, request);
    }

    private boolean transferToGameServer(Player player, NetworkJoinRequest request) {
        if (gameServer == null || gameServer.isBlank()) {
            plugin.messages().send(player, "network-misconfigured");
            return true;
        }
        if (!bungee.forwardJoinRequest(player, gameServer, request)) {
            plugin.messages().send(player, "network-unavailable");
            return true;
        }
        if (!bungee.connect(player, gameServer)) {
            plugin.messages().send(player, "network-unavailable");
            return true;
        }
        plugin.messages().send(player, "network-connecting", "server", gameServer);
        return true;
    }

    private void connectToLobby(Player player) {
        if (lobbyServer == null || lobbyServer.isBlank()) {
            return;
        }
        if (!bungee.connect(player, lobbyServer)) {
            plugin.messages().send(player, "network-unavailable");
            return;
        }
        plugin.messages().send(player, "network-returning-lobby", "server", lobbyServer);
    }

    private void applyJoin(Player player, NetworkJoinRequest request) {
        if (request.action() == NetworkJoinAction.OPEN_MODE_MENU) {
            QuickJoinModeMenu.open(plugin, player);
            return;
        }

        if (request.action() == NetworkJoinAction.JOIN_ARENA) {
            Arena arena = plugin.arenas().get(request.arenaName());
            if (arena == null) {
                plugin.messages().send(player, "arena-not-found", "arena", request.arenaName());
                return;
            }
            plugin.games().join(player, arena, request.team());
            return;
        }

        var target = plugin.games().findQuickJoinTarget(request.mode());
        if (target == null) {
            plugin.messages().send(player, "no-arenas-available");
            return;
        }

        boolean joined = request.mode() == QuickJoinMode.SOLO
                ? plugin.games().joinSolo(player, target.arena(), request.team(), request.botCount(), request.teammateCount())
                : plugin.games().join(player, target.arena(), request.team());
        if (!joined && request.team() != null) {
            plugin.messages().send(player, "quickjoin-team-full",
                    "team", request.team() == TeamSide.RED
                            ? plugin.settings().redName
                            : plugin.settings().blueName);
        }
    }
}
