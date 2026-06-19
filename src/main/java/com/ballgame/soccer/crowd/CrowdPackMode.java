package com.ballgame.soccer.crowd;

/**
 * How the stadium crowd resource pack reaches players.
 */
public enum CrowdPackMode {

    /**
     * No network. Players copy {@code ballgame-crowd-pack.zip} into their Minecraft
     * resourcepacks folder once and enable it in Options.
     */
    MANUAL,

    /**
     * Plugin serves the zip over HTTP and prompts players to download on match join.
     */
    DOWNLOAD,

    /**
     * Use {@code crowd.resource-pack.url} (HTTPS) and prompt on match join.
     */
    URL;

    public static CrowdPackMode parse(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return MANUAL;
        }
    }
}
