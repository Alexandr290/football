package com.ballgame.soccer.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helper for translating color codes, including hex codes in the
 * form {@code &#RRGGBB}, into Bukkit's legacy color format.
 */
public final class Text {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private Text() {
    }

    /**
     * Colorize a string, supporting both legacy {@code &} codes and hex
     * {@code &#RRGGBB} codes (1.16+).
     *
     * @param input the raw string (may be null)
     * @return the colorized string, or empty string if input was null
     */
    public static String color(String input) {
        if (input == null) {
            return "";
        }
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char c : hex.toCharArray()) {
                replacement.append('\u00a7').append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    /**
     * Strip all color codes from a string.
     */
    public static String strip(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.stripColor(color(input));
    }

    /**
     * Format a number of total seconds as {@code M:SS}.
     */
    public static String formatTime(int totalSeconds) {
        if (totalSeconds < 0) {
            totalSeconds = 0;
        }
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : seconds);
    }
}
