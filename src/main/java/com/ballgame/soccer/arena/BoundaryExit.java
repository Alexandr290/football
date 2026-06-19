package com.ballgame.soccer.arena;

/**
 * How the ball left the pitch boundary.
 */
public enum BoundaryExit {
    /** Still inside the playable area. */
    IN_PLAY,
    /** Crossed a touchline (sideline) — throw-in restart. */
    TOUCHLINE,
    /** Crossed a goal line (or a corner) — not a throw-in. */
    GOAL_LINE,
    /** Fell well below the pitch (void / drop). */
    BELOW_PITCH
}
