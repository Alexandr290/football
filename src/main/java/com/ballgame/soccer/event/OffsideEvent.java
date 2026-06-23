package com.ballgame.soccer.event;

import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when an attacker is penalised for offside. Cancelling prevents the
 * whistle and play continues.
 */
public final class OffsideEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Game game;
    private final SoccerPlayer offender;
    private final TeamSide attackingTeam;
    private final TeamSide defendingTeam;
    private final Location restartSpot;
    private boolean cancelled;

    public OffsideEvent(Game game, SoccerPlayer offender, TeamSide attackingTeam,
                        TeamSide defendingTeam, Location restartSpot) {
        this.game = game;
        this.offender = offender;
        this.attackingTeam = attackingTeam;
        this.defendingTeam = defendingTeam;
        this.restartSpot = restartSpot;
    }

    public Game getGame() {
        return game;
    }

    public SoccerPlayer getOffender() {
        return offender;
    }

    public TeamSide getAttackingTeam() {
        return attackingTeam;
    }

    public TeamSide getDefendingTeam() {
        return defendingTeam;
    }

    public Location getRestartSpot() {
        return restartSpot;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
