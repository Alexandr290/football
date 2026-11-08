package com.ballgame.soccer.game;

import org.bukkit.WeatherType;

/** Rules and presentation chosen for one exhibition match. */
public record MatchOptions(
        int durationSeconds,
        int halves,
        long playerTime,
        WeatherType playerWeather
) {
    public MatchOptions {
        durationSeconds = Math.max(60, Math.min(1800, durationSeconds));
        halves = Math.max(1, Math.min(2, halves));
        playerTime = Math.floorMod(playerTime, 24000L);
        playerWeather = playerWeather != null ? playerWeather : WeatherType.CLEAR;
    }
}
