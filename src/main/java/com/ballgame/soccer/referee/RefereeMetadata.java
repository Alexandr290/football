package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.metadata.FixedMetadataValue;

/**
 * Marks match referee entities so protection listeners can ignore player clicks.
 */
public final class RefereeMetadata {

    public static final String KEY = "soccer-referee";

    private RefereeMetadata() {
    }

    public static void tag(SoccerPlugin plugin, Entity entity, String role) {
        entity.setMetadata(KEY, new FixedMetadataValue(plugin, role));
    }

    public static boolean isReferee(Entity entity) {
        return entity != null && entity.hasMetadata(KEY);
    }
}
