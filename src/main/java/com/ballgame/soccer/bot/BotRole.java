package com.ballgame.soccer.bot;

/** Tactical role assigned from squad index — drives positioning and decisions. */
public enum BotRole {
    STRIKER(0.78, 0.08, 1.0, 0.35),
    MIDFIELDER(0.52, 0.0, 0.85, 0.55),
    WINGER(0.62, 0.38, 0.95, 0.45),
    PLAYMAKER(0.44, 0.14, 0.7, 0.75),
    DEFENDER(0.26, 0.06, 0.55, 0.25);

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
}
