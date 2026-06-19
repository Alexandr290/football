package com.ballgame.soccer.game;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
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

    private final SoccerPlugin plugin;
    private final Map<String, Game> gamesByArena = new HashMap<>();
    private final Map<UUID, Game> gamesByPlayer = new HashMap<>();
    private BukkitTask tickTask;

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
    }

    /**
     * Join (or create and join) the match for a given arena.
     *
     * @return true if the player joined.
     */
    public boolean join(Player player, Arena arena) {
        if (gamesByPlayer.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "already-in-game");
            return false;
        }
        if (!arena.isPlayable()) {
            plugin.messages().send(player, "arena-not-ready");
            return false;
        }
        Game game = gamesByArena.get(key(arena));
        if (game == null || game.isEnded()) {
            game = new Game(plugin, arena);
            gamesByArena.put(key(arena), game);
        }
        if (game.isFull()) {
            plugin.messages().send(player, "game-full");
            return false;
        }
        if (!plugin.settings().allowRejoin && game.getState() != GameState.WAITING
                && game.getState() != GameState.COUNTDOWN) {
            plugin.messages().send(player, "match-already-started");
            return false;
        }
        if (game.addPlayer(player)) {
            gamesByPlayer.put(player.getUniqueId(), game);
            return true;
        }
        return false;
    }

    /**
     * Auto-pick the best available arena (most populated joinable match, else any
     * empty playable arena) and join it.
     */
    public boolean quickJoin(Player player) {
        Game best = null;
        for (Game game : gamesByArena.values()) {
            if (game.isEnded() || game.isFull()) {
                continue;
            }
            if (game.getState() == GameState.WAITING || game.getState() == GameState.COUNTDOWN) {
                if (best == null || game.playerCount() > best.playerCount()) {
                    best = game;
                }
            }
        }
        if (best != null) {
            return join(player, best.getArena());
        }
        for (Arena arena : plugin.arenas().getArenas()) {
            if (arena.isPlayable() && !gamesByArena.containsKey(key(arena))) {
                return join(player, arena);
            }
        }
        plugin.messages().send(player, "no-arenas-available");
        return false;
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
        Game game = findGameByFixture(championshipId, fixtureId);
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

    private Game findGameByFixture(String championshipId, String fixtureId) {
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
    }

    /** Called by a {@link Game} when it finishes. */
    public void endGame(Game game) {
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

    private String key(Arena arena) {
        return arena.getName().toLowerCase(Locale.ROOT);
    }
}
