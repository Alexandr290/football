package com.ballgame.soccer.event;

import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.GameState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired whenever a match transitions between {@link GameState}s (start, kickoff,
 * half time, end, etc.). Useful for hooking external systems (bossbars, bungee,
 * statistics, holograms).
 */
public final class MatchStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Game game;
    private final GameState from;
    private final GameState to;

    public MatchStateChangeEvent(Game game, GameState from, GameState to) {
        this.game = game;
        this.from = from;
        this.to = to;
    }

    public Game getGame() {
        return game;
    }

    public GameState getFrom() {
        return from;
    }

    public GameState getTo() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
