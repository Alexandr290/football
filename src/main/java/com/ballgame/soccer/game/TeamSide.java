package com.ballgame.soccer.game;

/**
 * The two sides of a match. Kept deliberately simple; display names and colors
 * are driven by configuration via {@link com.ballgame.soccer.config.SoccerSettings}.
 */
public enum TeamSide {
    RED,
    BLUE;

    public TeamSide opposite() {
        return this == RED ? BLUE : RED;
    }
}
