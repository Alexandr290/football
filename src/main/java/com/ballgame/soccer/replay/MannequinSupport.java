package com.ballgame.soccer.replay;

import org.bukkit.entity.EntityType;

/**
 * Detects Mannequin entity support at runtime (1.21.4+); absent on 1.20.x servers.
 */
public final class MannequinSupport {

    private static final EntityType MANNEQUIN_TYPE;
    private static final boolean AVAILABLE;

    static {
        EntityType type = null;
        try {
            type = EntityType.valueOf("MANNEQUIN");
        } catch (IllegalArgumentException ignored) {
            // Older server versions.
        }
        MANNEQUIN_TYPE = type;
        AVAILABLE = MANNEQUIN_TYPE != null;
    }

    private MannequinSupport() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static EntityType entityType() {
        return MANNEQUIN_TYPE;
    }
}
