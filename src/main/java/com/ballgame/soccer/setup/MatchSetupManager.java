package com.ballgame.soccer.setup;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.gui.MatchInviteMenu;
import com.ballgame.soccer.gui.MatchSetupMenu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns setup lobbies, invitations and participant-to-session lookup. */
public final class MatchSetupManager {

    private static final long SESSION_TTL_MS = 30L * 60L * 1000L;

    private final SoccerPlugin plugin;
    private final Map<UUID, MatchSetupSession> byId = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionByPlayer = new LinkedHashMap<>();

    public MatchSetupManager(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public MatchSetupSession create(Player owner) {
        leave(owner.getUniqueId());
        MatchSetupSession session = new MatchSetupSession(owner.getUniqueId());
        byId.put(session.getId(), session);
        sessionByPlayer.put(owner.getUniqueId(), session.getId());
        cleanupExpired();
        return session;
    }

    public MatchSetupSession getFor(UUID playerId) {
        UUID sessionId = sessionByPlayer.get(playerId);
        return sessionId != null ? byId.get(sessionId) : null;
    }

    public MatchSetupSession get(UUID sessionId) {
        return byId.get(sessionId);
    }

    public boolean invite(MatchSetupSession session, Player target) {
        if (session == null || target == null || getFor(target.getUniqueId()) != null
                || plugin.games().getGame(target) != null) {
            return false;
        }
        session.invite(target.getUniqueId());
        MatchInviteMenu.open(plugin, target, session.getId());
        return true;
    }

    public boolean accept(Player player, UUID sessionId) {
        MatchSetupSession session = byId.get(sessionId);
        if (session == null) {
            return false;
        }
        if (getFor(player.getUniqueId()) == session) {
            MatchSetupMenu.openLobby(plugin, player, session);
            return true;
        }
        leave(player.getUniqueId());
        if (!session.accept(player.getUniqueId())) {
            return false;
        }
        byId.put(session.getId(), session);
        sessionByPlayer.put(player.getUniqueId(), session.getId());
        refreshLobby(session);
        return true;
    }

    public void decline(UUID playerId, UUID sessionId) {
        MatchSetupSession session = byId.get(sessionId);
        if (session != null) {
            session.removePlayer(playerId);
        }
    }

    public void leave(UUID playerId) {
        UUID id = sessionByPlayer.remove(playerId);
        if (id == null) {
            return;
        }
        MatchSetupSession session = byId.get(id);
        if (session == null) {
            return;
        }
        if (session.isOwner(playerId)) {
            close(session);
        } else {
            session.removePlayer(playerId);
            refreshLobby(session);
        }
    }

    public void close(MatchSetupSession session) {
        if (session == null) {
            return;
        }
        byId.remove(session.getId());
        sessionByPlayer.entrySet().removeIf(e -> e.getValue().equals(session.getId()));
    }

    public void refreshLobby(MatchSetupSession session) {
        if (session == null) {
            return;
        }
        for (UUID id : session.getAccepted()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                MatchSetupMenu.openLobby(plugin, player, session);
            }
        }
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (MatchSetupSession session : java.util.List.copyOf(byId.values())) {
            if (now - session.getCreatedAt() > SESSION_TTL_MS) {
                close(session);
            }
        }
    }
}
