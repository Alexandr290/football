package com.ballgame.soccer.bot;

/** Tactical role assigned from squad index — drives positioning and decisions. */
public enum BotRole {
    GOALKEEPER(0.06, 0.0, 0.15, 0.35),
    STRIKER(0.80, 0.10, 1.0, 0.35),
    MIDFIELDER(0.52, 0.0, 0.55, 0.6),
    WINGER(0.64, 0.42, 0.75, 0.45),
    PLAYMAKER(0.46, 0.16, 0.65, 0.78),
    DEFENDER(0.24, 0.08, 0.28, 0.3);

    private final double attackDepth;
    private final double lateralSpread;
    private final double chaseWeight;
    private final double passBias;

    BotRole(double attackDepth, double lateralSpread, double chaseWeight, double passBias) {
        this.attackDepth = attackDepth;
        this.lateralSpread = lateralSpread;
        this.chaseWeight = chaseWeight;
        this.passBias = passBias;
    }

    public double attackDepth() {
        return attackDepth;
    }

    public double lateralSpread() {
        return lateralSpread;
    }

    public double chaseWeight() {
        return chaseWeight;
    }

    public double passBias() {
        return passBias;
    }

    public static BotRole fromIndex(int index) {
        BotRole[] roles = values();
        return roles[Math.floorMod(index, roles.length)];
    }

    /**
     * Assign a realistic squad role from a per-team index. The first bot on each
     * side is the goalkeeper; the rest are spread across a sensible outfield shape
     * (defenders, midfielders, wingers and strikers) so a team holds its shape
     * instead of every bot crowding the ball.
     */
    public static BotRole forSquadIndex(int index) {
        if (index <= 0) {
            return GOALKEEPER;
        }
        BotRole[] outfield = {
                DEFENDER, STRIKER, MIDFIELDER, WINGER,
                DEFENDER, PLAYMAKER, WINGER, STRIKER,
                MIDFIELDER, DEFENDER
        };
        return outfield[(index - 1) % outfield.length];
    }
}
