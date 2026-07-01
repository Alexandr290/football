package com.ballgame.soccer.stats;

import java.util.Locale;
import java.util.function.ToIntFunction;

/** Sortable stat columns shown in chat leaderboards. */
public enum LeaderboardCategory {

    GOALS("goals", PlayerStats::getGoals, "leaderboard-category-goals"),
    WINS("wins", PlayerStats::getWins, "leaderboard-category-wins"),
    ASSISTS("assists", PlayerStats::getAssists, "leaderboard-category-assists"),
    TACKLES("tackles", PlayerStats::getTackles, "leaderboard-category-tackles"),
    SAVES("saves", PlayerStats::getSaves, "leaderboard-category-saves"),
    CLEAN_SHEETS("clean-sheets", PlayerStats::getCleanSheets, "leaderboard-category-clean-sheets");

    private final String id;
    private final ToIntFunction<PlayerStats> value;
    private final String messageKey;

    LeaderboardCategory(String id, ToIntFunction<PlayerStats> value, String messageKey) {
        this.id = id;
        this.value = value;
        this.messageKey = messageKey;
    }

    public String id() {
        return id;
    }

    public ToIntFunction<PlayerStats> value() {
        return value;
    }

    public String messageKey() {
        return messageKey;
    }

    public static LeaderboardCategory parse(String input) {
        if (input == null || input.isBlank()) {
            return GOALS;
        }
        String normalized = input.toLowerCase(Locale.ROOT).replace('_', '-');
        for (LeaderboardCategory category : values()) {
            if (category.id.equals(normalized)) {
                return category;
            }
        }
        return GOALS;
    }
}
