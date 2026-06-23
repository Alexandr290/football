package com.ballgame.soccer.network;

/** Which role this backend plays in a BungeeCord network. */
public enum NetworkRole {
    STANDALONE,
    LOBBY,
    GAME;

    public static NetworkRole fromConfig(String raw) {
        if (raw == null) {
            return STANDALONE;
        }
        return switch (raw.toLowerCase()) {
            case "lobby", "hub" -> LOBBY;
            case "game", "match", "soccer" -> GAME;
            default -> STANDALONE;
        };
    }
}
