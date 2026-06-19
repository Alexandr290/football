package com.ballgame.soccer.championship;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChampSchedulePrompt {

    private enum Mode {
        CUSTOM,
        OPPONENT
    }

    private static final class Pending {
        private final String championshipId;
        private final Mode mode;
        private int step;
        private String teamAName;
        private String teamATeamId;

        private Pending(String championshipId, Mode mode) {
            this.championshipId = championshipId;
            this.mode = mode;
            this.step = 1;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private ChampSchedulePrompt() {
    }

    public static void startCustom(SoccerPlugin plugin, Player player, String championshipId) {
        Pending pending = new Pending(championshipId, Mode.CUSTOM);
        PENDING.put(player.getUniqueId(), pending);
        player.closeInventory();
        plugin.messages().send(player, "champ-schedule-prompt-side-a");
    }

    public static void startWithSideA(SoccerPlugin plugin, Player player, String championshipId,
                                      String teamAName, String teamATeamId) {
        Pending pending = new Pending(championshipId, Mode.OPPONENT);
        pending.teamAName = teamAName;
        pending.teamATeamId = teamATeamId;
        PENDING.put(player.getUniqueId(), pending);
        player.closeInventory();
        plugin.messages().send(player, "champ-schedule-prompt-opponent", "team", teamAName);
    }

    public static void cancel(Player player) {
        PENDING.remove(player.getUniqueId());
    }

    public static boolean hasPending(Player player) {
        return PENDING.containsKey(player.getUniqueId());
    }

    public static boolean handleChat(SoccerPlugin plugin, Player player, String message) {
        Pending pending = PENDING.get(player.getUniqueId());
        if (pending == null) {
            return false;
        }
        if ("cancel".equalsIgnoreCase(message.trim())) {
            PENDING.remove(player.getUniqueId());
            plugin.messages().send(player, "champ-rename-cancelled");
            return true;
        }
        String normalized = ChampionshipManager.normalizeDisplayName(message);
        if (normalized == null) {
            plugin.messages().send(player, "champ-rename-invalid");
            return true;
        }
        Championship championship = plugin.championships().get(pending.championshipId);
        if (championship == null) {
            PENDING.remove(player.getUniqueId());
            plugin.messages().send(player, "champ-not-found");
            return true;
        }
        if (championship.getState() != ChampionshipState.REGISTRATION
                && championship.getState() != ChampionshipState.ACTIVE) {
            PENDING.remove(player.getUniqueId());
            plugin.messages().send(player, "champ-schedule-failed");
            return true;
        }

        if (pending.mode == Mode.CUSTOM) {
            if (pending.step == 1) {
                ChampTeam teamA = plugin.championships().ensureTeamByName(championship, normalized);
                if (teamA == null) {
                    plugin.messages().send(player, "champ-add-team-failed");
                    return true;
                }
                pending.teamAName = teamA.getName();
                pending.teamATeamId = teamA.getId();
                pending.step = 2;
                plugin.messages().send(player, "champ-schedule-prompt-side-b");
                return true;
            }
            PENDING.remove(player.getUniqueId());
            if (normalized.equalsIgnoreCase(pending.teamAName)) {
                plugin.messages().send(player, "champ-schedule-same-team");
                return true;
            }
            finishSchedule(plugin, player, championship, pending.teamATeamId, normalized);
            return true;
        }

        PENDING.remove(player.getUniqueId());
        if (normalized.equalsIgnoreCase(pending.teamAName)) {
            plugin.messages().send(player, "champ-schedule-same-team");
            return true;
        }
        finishSchedule(plugin, player, championship, pending.teamATeamId, normalized);
        return true;
    }

    private static void finishSchedule(SoccerPlugin plugin, Player player, Championship championship,
                                       String teamAId, String teamBName) {
        if (teamAId == null) {
            plugin.messages().send(player, "champ-schedule-failed");
            return;
        }
        ChampTeam teamB = plugin.championships().ensureTeamByName(championship, teamBName);
        if (teamB == null) {
            plugin.messages().send(player, "champ-add-team-failed");
            return;
        }
        ChampFixture[] out = new ChampFixture[1];
        ChampionshipManager.ScheduleMatchResult result = plugin.championships()
                .scheduleMatch(championship, teamAId, teamB.getId(), out);
        ChampTeam teamA = championship.getTeam(teamAId);
        String nameA = teamA != null ? teamA.getName() : "?";
        String nameB = teamB.getName();
        if (result == ChampionshipManager.ScheduleMatchResult.CREATED) {
            plugin.messages().send(player, "champ-schedule-success",
                    "team_a", nameA, "team_b", nameB);
        } else if (result == ChampionshipManager.ScheduleMatchResult.ALREADY_EXISTS) {
            plugin.messages().send(player, "champ-schedule-already",
                    "team_a", nameA, "team_b", nameB);
        } else if (championship.getType() == ChampionshipType.KNOCKOUT
                && championship.getState() == ChampionshipState.ACTIVE) {
            plugin.messages().send(player, "champ-schedule-knockout-active");
        } else {
            plugin.messages().send(player, "champ-schedule-failed");
        }
    }
}
