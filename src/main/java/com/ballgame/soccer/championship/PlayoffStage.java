package com.ballgame.soccer.championship;

public enum PlayoffStage {
    GROUP,
    /** @deprecated legacy saves only */
    ROUND_OF_32,
    /** @deprecated legacy saves only */
    ROUND_OF_16,
    QUARTER_FINAL,
    SEMI_FINAL,
    FINAL;

    public String displayKey() {
        return switch (this) {
            case GROUP -> "champ-bracket-group";
            case ROUND_OF_32 -> "champ-bracket-r32";
            case ROUND_OF_16 -> "champ-bracket-r16";
            case QUARTER_FINAL -> "champ-bracket-qf";
            case SEMI_FINAL -> "champ-bracket-sf";
            case FINAL -> "champ-bracket-final";
        };
    }

    public static PlayoffStage fromKnockoutRound(int maxRound, int round) {
        if (maxRound >= 3) {
            if (round == 1) {
                return QUARTER_FINAL;
            }
            if (round == 2) {
                return SEMI_FINAL;
            }
            return FINAL;
        }
        if (maxRound == 2) {
            if (round == 1) {
                return SEMI_FINAL;
            }
            return FINAL;
        }
        return FINAL;
    }

    public static PlayoffStage fromBracketSize(int initialBracketSize, int round) {
        int totalRounds = (int) (Math.log(initialBracketSize) / Math.log(2));
        int roundsFromEnd = totalRounds - round;
        if (roundsFromEnd <= 0) {
            return FINAL;
        }
        return switch (roundsFromEnd) {
            case 1 -> SEMI_FINAL;
            case 2 -> QUARTER_FINAL;
            default -> QUARTER_FINAL;
        };
    }

    public boolean isKnockout() {
        return this != GROUP;
    }
}
