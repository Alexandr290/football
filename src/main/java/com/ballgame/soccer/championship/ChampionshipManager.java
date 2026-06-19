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

    private final SoccerPlugin plugin;
    private final File file;
    private final Map<String, Championship> championships = new ConcurrentHashMap<>();
    private int teamIdSeq;

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
        if (!championship.isRegistrationOpen()) {
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
        save();
        return true;
    }

    public boolean leaveTeam(Championship championship, Player player) {
        if (!championship.isRegistrationOpen()) {
            return false;
        }
        ChampTeam team = championship.findTeamOf(player.getUniqueId());
        if (team == null) {
            return false;
        }
        team.removeMember(player.getUniqueId());
        if (team.size() == 0) {
            championship.removeTeam(team.getId());
        } else if (player.getUniqueId().equals(team.getCaptain())) {
            team.setCaptain(team.getMembers().get(0));
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
        championship.clearFixtures();
        generateFixtures(championship);
        championship.setState(ChampionshipState.ACTIVE);
        save();
        broadcastChampionship(championship, plugin.messages().format("champ-started",
                "name", championship.getDisplayName()));
        return true;
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
        int round = 1;
        int fixtureNum = 1;
        for (int i = 0; i < teams.size(); i++) {
            for (int j = i + 1; j < teams.size(); j++) {
                championship.addFixture(new ChampFixture(
                        "f" + fixtureNum++, round,
                        teams.get(i).getId(), teams.get(j).getId()));
            }
        }
    }

    private void generateKnockoutFixtures(Championship championship, List<ChampTeam> teams) {
        int size = teams.size();
        int bracketSize = 1;
        while (bracketSize < size) {
            bracketSize <<= 1;
        }
        int round = 1;
        int fixtureNum = 1;
        List<ChampFixture> roundFixtures = new ArrayList<>();
        for (int i = 0; i < bracketSize; i += 2) {
            String teamA = i < size ? teams.get(i).getId() : null;
            String teamB = (i + 1) < size ? teams.get(i + 1).getId() : null;
            ChampFixture fixture = new ChampFixture("f" + fixtureNum++, round, teamA, teamB);
            if (teamA == null || teamB == null) {
                fixture.setState(FixtureState.COMPLETED);
                fixture.setScores(teamA == null ? 0 : 1, teamB == null ? 0 : 1);
                fixture.setWinnerTeamId(teamA != null ? teamA : teamB);
            }
            championship.addFixture(fixture);
            roundFixtures.add(fixture);
        }
        buildKnockoutLinks(championship, roundFixtures, round + 1, fixtureNum);
        autoAdvanceByes(championship);
    }

    private int buildKnockoutLinks(Championship championship, List<ChampFixture> currentRound,
                                   int round, int fixtureNum) {
        if (currentRound.size() <= 1) {
            return fixtureNum;
        }
        List<ChampFixture> nextRound = new ArrayList<>();
        for (int i = 0; i < currentRound.size(); i += 2) {
            ChampFixture next = new ChampFixture("f" + fixtureNum++, round, null, null);
            championship.addFixture(next);
            currentRound.get(i).setNextFixtureId(next.getId());
            if (i + 1 < currentRound.size()) {
                currentRound.get(i + 1).setNextFixtureId(next.getId());
            }
            nextRound.add(next);
        }
        return buildKnockoutLinks(championship, nextRound, round + 1, fixtureNum);
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
        if (winner == null && championship.getType() == ChampionshipType.KNOCKOUT) {
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

        if (championship.getType() == ChampionshipType.LEAGUE) {
            applyLeagueResult(championship, fixture, scoreA, scoreB);
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
        for (ChampFixture fixture : championship.getFixtures()) {
            if (fixture.getTeamAId() != null && fixture.getTeamBId() != null
                    && fixture.getState() != FixtureState.COMPLETED) {
                return false;
            }
        }
        return true;
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
                if (fixture.getNextFixtureId() == null && fixture.getWinnerTeamId() != null) {
                    championship.setChampionTeamId(fixture.getWinnerTeamId());
                    ChampTeam team = championship.getTeam(fixture.getWinnerTeamId());
                    return team != null ? team.getName() : null;
                }
            }
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
        return null;
    }

    public boolean joinFixtureMatch(Player player, Championship championship, ChampFixture fixture) {
        if (championship.getState() != ChampionshipState.ACTIVE
                || fixture.getState() != FixtureState.PENDING) {
            return false;
        }
        ChampTeam team = championship.findTeamOf(player.getUniqueId());
        if (team == null || !fixture.involvesTeam(team.getId())) {
            return false;
        }
        Arena arena = plugin.arenas().get(championship.getArenaName());
        if (arena == null || !arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        if (fixture.getTeamAId() == null || fixture.getTeamBId() == null) {
            return false;
        }
        TeamSide side = team.getId().equals(fixture.getTeamAId()) ? TeamSide.RED : TeamSide.BLUE;
        fixture.setState(FixtureState.LIVE);
        save();
        return plugin.games().joinChampionship(player, arena, side, championship.getId(), fixture.getId());
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

        ConfigurationSection teamsSec = section.getConfigurationSection("teams");
        if (teamsSec != null) {
            for (String teamId : teamsSec.getKeys(false)) {
                ConfigurationSection t = teamsSec.getConfigurationSection(teamId);
                if (t == null) {
                    continue;
                }
                UUID captain = UUID.fromString(t.getString("captain"));
                ChampTeam team = new ChampTeam(teamId, t.getString("name", teamId), captain);
                team.setEliminated(t.getBoolean("eliminated", false));
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
                ChampFixture fixture = new ChampFixture(
                        fixId,
                        f.getInt("round"),
                        f.getString("team-a"),
                        f.getString("team-b"));
                fixture.setState(FixtureState.valueOf(
                        f.getString("state", "PENDING").toUpperCase(Locale.ROOT)));
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

        ConfigurationSection teamsSec = section.createSection("teams");
        for (ChampTeam team : championship.getTeams().values()) {
            ConfigurationSection t = teamsSec.createSection(team.getId());
            t.set("name", team.getName());
            t.set("captain", team.getCaptain().toString());
            t.set("eliminated", team.isEliminated());
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
            f.set("state", fixture.getState().name());
            f.set("score-a", fixture.getScoreA());
            f.set("score-b", fixture.getScoreB());
            f.set("winner", fixture.getWinnerTeamId());
            f.set("next", fixture.getNextFixtureId());
        }
    }
}
