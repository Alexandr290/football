package com.ballgame.soccer.replay;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.SoccerPlayer;

/**
 * Creates the best replay actor implementation for the current server.
 */
public final class ReplayActorFactory {

    private ReplayActorFactory() {
    }

    public static ReplayActor create(SoccerPlugin plugin, SoccerPlayer source, SoccerSettings settings) {
        String mode = settings.replayActorType == null ? "auto" : settings.replayActorType.toLowerCase();
        return switch (mode) {
            case "armor_stand", "armorstand" -> new ArmorStandReplayActor(source);
            case "mannequin" -> new MannequinReplayActor(plugin, source);
            default -> MannequinSupport.isAvailable()
                    ? new MannequinReplayActor(plugin, source)
                    : new ArmorStandReplayActor(source);
        };
    }
}
