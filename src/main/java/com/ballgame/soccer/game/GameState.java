package com.ballgame.soccer.game;

/**
 * The lifecycle states of a match.
 */
public enum GameState {
    /** Waiting for enough players to join. */
    WAITING("Waiting"),
    /** Lobby countdown before teleporting players in. */
    COUNTDOWN("Starting"),
    /** Kickoff countdown (ball frozen). */
    KICKOFF("Kick Off"),
    /** Active play. */
    PLAYING("In Play"),
    /** Ball out on the touchline — one team restarts from the sideline. */
    THROW_IN("Throw-In"),
    /** Offside — defending team restarts with an indirect free kick. */
    OFFSIDE("Offside"),
    /** Short break between halves. */
    HALFTIME("Half Time"),
    /** Golden-goal extra time. */
    EXTRA_TIME("Extra Time"),
    /** Penalty shootout. */
    PENALTIES("Penalties"),
    /** TV-style goal replay playback. */
    REPLAY("Replay"),
    /** Match finished, showing results. */
    ENDED("Finished");

    private final String display;

    GameState(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public boolean isLive() {
        return this == PLAYING || this == EXTRA_TIME;
    }

    /** Stadium crowd audio runs through these phases (kickoff → full time). */
    public boolean hasStadiumCrowd() {
        return switch (this) {
            case KICKOFF, PLAYING, THROW_IN, OFFSIDE, HALFTIME, EXTRA_TIME, PENALTIES, REPLAY -> true;
            default -> false;
        };
    }

    /** Player input (kick / pass) is accepted in this state. */
    public boolean acceptsPlayerKicks() {
        return this == PLAYING || this == EXTRA_TIME || this == THROW_IN || this == OFFSIDE;
    }
}
