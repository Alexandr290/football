package com.ballgame.soccer.game;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.bot.BotFormation;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.ChampTeam;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.FixtureState;
import com.ballgame.soccer.fifa.FifaClub;
import com.ballgame.soccer.gui.QuickJoinModeMenu;
import com.ballgame.soccer.setup.MatchSetupSession;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks every active {@link Game}, maps players to their current match and runs
 * the single shared per-tick loop that advances all matches and ball physics.
 */
public final class GameManager {

    public record QuickJoinTarget(Arena arena, Game game) {
    }

    private final SoccerPlugin plugin;
    private final Map<String, Game> gamesByArena = new HashMap<>();
    private final Map<UUID, Game> gamesByPlayer = new HashMap<>();
    private final Map<UUID, FreeWatch> freeWatchers = new HashMap<>();
    private BukkitTask tickTask;
    private long viewerSyncCounter;

    public GameManager(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickAll();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Game game : new ArrayList<>(gamesByArena.values())) {
            restoreFreeWatchers(game);
            game.cleanup();
        }
        gamesByArena.clear();
        gamesByPlayer.clear();
        freeWatchers.clear();
    }

    private void tickAll() {
        for (Game game : new ArrayList<>(gamesByArena.values())) {
            try {
                game.tick();
            } catch (Exception ex) {
                plugin.getLogger().severe("Error ticking match in arena "
                        + game.getArena().getName() + ": " + ex.getMessage());
                ex.printStackTrace();
            }
        }
        if (++viewerSyncCounter % 20 == 0) {
            syncServerViewers();
        }
    }

    /**
     * Ensures every online player sees the primary live match score (TV ticker +
     * sidebar). Match participants are always wired in; everyone else becomes a
     * server-wide spectator.
     */
    public void syncServerViewers() {
        Game primary = selectPrimaryBroadcastGame();
        for (Game game : new ArrayList<>(gamesByArena.values())) {
            if (game.isEnded()) {
                continue;
            }
            if (game != primary) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    Game theirMatch = gamesByPlayer.get(online.getUniqueId());
                    if (theirMatch != game) {
                        game.detachServerViewer(online);
                    }
                }
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            Game theirMatch = gamesByPlayer.get(online.getUniqueId());
            Game show = theirMatch != null && !theirMatch.isEnded() ? theirMatch : primary;
            if (show == null) {
                continue;
            }
            show.attachServerViewer(online);
            for (Game other : gamesByArena.values()) {
                if (other != show && !other.isEnded()) {
                    other.detachServerViewer(online);
                }
            }
        }
    }

    private Game selectPrimaryBroadcastGame() {
        Game best = null;
        int bestRank = 0;
        for (Game game : gamesByArena.values()) {
            if (game.isEnded()) {
                continue;
            }
            int rank = game.broadcastPriority();
            if (rank > bestRank) {
                bestRank = rank;
                best = game;
            }
        }
        return best;
    }

    /**
     * Creates a private exhibition from the command-free FIFA setup lobby.
     * Claimed slots become humans; mixed and AI modes fill all remaining club
     * positions with the real starting-XI names from the selected catalog.
     */
    public Game startConfiguredMatch(MatchSetupSession session) {
        if (session == null || !session.readyToLaunch()) {
            return null;
        }
        Arena arena = plugin.arenas().get(session.getArenaName());
        FifaClub redClub = plugin.clubCatalog().get(session.getRedClubCode());
        FifaClub blueClub = plugin.clubCatalog().get(session.getBlueClubCode());
        if (arena == null || !arena.isPlayable() || redClub == null || blueClub == null
                || gamesByArena.containsKey(key(arena))) {
            return null;
        }

        for (Map.Entry<UUID, MatchSetupSession.Claim> entry : session.getClaims().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || gamesByPlayer.containsKey(entry.getKey())) {
                return null;
            }
        }

        Game game = new Game(plugin, arena);
        game.setSoloMatch(true);
        game.setTeamCaps(session.getRedSize(), session.getBlueSize());
        game.setMatchTeamNames(redClub.getNameRu(), blueClub.getNameRu());
        game.setClubCodes(redClub.getCode(), blueClub.getCode());
        game.setBotDifficulty(TeamSide.RED, session.getRedDifficulty());
        game.setBotDifficulty(TeamSide.BLUE, session.getBlueDifficulty());
        game.setMatchOptions(new MatchOptions(
                session.getDurationSeconds(),
                2,
                session.getTimeOfDay().ticks(),
                session.getWeather() == MatchSetupSession.Weather.RAIN
                        ? WeatherType.DOWNFALL : WeatherType.CLEAR));
        game.setBotOnlyMatch(session.getMode() == MatchSetupSession.Mode.BOTS_VS_BOTS);
        gamesByArena.put(key(arena), game);

        try {
            for (Map.Entry<UUID, MatchSetupSession.Claim> entry : session.getClaims().entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                MatchSetupSession.Claim claim = entry.getValue();
                if (player == null || !game.addPlayer(player, claim.team())) {
                    throw new IllegalStateException("Could not add claimed player");
                }
                gamesByPlayer.put(player.getUniqueId(), game);
                plugin.network().clearLobbyReturn(player);
                if (!game.claimSquadSlot(claim.team(), claim.slot(), player.getUniqueId())) {
                    throw new IllegalStateException("Could not claim configured squad slot");
                }
            }

            if (session.getMode() != MatchSetupSession.Mode.PLAYERS_VS_PLAYERS) {
                game.fillFullSquadsSilently();
            }
            game.deployFormationLineup();
            game.startCountdown();

            for (UUID acceptedId : session.getAccepted()) {
                if (session.claimOf(acceptedId) != null) {
                    continue;
                }
                Player spectatorPlayer = Bukkit.getPlayer(acceptedId);
                if (spectatorPlayer != null && spectatorPlayer.isOnline()) {
                    beginFreeWatch(spectatorPlayer, game);
                }
            }
            syncServerViewers();
            return game;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not launch configured match: " + ex.getMessage());
            gamesByArena.remove(key(arena));
            gamesByPlayer.values().removeIf(mapped -> mapped == game);
            game.cleanup();
            return null;
        }
    }

    /**
     * Join (or create and join) the match for a given arena with optional team choice.
     *
     * @return true if the player joined.
     */
    public boolean join(Player player, Arena arena) {
        return join(player, arena, null);
    }

    public boolean join(Player player, Arena arena, TeamSide team) {
        if (plugin.network().redirectJoin(player, arena.getName(), team)) {
            return true;
        }
        Game existingPlayerGame = gamesByPlayer.get(player.getUniqueId());
        if (existingPlayerGame != null) {
            if (existingPlayerGame.isEnded()) {
                existingPlayerGame.cancelScheduledCleanup();
                existingPlayerGame.releaseAllPlayersQuietly();
                gamesByPlayer.remove(player.getUniqueId());
            } else {
                plugin.messages().send(player, "already-in-game");
                return false;
            }
        }
        if (!arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        Game game = gamesByArena.get(key(arena));
        if (game != null && game.isEnded()) {
            Game ended = game;
            ended.cancelScheduledCleanup();
            ended.releaseAllPlayersQuietly();
            ended.cleanupResourcesOnly();
            gamesByArena.values().removeIf(g -> g == ended);
            gamesByPlayer.values().removeIf(g -> g == ended);
            game = null;
        }
        if (game == null) {
            game = new Game(plugin, arena);
            gamesByArena.put(key(arena), game);
        } else if (game.isSoloMatch()) {
            plugin.messages().send(player, "join-solo-only");
            return false;
        }
        if (game.isFull()) {
            plugin.messages().send(player, "game-full");
            return false;
        }
        if (team != null && game.isTeamFull(team)) {
            plugin.messages().send(player, "quickjoin-team-full",
                    "team", teamDisplayName(game, team));
            return false;
        }
        if (!plugin.settings().allowRejoin && game.getState() != GameState.WAITING
                && game.getState() != GameState.COUNTDOWN) {
            plugin.messages().send(player, "match-already-started");
            return false;
        }
        if (game.addPlayer(player, team)) {
            gamesByPlayer.put(player.getUniqueId(), game);
            plugin.network().clearLobbyReturn(player);
            return true;
        }
        if (team != null) {
            plugin.messages().send(player, "quickjoin-team-full",
                    "team", teamDisplayName(game, team));
        }
        return false;
    }

    public boolean joinAsReferee(Player player, Arena arena) {
        if (plugin.network().redirectJoin(player, arena.getName(), null)) {
            return true;
        }
        Game existingPlayerGame = gamesByPlayer.get(player.getUniqueId());
        if (existingPlayerGame != null) {
            if (existingPlayerGame.isEnded()) {
                existingPlayerGame.cancelScheduledCleanup();
                existingPlayerGame.releaseAllPlayersQuietly();
                gamesByPlayer.remove(player.getUniqueId());
            } else {
                plugin.messages().send(player, "already-in-game");
                return false;
            }
        }
        if (!arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        Game game = gamesByArena.get(key(arena));
        if (game != null && game.isEnded()) {
            Game ended = game;
            ended.cancelScheduledCleanup();
            ended.releaseAllPlayersQuietly();
            ended.cleanupResourcesOnly();
            gamesByArena.values().removeIf(g -> g == ended);
            gamesByPlayer.values().removeIf(g -> g == ended);
            game = null;
        }
        if (game == null) {
            game = new Game(plugin, arena);
            gamesByArena.put(key(arena), game);
        }
        if (game.isRefereeSlotTaken()) {
            plugin.messages().send(player, "referee-slot-taken");
            return false;
        }
        if (!plugin.settings().allowRejoin && game.getState() != GameState.WAITING
                && game.getState() != GameState.COUNTDOWN) {
            plugin.messages().send(player, "match-already-started");
            return false;
        }
        if (game.addReferee(player)) {
            gamesByPlayer.put(player.getUniqueId(), game);
            plugin.network().clearLobbyReturn(player);
            return true;
        }
        return false;
    }

    /**
     * Join as referee of a solo bot match: red bots vs blue bots, no field human.
     */
    public boolean joinSoloAsReferee(Player player, Arena arena, int redBotCount, int blueBotCount,
                                     BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        return joinSoloAsReferee(player, arena, redBotCount, blueBotCount,
                redDifficulty, blueDifficulty, BotFormation.FOUR_FOUR_TWO, BotFormation.FOUR_FOUR_TWO);
    }

    public boolean joinSoloAsReferee(Player player, Arena arena, int redBotCount, int blueBotCount,
                                     BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                                     BotFormation formation) {
        return joinSoloAsReferee(player, arena, redBotCount, blueBotCount,
                redDifficulty, blueDifficulty, formation, formation);
    }

    public boolean joinSoloAsReferee(Player player, Arena arena, int redBotCount, int blueBotCount,
                                     BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                                     BotFormation redFormation, BotFormation blueFormation) {
        if (!plugin.settings().botsEnabled) {
            return joinAsReferee(player, arena);
        }
        Game existingPlayerGame = gamesByPlayer.get(player.getUniqueId());
        if (existingPlayerGame != null) {
            if (existingPlayerGame.isEnded()) {
                existingPlayerGame.cancelScheduledCleanup();
                existingPlayerGame.releaseAllPlayersQuietly();
                gamesByPlayer.remove(player.getUniqueId());
            } else {
                plugin.messages().send(player, "already-in-game");
                return false;
            }
        }
        if (!arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }

        int redBots = Math.min(redBotCount, plugin.settings().botMaxSoloCount);
        redBots = Math.min(redBots, plugin.settings().maxPlayersPerTeam);
        int blueBots = Math.min(blueBotCount, plugin.settings().botMaxSoloCount);
        blueBots = Math.min(blueBots, plugin.settings().maxPlayersPerTeam);
        redBots = Math.max(1, redBots);
        blueBots = Math.max(1, blueBots);

        Game game = gamesByArena.get(key(arena));
        if (game != null && !game.isEnded() && !game.isSoloMatch()) {
            plugin.messages().send(player, "match-already-started");
            return false;
        }
        if (game != null && game.isEnded()) {
            Game ended = game;
            ended.cancelScheduledCleanup();
            ended.releaseAllPlayersQuietly();
            ended.cleanupResourcesOnly();
            gamesByArena.values().removeIf(g -> g == ended);
            gamesByPlayer.values().removeIf(g -> g == ended);
            game = null;
        }
        if (game != null && game.isRefereeSlotTaken()) {
            plugin.messages().send(player, "referee-slot-taken");
            return false;
        }
        if (game == null) {
            game = new Game(plugin, arena);
            game.setSoloMatch(true);
            gamesByArena.put(key(arena), game);
        } else {
            game.setSoloMatch(true);
        }
        game.setBotDifficulty(TeamSide.RED, redDifficulty);
        game.setBotDifficulty(TeamSide.BLUE, blueDifficulty);
        game.setFormation(TeamSide.RED, redFormation);
        game.setFormation(TeamSide.BLUE, blueFormation);

        if (!game.addReferee(player)) {
            return false;
        }
        gamesByPlayer.put(player.getUniqueId(), game);
        plugin.network().clearLobbyReturn(player);

        int currentRed = 0;
        int currentBlue = 0;
        for (var bot : game.getBots()) {
            if (bot.getTeam() == TeamSide.RED) {
                currentRed++;
            } else if (bot.getTeam() == TeamSide.BLUE) {
                currentBlue++;
            }
        }
        for (int i = currentRed; i < redBots; i++) {
            if (game.isTeamFull(TeamSide.RED)) {
                break;
            }
            game.addBot(TeamSide.RED, i);
        }
        for (int i = currentBlue; i < blueBots; i++) {
            if (game.isTeamFull(TeamSide.BLUE)) {
                break;
            }
            game.addBot(TeamSide.BLUE, i);
        }
        game.deployFormationLineup();
        plugin.messages().send(player, "referee-solo-ready",
                "red", String.valueOf(redBots),
                "blue", String.valueOf(blueBots),
                "red_difficulty", plugin.messages().raw(game.getBotDifficulty(TeamSide.RED).langKey()),
                "blue_difficulty", plugin.messages().raw(game.getBotDifficulty(TeamSide.BLUE).langKey()));
        return true;
    }

    /**
     * Auto-pick the best available arena (most populated joinable match, else any
     * empty playable arena) and join it, optionally after a team-pick GUI.
     */
    public boolean quickJoin(Player player) {
        return quickJoin(player, null);
    }

    public boolean quickJoin(Player player, TeamSide preferredTeam) {
        if (plugin.network().redirectQuickJoin(player, preferredTeam)) {
            return true;
        }
        if (preferredTeam != null) {
            QuickJoinTarget target = findQuickJoinTarget(QuickJoinMode.TEAMS);
            if (target == null) {
                plugin.messages().send(player, "no-arenas-available");
                return false;
            }
            return join(player, target.arena(), preferredTeam);
        }
        QuickJoinModeMenu.open(plugin, player);
        return true;
    }

    public boolean joinSolo(Player player, Arena arena, TeamSide team, int botCount) {
        return joinSolo(player, arena, team, botCount, 0);
    }

    public boolean joinSolo(Player player, Arena arena, TeamSide team, int botCount, int teammateBots) {
        return joinSolo(player, arena, team, botCount, teammateBots, BotDifficulty.MEDIUM);
    }

    public boolean joinSolo(Player player, Arena arena, TeamSide team, int botCount,
                            int teammateBots, BotDifficulty difficulty) {
        return joinSolo(player, arena, team, botCount, teammateBots, difficulty, difficulty);
    }

    public boolean joinSolo(Player player, Arena arena, TeamSide team, int botCount,
                            int teammateBots, BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        return joinSolo(player, arena, team, botCount, teammateBots,
                redDifficulty, blueDifficulty, BotFormation.FOUR_FOUR_TWO);
    }

    public boolean joinSolo(Player player, Arena arena, TeamSide team, int botCount,
                            int teammateBots, BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation formation) {
        TeamSide playerTeam = team != null ? team : TeamSide.RED;
        int redBots = playerTeam == TeamSide.RED ? teammateBots : botCount;
        int blueBots = playerTeam == TeamSide.BLUE ? teammateBots : botCount;
        return joinSolo(player, arena, team, redBots, blueBots,
                redDifficulty, blueDifficulty, formation, formation);
    }

    public boolean joinSolo(Player player, Arena arena, TeamSide team, int redBots, int blueBots,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation redFormation, BotFormation blueFormation) {
        if (!plugin.settings().botsEnabled) {
            return join(player, arena, team);
        }
        Game existingPlayerGame = gamesByPlayer.get(player.getUniqueId());
        if (existingPlayerGame != null) {
            if (existingPlayerGame.isEnded()) {
                existingPlayerGame.cancelScheduledCleanup();
                existingPlayerGame.releaseAllPlayersQuietly();
                gamesByPlayer.remove(player.getUniqueId());
            } else {
                plugin.messages().send(player, "already-in-game");
                return false;
            }
        }
        if (!arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        int maxPerTeam = plugin.settings().maxPlayersPerTeam;
        TeamSide playerTeam = team != null ? team : TeamSide.RED;

        int cappedRed = Math.min(redBots, plugin.settings().botMaxSoloCount);
        int cappedBlue = Math.min(blueBots, plugin.settings().botMaxSoloCount);
        cappedRed = Math.min(cappedRed, maxPerTeam - (playerTeam == TeamSide.RED ? 1 : 0));
        cappedBlue = Math.min(cappedBlue, maxPerTeam - (playerTeam == TeamSide.BLUE ? 1 : 0));
        cappedRed = Math.max(0, cappedRed);
        cappedBlue = Math.max(0, cappedBlue);

        Game game = gamesByArena.get(key(arena));
        if (game != null && !game.isEnded() && !game.isSoloMatch()) {
            plugin.messages().send(player, "match-already-started");
            return false;
        }
        if (game != null && game.isEnded()) {
            Game ended = game;
            ended.cancelScheduledCleanup();
            ended.releaseAllPlayersQuietly();
            ended.cleanupResourcesOnly();
            gamesByArena.values().removeIf(g -> g == ended);
            gamesByPlayer.values().removeIf(g -> g == ended);
            game = null;
        }
        if (game == null) {
            game = new Game(plugin, arena);
            game.setSoloMatch(true);
            gamesByArena.put(key(arena), game);
        }
        game.setBotDifficulty(TeamSide.RED, redDifficulty);
        game.setBotDifficulty(TeamSide.BLUE, blueDifficulty);
        game.setFormation(TeamSide.RED, redFormation);
        game.setFormation(TeamSide.BLUE, blueFormation);
        if (game.isFull() || (team != null && game.isTeamFull(team))) {
            plugin.messages().send(player, "game-full");
            return false;
        }
        if (!game.addPlayer(player, team)) {
            return false;
        }
        gamesByPlayer.put(player.getUniqueId(), game);
        plugin.network().clearLobbyReturn(player);

        int currentRed = 0;
        int currentBlue = 0;
        for (var bot : game.getBots()) {
            if (bot.getTeam() == TeamSide.RED) {
                currentRed++;
            } else if (bot.getTeam() == TeamSide.BLUE) {
                currentBlue++;
            }
        }
        for (int i = currentRed; i < cappedRed; i++) {
            if (game.isTeamFull(TeamSide.RED)) {
                break;
            }
            game.addBot(TeamSide.RED, i);
        }
        for (int i = currentBlue; i < cappedBlue; i++) {
            if (game.isTeamFull(TeamSide.BLUE)) {
                break;
            }
            game.addBot(TeamSide.BLUE, i);
        }
        game.deployFormationLineup();
        plugin.messages().send(player, "solo-match-ready",
                "red", String.valueOf(cappedRed),
                "blue", String.valueOf(cappedBlue),
                "red_difficulty", plugin.messages().raw(game.getBotDifficulty(TeamSide.RED).langKey()),
                "blue_difficulty", plugin.messages().raw(game.getBotDifficulty(TeamSide.BLUE).langKey()));
        return true;
    }

    public QuickJoinTarget findQuickJoinTarget() {
        return findQuickJoinTarget(QuickJoinMode.TEAMS);
    }

    public QuickJoinTarget findQuickJoinTarget(QuickJoinMode mode) {
        if (mode == QuickJoinMode.TEAMS) {
            Game best = null;
            for (Game game : gamesByArena.values()) {
                if (game.isEnded() || game.isFull() || game.isSoloMatch()) {
                    continue;
                }
                if (game.getState() == GameState.WAITING || game.getState() == GameState.COUNTDOWN) {
                    if (best == null || game.playerCount() > best.playerCount()) {
                        best = game;
                    }
                }
            }
            if (best != null) {
                return new QuickJoinTarget(best.getArena(), best);
            }
            for (Arena arena : plugin.arenas().getArenas()) {
                Game existing = gamesByArena.get(key(arena));
                if (arena.isPlayable() && (existing == null || existing.isEnded())) {
                    return new QuickJoinTarget(arena, null);
                }
            }
            return null;
        }

        for (Arena arena : plugin.arenas().getArenas()) {
            if (!arena.isPlayable()) {
                continue;
            }
            Game existing = gamesByArena.get(key(arena));
            if (existing != null && !existing.isEnded()) {
                if (!existing.isSoloMatch()) {
                    continue;
                }
                if ((existing.getState() == GameState.WAITING || existing.getState() == GameState.COUNTDOWN)
                        && !existing.isFull()) {
                    return new QuickJoinTarget(arena, existing);
                }
                continue;
            }
            return new QuickJoinTarget(arena, null);
        }
        return null;
    }

    public Game getGame(Arena arena) {
        if (arena == null) {
            return null;
        }
        return gamesByArena.get(key(arena));
    }

    public boolean joinChampionship(Player player, Arena arena, TeamSide side,
                                    String championshipId, String fixtureId) {
        return joinChampionship(player, arena, side, championshipId, fixtureId, null, null);
    }

    public boolean joinChampionship(Player player, Arena arena, TeamSide side,
                                    String championshipId, String fixtureId,
                                    String redDisplayName, String blueDisplayName) {
        if (gamesByPlayer.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "already-in-game");
            return false;
        }
        if (!arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        if (!plugin.championships().verifyPlayerOnFixtureSide(
                player, championshipId, fixtureId, side)) {
            plugin.messages().send(player, "champ-fixture-wrong-team",
                    "team_a", redDisplayName != null ? redDisplayName : "?",
                    "team_b", blueDisplayName != null ? blueDisplayName : "?");
            return false;
        }
        Game game = findGameByFixture(championshipId, fixtureId);
        if (game != null && !game.isEnded()) {
            GameState state = game.getState();
            if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
                plugin.messages().send(player, "match-already-started");
                return false;
            }
        }
        if (game == null) {
            game = new Game(plugin, arena);
            game.setChampionship(championshipId, fixtureId);
            if (redDisplayName != null && blueDisplayName != null) {
                game.setMatchTeamNames(redDisplayName, blueDisplayName);
            }
            Championship championship = plugin.championships().get(championshipId);
            ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;
            if (championship != null && fixture != null) {
                bindNationCodes(game, championship, fixture);
            }
            gamesByArena.put(key(arena), game);
        } else {
            Championship championship = plugin.championships().get(championshipId);
            ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;
            if (championship != null && fixture != null) {
                bindNationCodes(game, championship, fixture);
            }
        }
        if (game.isFull()) {
            plugin.messages().send(player, "game-full");
            return false;
        }
        if (game.addPlayer(player, side)) {
            gamesByPlayer.put(player.getUniqueId(), game);
            return true;
        }
        return false;
    }

    public int addChampionshipBots(Championship championship, ChampFixture fixture,
                                   int redBots, int blueBots, BotDifficulty difficulty) {
        return addChampionshipBots(championship, fixture, redBots, blueBots, difficulty, difficulty);
    }

    public int addChampionshipBots(Championship championship, ChampFixture fixture,
                                   int redBots, int blueBots,
                                   BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        if (!plugin.settings().botsEnabled || championship == null || fixture == null) {
            return 0;
        }
        if (fixture.getState() == FixtureState.COMPLETED || fixture.getState() == FixtureState.LIVE) {
            return 0;
        }
        if (!plugin.championships().canJoinFixture(championship, fixture)) {
            return 0;
        }

        Game game = findGameByFixture(championship.getId(), fixture.getId());
        if (game != null && !game.isEnded()) {
            GameState state = game.getState();
            if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
                return 0;
            }
            game.setBotDifficulty(TeamSide.RED, redDifficulty);
            game.setBotDifficulty(TeamSide.BLUE, blueDifficulty);
        }

        String arenaName = plugin.championships().resolveFixtureArenaName(championship, fixture);
        Arena arena = plugin.arenas().get(arenaName);
        if (arena == null || !arena.isPlayable()) {
            return 0;
        }

        Game arenaGame = gamesByArena.get(key(arena));
        if (game == null) {
            if (arenaGame != null && !arenaGame.isEnded()) {
                return 0;
            }
            game = new Game(plugin, arena);
            game.setChampionship(championship.getId(), fixture.getId());
            game.setMatchTeamNames(fixture.sideADisplay(championship), fixture.sideBDisplay(championship));
            bindNationCodes(game, championship, fixture);
            game.setBotDifficulty(TeamSide.RED, redDifficulty);
            game.setBotDifficulty(TeamSide.BLUE, blueDifficulty);
            gamesByArena.put(key(arena), game);
        } else {
            bindNationCodes(game, championship, fixture);
        }

        int added = 0;
        if (redBots <= 0 && blueBots <= 0
                && (game.getRedNationCode() != null || game.getBlueNationCode() != null)) {
            added += game.fillSquadBots(TeamSide.RED);
            added += game.fillSquadBots(TeamSide.BLUE);
            return added > 0 ? added : -1;
        }
        added += addBotsToChampionshipSide(game, TeamSide.RED, Math.max(0, redBots));
        added += addBotsToChampionshipSide(game, TeamSide.BLUE, Math.max(0, blueBots));
        if (added == 0 && redBots <= 0 && blueBots <= 0) {
            return -1;
        }
        return added;
    }

    private void bindNationCodes(Game game, Championship championship, ChampFixture fixture) {
        if (game == null || championship == null || fixture == null) {
            return;
        }
        ChampTeam teamA = championship.getTeam(fixture.getTeamAId());
        ChampTeam teamB = championship.getTeam(fixture.getTeamBId());
        game.setNationCodes(
                teamA != null ? teamA.getNationCode() : null,
                teamB != null ? teamB.getNationCode() : null);
    }

    private int addBotsToChampionshipSide(Game game, TeamSide side, int requested) {
        int added = 0;
        int index = botCount(game, side);
        for (int i = 0; i < requested && !game.isTeamFull(side); i++) {
            if (game.addBot(side, index + i) != null) {
                added++;
            }
        }
        return added;
    }

    private int botCount(Game game, TeamSide side) {
        int count = 0;
        for (com.ballgame.soccer.bot.MatchBot bot : game.getBots()) {
            if (bot.getTeam() == side) {
                count++;
            }
        }
        return count;
    }

    public Game findGameByFixture(String championshipId, String fixtureId) {
        for (Game game : gamesByArena.values()) {
            if (game.isEnded()) {
                continue;
            }
            if (championshipId != null && fixtureId != null
                    && championshipId.equals(game.getChampionshipId())
                    && fixtureId.equals(game.getFixtureId())) {
                return game;
            }
        }
        return null;
    }

    /**
     * Creates a bot-only championship match (11 vs 11) and prepares kickoff.
     */
    public Game startChampionshipBotMatch(Championship championship, ChampFixture fixture, Arena arena) {
        if (!plugin.settings().botsEnabled || championship == null || fixture == null || arena == null) {
            return null;
        }
        if (fixture.getState() == FixtureState.COMPLETED || fixture.getState() == FixtureState.LIVE) {
            return null;
        }
        if (!plugin.championships().canJoinFixture(championship, fixture)) {
            return null;
        }

        Game existing = findGameByFixture(championship.getId(), fixture.getId());
        if (existing != null && !existing.isEnded()) {
            GameState state = existing.getState();
            if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
                return null;
            }
            existing.removeAllBots();
        }

        Game arenaGame = gamesByArena.get(key(arena));
        if (arenaGame != null && arenaGame != existing && !arenaGame.isEnded()) {
            return null;
        }

        Game game = existing;
        if (game == null || game.isEnded()) {
            game = new Game(plugin, arena);
            gamesByArena.put(key(arena), game);
        }

        game.setChampionship(championship.getId(), fixture.getId());
        game.setMatchTeamNames(fixture.sideADisplay(championship), fixture.sideBDisplay(championship));
        bindNationCodes(game, championship, fixture);
        game.setBotOnlyMatch(true);
        game.setBotDifficulty(TeamSide.RED, BotDifficulty.MEDIUM);
        game.setBotDifficulty(TeamSide.BLUE, BotDifficulty.MEDIUM);

        game.fillFullSquadsSilently();
        if (game.getTeamCount(TeamSide.RED) < plugin.settings().maxPlayersPerTeam
                || game.getTeamCount(TeamSide.BLUE) < plugin.settings().maxPlayersPerTeam) {
            game.removeAllBots();
            return null;
        }
        game.deployFormationLineup();
        game.refreshBotKitVisuals();
        game.tryStartBotOnlyLobby();
        syncServerViewers();
        return game;
    }

    /**
     * Bots-only friendly on a free arena: full squads at the given levels, the
     * usual walk-out and kickoff, and everyone else watching from the stands.
     *
     * @return the match, or null when bots are disabled, the arena is not
     *         playable/busy, or squads could not be filled
     */
    public Game startExhibitionBotMatch(Arena arena, BotDifficulty red, BotDifficulty blue) {
        if (!plugin.settings().botsEnabled || arena == null || !arena.isPlayable()) {
            return null;
        }
        Game existing = gamesByArena.get(key(arena));
        if (existing != null && !existing.isEnded()) {
            return null;
        }
        Game game = new Game(plugin, arena);
        gamesByArena.put(key(arena), game);
        game.setBotOnlyMatch(true);
        game.setBotDifficulty(TeamSide.RED, red != null ? red : BotDifficulty.MEDIUM);
        game.setBotDifficulty(TeamSide.BLUE, blue != null ? blue : BotDifficulty.MEDIUM);
        game.fillFullSquadsSilently();
        if (game.getTeamCount(TeamSide.RED) < plugin.settings().maxPlayersPerTeam
                || game.getTeamCount(TeamSide.BLUE) < plugin.settings().maxPlayersPerTeam) {
            game.removeAllBots();
            gamesByArena.remove(key(arena));
            return null;
        }
        game.deployFormationLineup();
        game.refreshBotKitVisuals();
        game.tryStartBotOnlyLobby();
        syncServerViewers();
        return game;
    }

    /** Teleports every online player not in a match to the arena spectator spot. */
    public void teleportServerToStadium(Arena arena, Game watchGame) {
        Location spot = resolveStadiumSpot(arena);
        if (spot == null) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (gamesByPlayer.containsKey(online.getUniqueId())) {
                continue;
            }
            if (watchGame != null) {
                beginFreeWatch(online, watchGame);
            } else {
                online.teleport(spot);
                online.setGameMode(GameMode.SPECTATOR);
                online.setAllowFlight(true);
                online.setFlying(true);
                online.setSpectatorTarget(null);
            }
        }
    }

    /**
     * Lets a non-participant fly around a match and keep the main menu.
     * The previous game mode is restored when that match ends.
     */
    public void beginFreeWatch(Player player, Game game) {
        if (player == null || game == null || !player.isOnline()) {
            return;
        }
        Game joined = gamesByPlayer.get(player.getUniqueId());
        if (joined != null && joined.contains(player.getUniqueId())) {
            return;
        }
        gamesByPlayer.remove(player.getUniqueId());
        FreeWatch existing = freeWatchers.get(player.getUniqueId());
        GameMode previous = existing != null ? existing.previousMode() : player.getGameMode();
        freeWatchers.put(player.getUniqueId(), new FreeWatch(game, previous));
        Location spot = resolveStadiumSpot(game.getArena());
        if (spot != null) {
            player.teleport(spot);
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(previous == GameMode.SPECTATOR ? GameMode.ADVENTURE : previous);
        }
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setCollidable(false);
        player.setInvulnerable(true);
        player.setSpectatorTarget(null);
        game.attachServerViewer(player);
        plugin.hubListener().giveMenuButton(player);
    }

    public boolean isFreeSpectator(Player player) {
        return player != null && freeWatchers.containsKey(player.getUniqueId());
    }

    public void restoreFreeWatchers(Game game) {
        if (game == null) {
            return;
        }
        freeWatchers.entrySet().removeIf(entry -> {
            if (entry.getValue().game != game) {
                return false;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()
                    && !gamesByPlayer.containsKey(player.getUniqueId())) {
                player.setSpectatorTarget(null);
                player.setCollidable(true);
                player.setInvulnerable(false);
                player.setGameMode(entry.getValue().previousMode);
                boolean flight = entry.getValue().previousMode == GameMode.CREATIVE
                        || entry.getValue().previousMode == GameMode.SPECTATOR;
                player.setAllowFlight(flight);
                player.setFlying(false);
            }
            return true;
        });
    }

    public void teleportServerToStadium(Arena arena) {
        teleportServerToStadium(arena, getGame(arena));
    }

    public Location resolveStadiumSpot(Arena arena) {
        if (arena == null) {
            return null;
        }
        if (arena.getSpectator() != null) {
            return arena.getSpectator();
        }
        if (arena.getLobby() != null) {
            return arena.getLobby();
        }
        return arena.getBallSpawn();
    }

    /** Clears a pre-kickoff lobby when the fixture arena changes. */
    public void resetFixtureLobbyGame(Championship championship, ChampFixture fixture) {
        if (championship == null || fixture == null) {
            return;
        }
        Game game = findGameByFixture(championship.getId(), fixture.getId());
        if (game == null || game.isEnded()) {
            return;
        }
        GameState state = game.getState();
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
            return;
        }
        game.releaseAllPlayersQuietly();
        endGame(game);
    }

    public void leave(Player player) {
        Game game = gamesByPlayer.remove(player.getUniqueId());
        if (game == null) {
            plugin.messages().send(player, "not-in-game");
            return;
        }
        if (game.contains(player.getUniqueId())) {
            game.removePlayer(player, true);
        } else {
            game.detachServerViewer(player);
        }
        plugin.messages().send(player, "left-game");
        plugin.network().returnToLobbyAfterLeave(player);
    }

    /** Called by a {@link Game} when it finishes. */
    public void endGame(Game game) {
        game.cancelScheduledCleanup();
        game.cleanup();
        gamesByArena.values().removeIf(g -> g == game);
        gamesByPlayer.values().removeIf(g -> g == game);
        restoreFreeWatchers(game);
    }

    public Game getGame(Player player) {
        return gamesByPlayer.get(player.getUniqueId());
    }

    public Game getGame(UUID uuid) {
        return gamesByPlayer.get(uuid);
    }

    public boolean isPlaying(Player player) {
        return gamesByPlayer.containsKey(player.getUniqueId());
    }

    public Collection<Game> getActiveGames() {
        return gamesByArena.values();
    }

    public List<Game> getGamesSnapshot() {
        return new ArrayList<>(gamesByArena.values());
    }

    /** Remove a player mapping without restoring (used on disconnect handling). */
    public void forget(Player player) {
        gamesByPlayer.remove(player.getUniqueId());
        freeWatchers.remove(player.getUniqueId());
    }

    private record FreeWatch(Game game, GameMode previousMode) {
    }

    private String teamDisplayName(Game game, TeamSide team) {
        if (game != null) {
            return game.getTeamDisplayName(team);
        }
        return team == TeamSide.RED ? plugin.settings().redName : plugin.settings().blueName;
    }

    private String key(Arena arena) {
        return arena.getName().toLowerCase(Locale.ROOT);
    }
}
