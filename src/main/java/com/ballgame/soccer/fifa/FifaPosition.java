package com.ballgame.soccer.fifa;

import com.ballgame.soccer.bot.BotRole;

public enum FifaPosition {
    GK,
    DF,
    MF,
    FW;

    public static FifaPosition parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MF;
        }
        return switch (raw.trim().toUpperCase()) {
            case "GK", "GOALKEEPER", "G" -> GK;
            case "DF", "DEF", "DEFENDER", "CB", "LB", "RB", "LWB", "RWB" -> DF;
            case "FW", "ST", "CF", "LW", "RW", "ATTACKER", "F" -> FW;
            default -> MF;
        };
    }

    public BotRole toBotRole(int outfieldIndex) {
        return switch (this) {
            case GK -> BotRole.GOALKEEPER;
            case DF -> BotRole.DEFENDER;
            case FW -> outfieldIndex % 2 == 0 ? BotRole.STRIKER : BotRole.WINGER;
            case MF -> outfieldIndex % 3 == 0 ? BotRole.PLAYMAKER : BotRole.MIDFIELDER;
        };
    }
}
