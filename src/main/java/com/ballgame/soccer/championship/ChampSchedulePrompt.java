package com.ballgame.soccer.championship;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.gui.ChampScheduleArenaMenu;
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
            if (normalized.equalsIgnoreCase(pending.teamAName)) {
                plugin.messages().send(player, "champ-schedule-same-team");
                return true;
            }
            ChampTeam teamB = plugin.championships().ensureTeamByName(championship, normalized);
            if (teamB == null) {
                plugin.messages().send(player, "champ-add-team-failed");
                return true;
            }
            openArenaPicker(plugin, player, pending.championshipId, pending.teamATeamId, teamB.getId());
            return true;
        }

        if (normalized.equalsIgnoreCase(pending.teamAName)) {
            plugin.messages().send(player, "champ-schedule-same-team");
            return true;
        }
        ChampTeam teamB = plugin.championships().ensureTeamByName(championship, normalized);
        if (teamB == null) {
            plugin.messages().send(player, "champ-add-team-failed");
            return true;
        }
        openArenaPicker(plugin, player, pending.championshipId, pending.teamATeamId, teamB.getId());
        return true;
    }

    private static void openArenaPicker(SoccerPlugin plugin, Player player, String championshipId,
                                        String teamAId, String teamBId) {
        PENDING.remove(player.getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> ChampScheduleArenaMenu.open(plugin, player, championshipId, teamAId, teamBId), 1L);
    }

    public static void completeSchedule(SoccerPlugin plugin, Player player, String championshipId,
                                        String teamAId, String teamBId, String arenaName) {
        Championship championship = plugin.championships().get(championshipId);
        if (championship == null) {
            plugin.messages().send(player, "champ-not-found");
            return;
        }
        if (teamAId == null || teamBId == null) {
            plugin.messages().send(player, "champ-schedule-failed");
            return;
        }
        ChampTeam teamA = championship.getTeam(teamAId);
        ChampTeam teamB = championship.getTeam(teamBId);
        if (teamA == null || teamB == null) {
            plugin.messages().send(player, "champ-schedule-failed");
            return;
        }
        ChampFixture[] out = new ChampFixture[1];
        ChampionshipManager.ScheduleMatchResult result = plugin.championships()
                .scheduleMatch(championship, teamAId, teamBId, arenaName, out);
        String nameA = teamA.getName();
        String nameB = teamB.getName();
        if (result == ChampionshipManager.ScheduleMatchResult.CREATED) {
            plugin.messages().send(player, "champ-schedule-success",
                    "team_a", nameA, "team_b", nameB, "arena", arenaName);
        } else if (result == ChampionshipManager.ScheduleMatchResult.ALREADY_EXISTS) {
            plugin.messages().send(player, "champ-schedule-already",
                    "team_a", nameA, "team_b", nameB, "arena", arenaName);
        } else if (championship.getType() == ChampionshipType.KNOCKOUT
                && championship.getState() == ChampionshipState.ACTIVE) {
            plugin.messages().send(player, "champ-schedule-knockout-active");
        } else {
            plugin.messages().send(player, "champ-schedule-failed");
        }
    }
}
