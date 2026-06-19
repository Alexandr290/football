package com.ballgame.soccer.championship;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.gui.ChampMenus;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ChampionshipManager {

    private static final int MAX_DISPLAY_NAME_LENGTH = 32;

    private final SoccerPlugin plugin;
    private final File file;
    private final Map<String, Championship> championships = new ConcurrentHashMap<>();
    private int teamIdSeq;
    private int leagueFixtureSeq;
    private int playoffFixtureSeq;

    public ChampionshipManager(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "championships.yml");
    }

    public void load() {
        championships.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        teamIdSeq = config.getInt("meta.team-id-seq", 0);
        leagueFixtureSeq = config.getInt("meta.league-fixture-seq", 0);
        playoffFixtureSeq = config.getInt("meta.playoff-fixture-seq", 0);
        ConfigurationSection root = config.getConfigurationSection("championships");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            Championship loaded = loadChampionship(id, root.getConfigurationSection(id));
            if (loaded != null) {
                championships.put(id.toLowerCase(Locale.ROOT), loaded);
            }
        }
        plugin.getLogger().info("Loaded " + championships.size() + " championship(s).");
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        config.set("meta.team-id-seq", teamIdSeq);
        config.set("meta.league-fixture-seq", leagueFixtureSeq);
        config.set("meta.playoff-fixture-seq", playoffFixtureSeq);
        ConfigurationSection root = config.createSection("championships");
        for (Championship championship : championships.values()) {
            saveChampionship(root.createSection(championship.getId()), championship);
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save championships.yml", ex);
        }
    }

    public Collection<Championship> getAll() {
        return championships.values();
    }

    public Championship get(String id) {
        if (id == null) {
            return null;
        }
        return championships.get(id.toLowerCase(Locale.ROOT));
    }

    public Championship create(String id, String displayName, ChampionshipType type, String arenaName) {
        String key = id.toLowerCase(Locale.ROOT);
        if (championships.containsKey(key)) {
            return null;
        }
        Championship championship = new Championship(
                key,
                displayName,
                type,
                arenaName,
                plugin.settings().champPointsWin,
                plugin.settings().champPointsDraw,
                plugin.settings().champPointsLoss);
        championships.put(key, championship);
        save();
        return championship;
    }

    public boolean delete(String id) {
        if (championships.remove(id.toLowerCase(Locale.ROOT)) != null) {
            save();
            return true;
        }
        return false;
    }

    public boolean renameChampionship(String id, String newName) {
        Championship championship = get(id);
        String trimmed = normalizeDisplayName(newName);
        if (championship == null || trimmed == null) {
            return false;
        }
        championship.setDisplayName(trimmed);
        save();
        return true;
    }

    public boolean renameTeam(Championship championship, String teamId, String newName, boolean admin) {
        if (championship == null) {
            return false;
        }
        String trimmed = normalizeDisplayName(newName);
        if (trimmed == null) {
            return false;
        }
        if (!admin && !championship.isRegistrationOpen()) {
            return false;
        }
        ChampTeam team = championship.getTeam(teamId);
        if (team == null) {
            return false;
        }
        team.setName(trimmed);
        save();
        return true;
    }

    public static String normalizeDisplayName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            return null;
        }
        return trimmed;
    }

    public ChampTeam createTeam(Championship championship, Player captain, String teamName) {
        if (!championship.isRegistrationOpen()) {
            return null;
        }
        if (championship.findTeamOf(captain.getUniqueId()) != null) {
            return null;
        }
        if (championship.teamCount() >= plugin.settings().champMaxTeams) {
            return null;
        }
        String teamId = "team-" + (++teamIdSeq);
        ChampTeam team = new ChampTeam(teamId, teamName, captain.getUniqueId());
        championship.addTeam(team);
        save();
        return team;
    }

    public boolean joinTeam(Championship championship, Player player, String teamId) {
        if (!canJoinTeam(championship)) {
            return false;
        }
        if (championship.findTeamOf(player.getUniqueId()) != null) {
            return false;
        }
        ChampTeam team = championship.getTeam(teamId);
        if (team == null || team.size() >= plugin.settings().maxPlayersPerTeam) {
            return false;
        }
        team.addMember(player.getUniqueId());
        if (team.getCaptain() == null) {
            team.setCaptain(player.getUniqueId());
        }
        save();
        return true;
    }

    public boolean canLeaveTeam(Championship championship) {
        if (championship == null || championship.getState() == ChampionshipState.FINISHED) {
            return false;
        }
        if (championship.isRegistrationOpen()) {
            return true;
        }
        return championship.getType() == ChampionshipType.KNOCKOUT
                && championship.getState() == ChampionshipState.ACTIVE;
    }

    public boolean canJoinTeam(Championship championship) {
        if (championship == null || championship.getState() == ChampionshipState.FINISHED) {
            return false;
        }
        if (championship.isRegistrationOpen()) {
            return true;
        }
        return championship.getType() == ChampionshipType.KNOCKOUT
                && championship.getState() == ChampionshipState.ACTIVE;
    }

    public boolean canJoinFixture(Championship championship, ChampFixture fixture) {
        if (!areTeamsReadyForMatch(championship, fixture)) {
            return false;
        }
        if (isWorldCupGroupFixture(championship, fixture)
                && !isWorldCupGroupCalendarComplete(championship)) {
            return false;
        }
        return true;
    }

    public boolean leaveTeam(Championship championship, Player player) {
        if (!canLeaveTeam(championship)) {
            return false;
        }
        ChampTeam team = championship.findTeamOf(player.getUniqueId());
        if (team == null) {
            return false;
        }
        team.removeMember(player.getUniqueId());
        if (team.getCaptain() != null && player.getUniqueId().equals(team.getCaptain())) {
            if (team.size() > 0) {
                team.setCaptain(team.getMembers().get(0));
            } else {
                team.setCaptain(null);
            }
        }
        save();
        return true;
    }

    public boolean startChampionship(String id) {
        Championship championship = get(id);
        if (championship == null || championship.getState() != ChampionshipState.REGISTRATION) {
            return false;
        }
        if (championship.teamCount() < plugin.settings().champMinTeamsToStart) {
            return false;
        }
        if (championship.getType() == ChampionshipType.LEAGUE) {
            generateMissingLeagueFixtures(championship);
        } else if (!canStartWorldCup(championship)) {
            return false;
        } else {
            startWorldCupGroupPhase(championship);
        }
        championship.setState(ChampionshipState.ACTIVE);
        save();
        broadcastChampionship(championship, plugin.messages().format("champ-started",
                "name", championship.getDisplayName()));
        return true;
    }

    public boolean tryStartChampionship(Player player, String id) {
        Championship championship = get(id);
        if (championship == null) {
            if (player != null) {
                plugin.messages().send(player, "champ-not-found");
            }
            return false;
        }
        if (championship.getState() != ChampionshipState.REGISTRATION) {
            if (player != null) {
                plugin.messages().send(player, "champ-start-not-registration");
            }
            return false;
        }
        if (championship.teamCount() < plugin.settings().champMinTeamsToStart) {
            if (player != null) {
                plugin.messages().send(player, "champ-start-min-teams",
                        "min", String.valueOf(plugin.settings().champMinTeamsToStart));
            }
            return false;
        }
        if (championship.getArenaName() == null || championship.getArenaName().isEmpty()) {
            if (player != null) {
                plugin.messages().send(player, "champ-start-no-arena");
            }
            return false;
        }
        if (championship.getType() == ChampionshipType.KNOCKOUT && !canStartWorldCup(championship)) {
            if (player != null) {
                if (championship.teamCount() != plugin.settings().worldCupTeamSlots()) {
                    plugin.messages().send(player, "champ-start-wc-teams",
                            "required", String.valueOf(plugin.settings().worldCupTeamSlots()));
                } else {
                    plugin.messages().send(player, "champ-start-wc-scheduled",
                            "count", String.valueOf(countWorldCupGroupFixtures(championship)),
                            "required", String.valueOf(worldCupRequiredGroupMatches()));
                }
            }
            return false;
        }
        if (!startChampionship(id)) {
            if (player != null) {
                plugin.messages().send(player, "champ-start-failed");
            }
            return false;
        }
        if (player != null) {
            plugin.messages().send(player, "champ-started-admin", "id", id);
        }
        return true;
    }

    private boolean canStartWorldCup(Championship championship) {
        return championship.getType() == ChampionshipType.KNOCKOUT
                && championship.teamCount() == plugin.settings().worldCupTeamSlots()
                && isWorldCupGroupCalendarComplete(championship);
    }

    public int worldCupRequiredGroupMatches() {
        return plugin.settings().champGroupCount;
    }

    public int countWorldCupGroupFixtures(Championship championship) {
        if (championship == null || championship.getType() != ChampionshipType.KNOCKOUT) {
            return 0;
        }
        int count = 0;
        for (ChampFixture fixture : championship.getFixtures()) {
            if (!fixture.hasBothSides() || !isWorldCupGroupFixture(championship, fixture)) {
                continue;
            }
            count++;
        }
        return count;
    }

    public boolean isWorldCupGroupCalendarComplete(Championship championship) {
        if (championship == null || championship.getType() != ChampionshipType.KNOCKOUT) {
            return true;
        }
        return countWorldCupGroupFixtures(championship) >= worldCupRequiredGroupMatches();
    }

    public boolean isWorldCupGroupFixture(Championship championship, ChampFixture fixture) {
        if (championship == null || fixture == null
                || championship.getType() != ChampionshipType.KNOCKOUT) {
            return false;
        }
        if (fixture.getPlayoffStage() == PlayoffStage.GROUP) {
            return true;
        }
        return championship.getState() == ChampionshipState.REGISTRATION
                && fixture.getRound() == 0
                && fixture.getPlayoffStage() == null;
    }

    private void startWorldCupGroupPhase(Championship championship) {
        syncWorldCupGroupsFromFixtures(championship);
        promoteScheduledToGroupFixtures(championship);
    }

    /**
     * Assigns group A–H from scheduled group matches (each fixture = one group of two teams).
     */
    public void syncWorldCupGroupsFromFixtures(Championship championship) {
        if (championship == null || championship.getType() != ChampionshipType.KNOCKOUT) {
            return;
        }
        List<ChampFixture> groupMatches = new ArrayList<>();
        for (ChampFixture fixture : championship.getFixtures()) {
            if (isWorldCupGroupFixture(championship, fixture) && fixture.hasBothSides()) {
                groupMatches.add(fixture);
            }
        }
        groupMatches.sort(Comparator.comparing(ChampFixture::getId));
        int groupCount = plugin.settings().champGroupCount;
        boolean changed = false;
        for (ChampTeam team : championship.getTeams().values()) {
            if (team.getGroupId() != null) {
                team.setGroupId(null);
                changed = true;
            }
        }
        for (int i = 0; i < groupMatches.size() && i < groupCount; i++) {
            String label = String.valueOf((char) ('A' + i));
            ChampFixture fixture = groupMatches.get(i);
            ChampTeam teamA = championship.getTeam(fixture.getTeamAId());
            ChampTeam teamB = championship.getTeam(fixture.getTeamBId());
            if (teamA != null && !label.equals(teamA.getGroupId())) {
                teamA.setGroupId(label);
                changed = true;
            }
            if (teamB != null && !label.equals(teamB.getGroupId())) {
                teamB.setGroupId(label);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    public ChampFixture findGroupFixture(Championship championship, String groupId) {
        if (championship == null || groupId == null) {
            return null;
        }
        for (ChampFixture fixture : championship.getFixtures()) {
            if (!isWorldCupGroupFixture(championship, fixture) || !fixture.hasBothSides()) {
                continue;
            }
            ChampTeam teamA = championship.getTeam(fixture.getTeamAId());
            ChampTeam teamB = championship.getTeam(fixture.getTeamBId());
            if (teamA != null && groupId.equals(teamA.getGroupId())
                    && teamB != null && groupId.equals(teamB.getGroupId())) {
                return fixture;
            }
        }
        return null;
    }

    public boolean areTeamsReadyForMatch(Championship championship, ChampFixture fixture) {
        if (fixture == null || !fixture.hasBothSides()) {
            return false;
        }
        ChampTeam teamA = championship.getTeam(fixture.getTeamAId());
        ChampTeam teamB = championship.getTeam(fixture.getTeamBId());
        return teamA != null && teamB != null && (teamA.size() >= 1 || teamB.size() >= 1);
    }

    private void promoteScheduledToGroupFixtures(Championship championship) {
        for (ChampFixture fixture : championship.getFixtures()) {
            if (fixture.getRound() == 0
                    && fixture.getPlayoffStage() == null
                    && fixture.hasBothSides()) {
                fixture.setPlayoffStage(PlayoffStage.GROUP);
            }
        }
    }

    private void assignGroups(Championship championship) {
        syncWorldCupGroupsFromFixtures(championship);
    }

    private void generateGroupFixtures(Championship championship) {
        for (String groupId : championship.getGroupIds()) {
            List<ChampTeam> groupTeams = championship.getTeamsInGroup(groupId);
            int round = 1;
            for (int i = 0; i < groupTeams.size(); i++) {
                for (int j = i + 1; j < groupTeams.size(); j++) {
                    ChampFixture fixture = new ChampFixture(
                            "gf-" + (++leagueFixtureSeq), round,
                            groupTeams.get(i).getId(), groupTeams.get(j).getId());
                    fixture.setPlayoffStage(PlayoffStage.GROUP);
                    championship.addFixture(fixture);
                }
            }
        }
    }

    private void tryStartKnockoutFromGroups(Championship championship) {
        if (championship.getType() != ChampionshipType.KNOCKOUT
                || !championship.isGroupPhaseComplete()
                || championship.isPlayoffsActive()) {
            return;
        }
        List<String> qualified = getGroupQualifiers(championship, plugin.settings().champAdvancePerGroup);
        if (qualified.size() < plugin.settings().champGroupCount) {
            return;
        }
        generatePlayoffBracket(championship, qualified, 8);
        championship.setPlayoffsActive(true);
        save();
        broadcastChampionship(championship, plugin.messages().format("champ-knockout-started",
                "name", championship.getDisplayName()));
    }

    private List<String> getGroupQualifiers(Championship championship, int advancePerGroup) {
        List<String> qualified = new ArrayList<>();
        for (String groupId : championship.getGroupIds()) {
            List<ChampStanding> rows = new ArrayList<>();
            for (ChampTeam team : championship.getTeamsInGroup(groupId)) {
                ChampStanding standing = championship.getStanding(team.getId());
                if (standing != null) {
                    rows.add(standing);
                }
            }
            rows.sort(Comparator.comparingInt(ChampStanding::getPoints).reversed()
                    .thenComparingInt(ChampStanding::getGoalDifference).reversed()
                    .thenComparingInt(ChampStanding::getGoalsFor).reversed());
            for (int i = 0; i < rows.size() && i < advancePerGroup; i++) {
                qualified.add(rows.get(i).getTeamId());
            }
        }
        return qualified;
    }

    public ChampTeam findTeamByName(Championship championship, String name) {
        if (name == null) {
            return null;
        }
        for (ChampTeam team : championship.getTeams().values()) {
            if (team.getName().equalsIgnoreCase(name)) {
                return team;
            }
        }
        return null;
    }

    public ChampTeam addTeamByName(Championship championship, String name) {
        if (championship == null || championship.getState() == ChampionshipState.FINISHED) {
            return null;
        }
        if (!championship.isRegistrationOpen()
                && championship.getState() != ChampionshipState.ACTIVE) {
            return null;
        }
        if (championship.getState() == ChampionshipState.ACTIVE
                && championship.getType() != ChampionshipType.LEAGUE) {
            return null;
        }
        String normalized = normalizeDisplayName(name);
        if (normalized == null) {
            return null;
        }
        ChampTeam existing = findTeamByName(championship, normalized);
        if (existing != null) {
            return existing;
        }
        if (championship.teamCount() >= plugin.settings().champMaxTeams) {
            return null;
        }
        String teamId = "team-" + (++teamIdSeq);
        ChampTeam team = new ChampTeam(teamId, normalized, null);
        championship.addTeam(team);
        if (championship.getState() == ChampionshipState.ACTIVE) {
            generateMissingLeagueFixtures(championship);
        }
        save();
        return team;
    }

    public ChampTeam ensureTeamByName(Championship championship, String name) {
        ChampTeam existing = findTeamByName(championship, name);
        if (existing != null) {
            return existing;
        }
        return addTeamByName(championship, name);
    }

    public ChampFixture findFixtureBetween(Championship championship, String teamAId, String teamBId) {
        if (teamAId == null || teamBId == null) {
            return null;
        }
        for (ChampFixture fixture : championship.getFixtures()) {
            if (fixture.getPlayoffStage() != null) {
                continue;
            }
            if (fixture.involvesTeam(teamAId) && fixture.involvesTeam(teamBId)) {
                return fixture;
            }
        }
        return null;
    }

    public enum ScheduleMatchResult {
        CREATED,
        ALREADY_EXISTS,
        FAILED
    }

    public ScheduleMatchResult scheduleMatch(Championship championship,
                                             String teamAId, String teamBId,
                                             ChampFixture[] outFixture) {
        if (outFixture == null || outFixture.length == 0) {
            return ScheduleMatchResult.FAILED;
        }
        outFixture[0] = null;
        if (championship == null || teamAId == null || teamBId == null
                || teamAId.equals(teamBId)) {
            return ScheduleMatchResult.FAILED;
        }
        if (championship.getState() == ChampionshipState.FINISHED) {
            return ScheduleMatchResult.FAILED;
        }
        if (championship.getType() == ChampionshipType.KNOCKOUT
                && championship.getState() == ChampionshipState.ACTIVE) {
            return ScheduleMatchResult.FAILED;
        }
        if (championship.getTeam(teamAId) == null || championship.getTeam(teamBId) == null) {
            return ScheduleMatchResult.FAILED;
        }
        ChampFixture existing = findFixtureBetween(championship, teamAId, teamBId);
        if (existing != null) {
            outFixture[0] = existing;
            return ScheduleMatchResult.ALREADY_EXISTS;
        }
        int round = championship.getState() == ChampionshipState.REGISTRATION ? 0 : 1;
        String id = "f" + (++leagueFixtureSeq);
        ChampFixture fixture = new ChampFixture(id, round, teamAId, teamBId);
        championship.addFixture(fixture);
        outFixture[0] = fixture;
        if (championship.getType() == ChampionshipType.KNOCKOUT) {
            syncWorldCupGroupsFromFixtures(championship);
        } else {
            save();
        }
        return ScheduleMatchResult.CREATED;
    }

    /** @deprecated use {@link #scheduleMatch} */
    public ScheduleMatchResult scheduleLeagueMatch(Championship championship,
                                                   String teamAId, String teamBId,
                                                   ChampFixture[] outFixture) {
        return scheduleMatch(championship, teamAId, teamBId, outFixture);
    }

    private void generateFixtures(Championship championship) {
        // Clear existing fixtures - add method to Championship
        List<ChampTeam> teamList = new ArrayList<>(championship.getTeams().values());
        if (championship.getType() == ChampionshipType.LEAGUE) {
            generateLeagueFixtures(championship, teamList);
        } else {
            generateKnockoutFixtures(championship, teamList);
        }
    }

    private void generateLeagueFixtures(Championship championship, List<ChampTeam> teams) {
        generateMissingLeagueFixtures(championship, teams, 1);
    }

    private void generateMissingLeagueFixtures(Championship championship) {
        generateMissingLeagueFixtures(championship,
                new ArrayList<>(championship.getTeams().values()), 1);
    }

    private void generateMissingLeagueFixtures(Championship championship,
                                               List<ChampTeam> teams, int round) {
        for (int i = 0; i < teams.size(); i++) {
            for (int j = i + 1; j < teams.size(); j++) {
                String teamAId = teams.get(i).getId();
                String teamBId = teams.get(j).getId();
                if (findFixtureBetween(championship, teamAId, teamBId) != null) {
                    continue;
                }
                championship.addFixture(new ChampFixture(
                        "f" + (++leagueFixtureSeq), round, teamAId, teamBId));
            }
        }
    }

    private void generateKnockoutFixtures(Championship championship, List<ChampTeam> teams) {
        List<String> teamIds = new ArrayList<>();
        for (ChampTeam team : teams) {
            teamIds.add(team.getId());
        }
        generatePlayoffBracket(championship, teamIds, 8);
    }

    private void autoAdvanceByes(Championship championship) {
        for (ChampFixture fixture : championship.getFixtures()) {
            if (fixture.getState() == FixtureState.COMPLETED && fixture.getWinnerTeamId() != null) {
                advanceWinnerToNext(championship, fixture);
            }
        }
    }

    public void onMatchFinished(Game game, TeamSide winner) {
        String champId = game.getChampionshipId();
        String fixtureId = game.getFixtureId();
        if (champId == null || fixtureId == null) {
            return;
        }
        Championship championship = get(champId);
        ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;
        if (championship == null || fixture == null) {
            return;
        }
        if (winner == null && (championship.getType() == ChampionshipType.KNOCKOUT
                || fixture.getPlayoffStage() != null)) {
            fixture.setState(FixtureState.PENDING);
            save();
            broadcastChampionship(championship, plugin.messages().raw("champ-draw-rematch"));
            return;
        }
        int red = game.getRedScore();
        int blue = game.getBlueScore();
        completeFixture(championship, fixture, red, blue);
    }

    public void completeFixture(Championship championship, ChampFixture fixture, int scoreA, int scoreB) {
        fixture.setState(FixtureState.COMPLETED);
        fixture.setScores(scoreA, scoreB);

        String winnerId = null;
        if (scoreA > scoreB) {
            winnerId = fixture.getTeamAId();
        } else if (scoreB > scoreA) {
            winnerId = fixture.getTeamBId();
        }
        fixture.setWinnerTeamId(winnerId);

        if (fixture.getPlayoffStage() != null) {
            if (fixture.getPlayoffStage() == PlayoffStage.GROUP) {
                applyLeagueResult(championship, fixture, scoreA, scoreB);
                tryStartKnockoutFromGroups(championship);
            } else {
                applyKnockoutResult(championship, fixture, winnerId);
            }
        } else if (championship.getType() == ChampionshipType.LEAGUE) {
            applyLeagueResult(championship, fixture, scoreA, scoreB);
            tryStartPlayoffs(championship);
        } else {
            applyKnockoutResult(championship, fixture, winnerId);
        }

        if (isChampionshipComplete(championship)) {
            finishChampionship(championship);
        }
        save();
    }

    private void applyLeagueResult(Championship championship, ChampFixture fixture,
                                   int scoreA, int scoreB) {
        ChampStanding a = championship.getStanding(fixture.getTeamAId());
        ChampStanding b = championship.getStanding(fixture.getTeamBId());
        if (a != null) {
            a.applyResult(scoreA, scoreB,
                    championship.getPointsWin(), championship.getPointsDraw(), championship.getPointsLoss());
        }
        if (b != null) {
            b.applyResult(scoreB, scoreA,
                    championship.getPointsWin(), championship.getPointsDraw(), championship.getPointsLoss());
        }
        Messages msg = plugin.messages();
        ChampTeam teamA = championship.getTeam(fixture.getTeamAId());
        ChampTeam teamB = championship.getTeam(fixture.getTeamBId());
        broadcastChampionship(championship, msg.format("champ-fixture-result",
                "team_a", teamA != null ? teamA.getName() : "?",
                "team_b", teamB != null ? teamB.getName() : "?",
                "score_a", String.valueOf(scoreA),
                "score_b", String.valueOf(scoreB)));
    }

    private void applyKnockoutResult(Championship championship, ChampFixture fixture, String winnerId) {
        if (winnerId != null) {
            ChampTeam loser = championship.getTeam(
                    winnerId.equals(fixture.getTeamAId()) ? fixture.getTeamBId() : fixture.getTeamAId());
            if (loser != null) {
                loser.setEliminated(true);
            }
            advanceWinnerToNext(championship, fixture);
        }
        ChampTeam teamA = championship.getTeam(fixture.getTeamAId());
        ChampTeam teamB = championship.getTeam(fixture.getTeamBId());
        broadcastChampionship(championship, plugin.messages().format("champ-fixture-result",
                "team_a", teamA != null ? teamA.getName() : "BYE",
                "team_b", teamB != null ? teamB.getName() : "BYE",
                "score_a", String.valueOf(fixture.getScoreA()),
                "score_b", String.valueOf(fixture.getScoreB())));
    }

    private void advanceWinnerToNext(Championship championship, ChampFixture fixture) {
        if (fixture.getNextFixtureId() == null || fixture.getWinnerTeamId() == null) {
            return;
        }
        ChampFixture next = championship.getFixture(fixture.getNextFixtureId());
        if (next == null) {
            return;
        }
        if (next.getTeamAId() == null) {
            assignTeamToSlot(next, true, fixture.getWinnerTeamId());
        } else if (next.getTeamBId() == null) {
            assignTeamToSlot(next, false, fixture.getWinnerTeamId());
        }
        if (next.getTeamAId() != null && next.getTeamBId() != null
                && next.getState() == FixtureState.PENDING) {
            // both slots filled — ready to play
        }
    }

    private void assignTeamToSlot(ChampFixture fixture, boolean slotA, String teamId) {
        if (slotA) {
            fixture.setTeamAId(teamId);
        } else {
            fixture.setTeamBId(teamId);
        }
    }

    private boolean isChampionshipComplete(Championship championship) {
        if (championship.getType() == ChampionshipType.LEAGUE) {
            if (!isLeaguePhaseComplete(championship)) {
                return false;
            }
            if (shouldStartPlayoffs(championship) && !championship.isPlayoffsActive()) {
                return false;
            }
            if (championship.isPlayoffsActive()) {
                return arePlayoffFixturesComplete(championship);
            }
            return true;
        }
        for (ChampFixture fixture : championship.getFixtures()) {
            if (fixture.getPlayoffStage() == null || fixture.getPlayoffStage() == PlayoffStage.GROUP) {
                continue;
            }
            if (fixture.hasBothSides() && fixture.getState() != FixtureState.COMPLETED) {
                return false;
            }
        }
        if (championship.getType() == ChampionshipType.KNOCKOUT && championship.hasGroupStage()) {
            if (!championship.isGroupPhaseComplete()) {
                return false;
            }
            if (!championship.isPlayoffsActive()) {
                return false;
            }
            return championship.getKnockoutFixtures().stream()
                    .anyMatch(f -> f.getPlayoffStage() == PlayoffStage.FINAL);
        }
        return championship.getFixtures().stream()
                .anyMatch(f -> f.getPlayoffStage() != null && f.getPlayoffStage().isKnockout());
    }

    private boolean isLeaguePhaseComplete(Championship championship) {
        List<ChampFixture> leagueFixtures = championship.getLeagueFixtures();
        if (leagueFixtures.isEmpty()) {
            return false;
        }
        for (ChampFixture fixture : leagueFixtures) {
            if (fixture.getTeamAId() != null && fixture.getTeamBId() != null
                    && fixture.getState() != FixtureState.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldStartPlayoffs(Championship championship) {
        if (!plugin.settings().champPlayoffsEnabled) {
            return false;
        }
        return championship.teamCount() >= plugin.settings().champPlayoffMinTeams;
    }

    private boolean arePlayoffFixturesComplete(Championship championship) {
        for (ChampFixture fixture : championship.getPlayoffFixtures()) {
            if (fixture.hasBothSides() && fixture.getState() != FixtureState.COMPLETED) {
                return false;
            }
        }
        return !championship.getPlayoffFixtures().isEmpty();
    }

    private void tryStartPlayoffs(Championship championship) {
        if (championship.getType() != ChampionshipType.LEAGUE
                || championship.isPlayoffsActive()
                || !shouldStartPlayoffs(championship)
                || !isLeaguePhaseComplete(championship)) {
            return;
        }
        int bracketSize = playoffBracketSize(championship);
        if (bracketSize < 2) {
            return;
        }
        List<String> seeded = getQualifiedTeamIds(championship, bracketSize);
        if (seeded.size() < 2) {
            return;
        }
        generatePlayoffBracket(championship, seeded, bracketSize);
        championship.setPlayoffsActive(true);
        save();
        broadcastChampionship(championship, plugin.messages().format("champ-playoffs-started",
                "name", championship.getDisplayName()));
    }

    private int playoffBracketSize(Championship championship) {
        int requested = Math.min(plugin.settings().champPlayoffQualifiers, championship.teamCount());
        int size = 2;
        while (size < requested && size < championship.teamCount()) {
            size <<= 1;
        }
        return Math.min(size, championship.teamCount());
    }

    private List<String> getQualifiedTeamIds(Championship championship, int limit) {
        List<ChampStanding> rows = new ArrayList<>(championship.getStandings().values());
        rows.sort(Comparator.comparingInt(ChampStanding::getPoints).reversed()
                .thenComparingInt(ChampStanding::getGoalDifference).reversed()
                .thenComparingInt(ChampStanding::getGoalsFor).reversed());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < rows.size() && ids.size() < limit; i++) {
            ids.add(rows.get(i).getTeamId());
        }
        return ids;
    }

    private void generatePlayoffBracket(Championship championship, List<String> seededTeams, int bracketSize) {
        List<String> slots;
        if (bracketSize == 8 && seededTeams.size() >= 8) {
            slots = applyBracketSeeding(seededTeams.subList(0, 8));
        } else {
            slots = new ArrayList<>(seededTeams);
        }
        while (slots.size() < bracketSize) {
            slots.add(null);
        }

        int round = 1;
        List<ChampFixture> roundFixtures = new ArrayList<>();
        for (int i = 0; i < bracketSize; i += 2) {
            String teamA = slots.get(i);
            String teamB = slots.get(i + 1);
            ChampFixture fixture = new ChampFixture("po-" + (++playoffFixtureSeq), round, teamA, teamB);
            fixture.setPlayoffStage(PlayoffStage.fromBracketSize(bracketSize, round));
            completeByeIfNeeded(fixture);
            championship.addFixture(fixture);
            roundFixtures.add(fixture);
        }
        buildPlayoffLinks(championship, roundFixtures, round + 1, bracketSize);
        autoAdvanceByes(championship);
    }

    private List<String> applyBracketSeeding(List<String> seededTeams) {
        int[][] pairs = {{0, 7}, {3, 4}, {1, 6}, {2, 5}};
        List<String> ordered = new ArrayList<>();
        for (int[] pair : pairs) {
            if (pair[0] < seededTeams.size()) {
                ordered.add(seededTeams.get(pair[0]));
            }
            if (pair[1] < seededTeams.size()) {
                ordered.add(seededTeams.get(pair[1]));
            }
        }
        return ordered;
    }

    private void completeByeIfNeeded(ChampFixture fixture) {
        String teamA = fixture.getTeamAId();
        String teamB = fixture.getTeamBId();
        if (teamA != null && teamB != null) {
            return;
        }
        if (teamA == null && teamB == null) {
            return;
        }
        fixture.setState(FixtureState.COMPLETED);
        fixture.setScores(teamA == null ? 0 : 1, teamB == null ? 0 : 1);
        fixture.setWinnerTeamId(teamA != null ? teamA : teamB);
    }

    private void buildPlayoffLinks(Championship championship, List<ChampFixture> currentRound,
                                   int round, int initialBracketSize) {
        if (currentRound.size() <= 1) {
            return;
        }
        List<ChampFixture> nextRound = new ArrayList<>();
        for (int i = 0; i < currentRound.size(); i += 2) {
            ChampFixture next = new ChampFixture("po-" + (++playoffFixtureSeq), round, null, null);
            next.setPlayoffStage(PlayoffStage.fromBracketSize(initialBracketSize, round));
            championship.addFixture(next);
            currentRound.get(i).setNextFixtureId(next.getId());
            if (i + 1 < currentRound.size()) {
                currentRound.get(i + 1).setNextFixtureId(next.getId());
            }
            nextRound.add(next);
        }
        buildPlayoffLinks(championship, nextRound, round + 1, initialBracketSize);
    }

    private void finishChampionship(Championship championship) {
        championship.setState(ChampionshipState.FINISHED);
        String winnerName = resolveChampionName(championship);
        if (winnerName != null) {
            broadcastChampionship(championship, plugin.messages().format("champ-winner",
                    "name", championship.getDisplayName(),
                    "team", winnerName));
        } else {
            broadcastChampionship(championship, plugin.messages().format("champ-finished",
                    "name", championship.getDisplayName()));
        }
    }

    private String resolveChampionName(Championship championship) {
        if (championship.getType() == ChampionshipType.KNOCKOUT) {
            for (ChampFixture fixture : championship.getFixtures()) {
                if (fixture.getPlayoffStage() == PlayoffStage.FINAL
                        && fixture.getState() == FixtureState.COMPLETED
                        && fixture.getWinnerTeamId() != null) {
                    championship.setChampionTeamId(fixture.getWinnerTeamId());
                    ChampTeam team = championship.getTeam(fixture.getWinnerTeamId());
                    return team != null ? team.getName() : null;
                }
            }
            return null;
        } else if (championship.isPlayoffsActive() || championship.hasPlayoffFixtures()) {
            for (ChampFixture fixture : championship.getPlayoffFixtures()) {
                if (fixture.getPlayoffStage() == PlayoffStage.FINAL
                        && fixture.getState() == FixtureState.COMPLETED
                        && fixture.getWinnerTeamId() != null) {
                    championship.setChampionTeamId(fixture.getWinnerTeamId());
                    ChampTeam team = championship.getTeam(fixture.getWinnerTeamId());
                    return team != null ? team.getName() : null;
                }
            }
            return null;
        } else {
            return championship.getStandings().values().stream()
                    .max(Comparator.comparingInt(ChampStanding::getPoints)
                            .thenComparingInt(ChampStanding::getGoalDifference))
                    .map(s -> {
                        championship.setChampionTeamId(s.getTeamId());
                        ChampTeam team = championship.getTeam(s.getTeamId());
                        return team != null ? team.getName() : null;
                    })
                    .orElse(null);
        }
    }

    public boolean joinFixtureMatch(Player player, Championship championship, ChampFixture fixture) {
        if (fixture.getState() == FixtureState.COMPLETED) {
            return false;
        }
        if (championship.getState() != ChampionshipState.ACTIVE
                && championship.getState() != ChampionshipState.REGISTRATION) {
            return false;
        }
        if (!canJoinFixture(championship, fixture)) {
            if (isWorldCupGroupFixture(championship, fixture)
                    && !isWorldCupGroupCalendarComplete(championship)) {
                plugin.messages().send(player, "champ-wc-need-scheduled",
                        "count", String.valueOf(countWorldCupGroupFixtures(championship)),
                        "required", String.valueOf(worldCupRequiredGroupMatches()));
            } else {
                plugin.messages().send(player, "champ-wc-teams-not-ready");
            }
            return false;
        }

        TeamSide side = resolveFixtureSide(player, championship, fixture);
        if (side == null || !fixture.hasBothSides()) {
            return false;
        }
        Arena arena = plugin.arenas().get(championship.getArenaName());
        if (arena == null || !arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        if (fixture.getState() == FixtureState.PENDING) {
            fixture.setState(FixtureState.LIVE);
            save();
        }
        return plugin.games().joinChampionship(
                player, arena, side, championship.getId(), fixture.getId(),
                fixture.sideADisplay(championship), fixture.sideBDisplay(championship));
    }

    private TeamSide resolveFixtureSide(Player player, Championship championship, ChampFixture fixture) {
        UUID playerId = player.getUniqueId();
        if (fixture.getTeamAId() != null) {
            ChampTeam team = championship.getTeam(fixture.getTeamAId());
            if (team != null && team.contains(playerId)) {
                return TeamSide.RED;
            }
        }
        if (fixture.getTeamBId() != null) {
            ChampTeam team = championship.getTeam(fixture.getTeamBId());
            if (team != null && team.contains(playerId)) {
                return TeamSide.BLUE;
            }
        }
        return null;
    }

    public void openMainMenu(Player player) {
        ChampMenus.openMain(plugin, player);
    }

    private void broadcastChampionship(Championship championship, String message) {
        for (ChampTeam team : championship.getTeams().values()) {
            for (UUID memberId : team.getMembers()) {
                Player online = Bukkit.getPlayer(memberId);
                if (online != null) {
                    plugin.messages().sendRaw(online, message);
                }
            }
        }
    }

    // --- persistence helpers ---

    private Championship loadChampionship(String id, ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        ChampionshipType type = ChampionshipType.valueOf(
                section.getString("type", "LEAGUE").toUpperCase(Locale.ROOT));
        Championship championship = new Championship(
                id,
                section.getString("name", id),
                type,
                section.getString("arena", ""),
                section.getInt("points-win", plugin.settings().champPointsWin),
                section.getInt("points-draw", plugin.settings().champPointsDraw),
                section.getInt("points-loss", plugin.settings().champPointsLoss));
        championship.setState(ChampionshipState.valueOf(
                section.getString("state", "REGISTRATION").toUpperCase(Locale.ROOT)));
        championship.setCreatedAt(section.getLong("created-at", System.currentTimeMillis()));
        championship.setChampionTeamId(section.getString("champion-team"));
        championship.setPlayoffsActive(section.getBoolean("playoffs-active", false));

        ConfigurationSection teamsSec = section.getConfigurationSection("teams");
        if (teamsSec != null) {
            for (String teamId : teamsSec.getKeys(false)) {
                ConfigurationSection t = teamsSec.getConfigurationSection(teamId);
                if (t == null) {
                    continue;
                }
                String captainStr = t.getString("captain");
                UUID captain = (captainStr != null && !captainStr.isEmpty())
                        ? UUID.fromString(captainStr) : null;
                ChampTeam team = new ChampTeam(teamId, t.getString("name", teamId), captain);
                team.setEliminated(t.getBoolean("eliminated", false));
                team.setGroupId(t.getString("group"));
                for (String member : t.getStringList("members")) {
                    UUID uuid = UUID.fromString(member);
                    if (!team.contains(uuid)) {
                        team.addMember(uuid);
                    }
                }
                championship.addTeam(team);
            }
        }

        ConfigurationSection standingsSec = section.getConfigurationSection("standings");
        if (standingsSec != null) {
            for (String teamId : standingsSec.getKeys(false)) {
                ConfigurationSection s = standingsSec.getConfigurationSection(teamId);
                if (s == null) {
                    continue;
                }
                ChampStanding standing = new ChampStanding(teamId);
                standing.load(
                        s.getInt("played"),
                        s.getInt("won"),
                        s.getInt("drawn"),
                        s.getInt("lost"),
                        s.getInt("gf"),
                        s.getInt("ga"),
                        s.getInt("points"));
                championship.putStanding(standing);
            }
        }

        ConfigurationSection fixturesSec = section.getConfigurationSection("fixtures");
        if (fixturesSec != null) {
            for (String fixId : fixturesSec.getKeys(false)) {
                ConfigurationSection f = fixturesSec.getConfigurationSection(fixId);
                if (f == null) {
                    continue;
                }
                if (f.getBoolean("friendly", false)) {
                    continue;
                }
                ChampFixture fixture = new ChampFixture(
                        fixId,
                        f.getInt("round"),
                        f.getString("team-a"),
                        f.getString("team-b"));
                fixture.setState(FixtureState.valueOf(
                        f.getString("state", "PENDING").toUpperCase(Locale.ROOT)));
                String stage = f.getString("playoff-stage");
                if (stage != null && !stage.isEmpty()) {
                    fixture.setPlayoffStage(PlayoffStage.valueOf(stage));
                }
                fixture.setScores(f.getInt("score-a", 0), f.getInt("score-b", 0));
                fixture.setWinnerTeamId(f.getString("winner"));
                fixture.setNextFixtureId(f.getString("next"));
                championship.addFixture(fixture);
            }
        }
        return championship;
    }

    private void saveChampionship(ConfigurationSection section, Championship championship) {
        section.set("name", championship.getDisplayName());
        section.set("type", championship.getType().name());
        section.set("state", championship.getState().name());
        section.set("arena", championship.getArenaName());
        section.set("points-win", championship.getPointsWin());
        section.set("points-draw", championship.getPointsDraw());
        section.set("points-loss", championship.getPointsLoss());
        section.set("created-at", championship.getCreatedAt());
        section.set("champion-team", championship.getChampionTeamId());
        section.set("playoffs-active", championship.isPlayoffsActive());

        ConfigurationSection teamsSec = section.createSection("teams");
        for (ChampTeam team : championship.getTeams().values()) {
            ConfigurationSection t = teamsSec.createSection(team.getId());
            t.set("name", team.getName());
            if (team.getCaptain() != null) {
                t.set("captain", team.getCaptain().toString());
            }
            t.set("eliminated", team.isEliminated());
            if (team.getGroupId() != null) {
                t.set("group", team.getGroupId());
            }
            List<String> members = new ArrayList<>();
            for (UUID uuid : team.getMembers()) {
                members.add(uuid.toString());
            }
            t.set("members", members);
        }

        ConfigurationSection standingsSec = section.createSection("standings");
        for (ChampStanding standing : championship.getStandings().values()) {
            ConfigurationSection s = standingsSec.createSection(standing.getTeamId());
            s.set("played", standing.getPlayed());
            s.set("won", standing.getWon());
            s.set("drawn", standing.getDrawn());
            s.set("lost", standing.getLost());
            s.set("gf", standing.getGoalsFor());
            s.set("ga", standing.getGoalsAgainst());
            s.set("points", standing.getPoints());
        }

        ConfigurationSection fixturesSec = section.createSection("fixtures");
        for (ChampFixture fixture : championship.getFixtures()) {
            ConfigurationSection f = fixturesSec.createSection(fixture.getId());
            f.set("round", fixture.getRound());
            f.set("team-a", fixture.getTeamAId());
            f.set("team-b", fixture.getTeamBId());
            if (fixture.getPlayoffStage() != null) {
                f.set("playoff-stage", fixture.getPlayoffStage().name());
            }
            f.set("state", fixture.getState().name());
            f.set("score-a", fixture.getScoreA());
            f.set("score-b", fixture.getScoreB());
            f.set("winner", fixture.getWinnerTeamId());
            f.set("next", fixture.getNextFixtureId());
        }
    }
}
