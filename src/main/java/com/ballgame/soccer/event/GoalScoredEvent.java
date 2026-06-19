package com.ballgame.soccer.event;

import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a goal is scored (including own goals). Not cancellable: by the
 * time this fires the goal has been validated and counted.
 */
public final class GoalScoredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Game game;
    private final TeamSide scoringTeam;
    private final SoccerPlayer scorer;
    private final SoccerPlayer assist;
    private final boolean ownGoal;

    public GoalScoredEvent(Game game, TeamSide scoringTeam,
                           SoccerPlayer scorer,
                           SoccerPlayer assist,
                           boolean ownGoal) {
        this.game = game;
        this.scoringTeam = scoringTeam;
        this.scorer = scorer;
        this.assist = assist;
        this.ownGoal = ownGoal;
    }

    public Game getGame() {
        return game;
    }

    public TeamSide getScoringTeam() {
        return scoringTeam;
    }

    public SoccerPlayer getScorer() {
        return scorer;
    }

    public SoccerPlayer getAssist() {
        return assist;
    }

    public boolean isOwnGoal() {
        return ownGoal;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
