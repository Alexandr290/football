package com.ballgame.soccer.game;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.FixtureState;
import com.ballgame.soccer.gui.QuickJoinModeMenu;
import org.bukkit.Bukkit;
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
            game.cleanup();
        }
        gamesByArena.clear();
        gamesByPlayer.clear();
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
        int opponents = Math.min(botCount, plugin.settings().botMaxSoloCount);
        opponents = Math.min(opponents, plugin.settings().maxPlayersPerTeam);
        TeamSide playerTeam = team != null ? team : TeamSide.RED;
        TeamSide botTeam = playerTeam.opposite();
        // The player occupies one slot on their own side, so team-mate bots are
        // capped one below the team limit.
        int allies = Math.min(teammateBots, plugin.settings().botMaxSoloCount);
        allies = Math.min(allies, Math.max(0, plugin.settings().maxPlayersPerTeam - 1));

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
        if (game.isFull() || (team != null && game.isTeamFull(team))) {
            plugin.messages().send(player, "game-full");
            return false;
        }
        if (!game.addPlayer(player, team)) {
            return false;
        }
        gamesByPlayer.put(player.getUniqueId(), game);
        plugin.network().clearLobbyReturn(player);

        int currentOpponentBots = 0;
        int currentAllyBots = 0;
        for (var bot : game.getBots()) {
            if (bot.getTeam() == botTeam) {
                currentOpponentBots++;
            } else if (bot.getTeam() == playerTeam) {
                currentAllyBots++;
            }
        }
        for (int i = currentOpponentBots; i < opponents; i++) {
            if (game.isTeamFull(botTeam)) {
                break;
            }
            game.addBot(botTeam, i);
        }
        for (int i = currentAllyBots; i < allies; i++) {
            if (game.isTeamFull(playerTeam)) {
                break;
            }
            game.addBot(playerTeam, i);
        }
        plugin.messages().send(player, "solo-match-ready",
                "count", String.valueOf(opponents),
                "team", game.getTeamDisplayName(botTeam),
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
            gamesByArena.put(key(arena), game);
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
            game.setBotDifficulty(TeamSide.RED, redDifficulty);
            game.setBotDifficulty(TeamSide.BLUE, blueDifficulty);
            gamesByArena.put(key(arena), game);
        }

        int added = 0;
        added += addBotsToChampionshipSide(game, TeamSide.RED, Math.max(0, redBots));
        added += addBotsToChampionshipSide(game, TeamSide.BLUE, Math.max(0, blueBots));
        if (added == 0 && redBots <= 0 && blueBots <= 0) {
            return -1;
        }
        return added;
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

    public void leave(Player player) {
        Game game = gamesByPlayer.remove(player.getUniqueId());
        if (game == null) {
            plugin.messages().send(player, "not-in-game");
            return;
        }
        game.removePlayer(player, true);
        plugin.messages().send(player, "left-game");
        plugin.network().returnToLobbyAfterLeave(player);
    }

    /** Called by a {@link Game} when it finishes. */
    public void endGame(Game game) {
        game.cancelScheduledCleanup();
        game.cleanup();
        gamesByArena.values().removeIf(g -> g == game);
        gamesByPlayer.values().removeIf(g -> g == game);
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
