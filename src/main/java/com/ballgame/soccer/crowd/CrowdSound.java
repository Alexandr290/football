package com.ballgame.soccer.crowd;

import org.bukkit.Sound;

/**
 * Custom stadium sounds (resource pack) with vanilla fallbacks when the pack is unavailable.
 */
public enum CrowdSound {

    AMBIENCE_LOW("ballgame:crowd.ambience_low", Sound.BLOCK_NOTE_BLOCK_HAT, 0.18f),
    AMBIENCE_MEDIUM("ballgame:crowd.ambience_medium", Sound.BLOCK_NOTE_BLOCK_PLING, 0.22f),
    AMBIENCE_HIGH("ballgame:crowd.ambience_high", Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.28f),
    CHANT("ballgame:crowd.chant", Sound.BLOCK_NOTE_BLOCK_BASS, 0.35f),
    GOAL_CHEER("ballgame:crowd.goal_cheer", Sound.ENTITY_PLAYER_LEVELUP, 0.65f),
    APPLAUSE("ballgame:crowd.applause", Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f),
    GASP("ballgame:crowd.gasp", Sound.BLOCK_NOTE_BLOCK_BELL, 0.45f),
    STINGER("ballgame:crowd.stinger", Sound.BLOCK_NOTE_BLOCK_PLING, 0.35f),
    KICKOFF("ballgame:crowd.kickoff", Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.35f);

    private final String key;
    private final Sound fallback;
    private final float defaultVolume;

    CrowdSound(String key, Sound fallback, float defaultVolume) {
        this.key = key;
        this.fallback = fallback;
        this.defaultVolume = defaultVolume;
    }

    public String key() {
        return key;
    }

    public Sound fallback() {
        return fallback;
    }

    public float defaultVolume() {
        return defaultVolume;
    }
}
