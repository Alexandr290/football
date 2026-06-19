package com.ballgame.soccer.championship;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChampRenamePrompt {

    public enum Type {
        CHAMPIONSHIP,
        TEAM
    }

    private record Pending(Type type, String championshipId, String teamId) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private ChampRenamePrompt() {
    }

    public static void startChampionship(SoccerPlugin plugin, Player player, String championshipId) {
        PENDING.put(player.getUniqueId(), new Pending(Type.CHAMPIONSHIP, championshipId, null));
        player.closeInventory();
        plugin.messages().send(player, "champ-rename-prompt-championship");
    }

    public static void startTeam(SoccerPlugin plugin, Player player, String championshipId, String teamId) {
        PENDING.put(player.getUniqueId(), new Pending(Type.TEAM, championshipId, teamId));
        player.closeInventory();
        plugin.messages().send(player, "champ-rename-prompt-team");
    }

    public static void cancel(Player player) {
        PENDING.remove(player.getUniqueId());
    }

    public static boolean hasPending(Player player) {
        return PENDING.containsKey(player.getUniqueId());
    }

    public static boolean handleChat(SoccerPlugin plugin, Player player, String message) {
        Pending pending = PENDING.remove(player.getUniqueId());
        if (pending == null) {
            return false;
        }
        if ("cancel".equalsIgnoreCase(message.trim())) {
            plugin.messages().send(player, "champ-rename-cancelled");
            return true;
        }
        String normalized = ChampionshipManager.normalizeDisplayName(message);
        if (normalized == null) {
            plugin.messages().send(player, "champ-rename-invalid");
            return true;
        }
        if (pending.type() == Type.CHAMPIONSHIP) {
            if (plugin.championships().renameChampionship(pending.championshipId(), normalized)) {
                plugin.messages().send(player, "champ-renamed", "name", normalized);
            } else {
                plugin.messages().send(player, "champ-rename-failed");
            }
            return true;
        }
        Championship championship = plugin.championships().get(pending.championshipId());
        boolean admin = player.hasPermission("soccer.admin");
        if (plugin.championships().renameTeam(championship, pending.teamId(), normalized, admin)) {
            plugin.messages().send(player, "champ-team-renamed", "name", normalized);
        } else {
            plugin.messages().send(player, "champ-team-rename-failed");
        }
        return true;
    }
}
