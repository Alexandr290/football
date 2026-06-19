package com.ballgame.soccer.crowd;

import org.bukkit.Sound;

/**
 * Custom stadium sounds (resource pack) with vanilla fallbacks when the pack is unavailable.
 */
public enum CrowdSound {

    AMBIENCE_LOW("ballgame:crowd.ambience_low", Sound.BLOCK_PORTAL_AMBIENT, 0.18f),
    AMBIENCE_MEDIUM("ballgame:crowd.ambience_medium", Sound.ENTITY_PILLAGER_AMBIENT, 0.22f),
    AMBIENCE_HIGH("ballgame:crowd.ambience_high", Sound.ENTITY_RAVAGER_CELEBRATE, 0.28f),
    CHANT("ballgame:crowd.chant", Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.35f),
    GOAL_CHEER("ballgame:crowd.goal_cheer", Sound.ENTITY_PILLAGER_CELEBRATE, 0.65f),
    APPLAUSE("ballgame:crowd.applause", Sound.ENTITY_VILLAGER_YES, 0.4f),
    GASP("ballgame:crowd.gasp", Sound.ENTITY_VILLAGER_NO, 0.45f),
    STINGER("ballgame:crowd.stinger", Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.35f),
    KICKOFF("ballgame:crowd.kickoff", Sound.ENTITY_RAVAGER_CELEBRATE, 0.35f);

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
