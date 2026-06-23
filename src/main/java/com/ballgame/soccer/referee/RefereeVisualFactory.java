package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.replay.MannequinSupport;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.function.Supplier;

public final class RefereeVisualFactory {

    private RefereeVisualFactory() {
    }

    public static RefereeVisual create(SoccerPlugin plugin, SoccerSettings settings,
                                       RefereeActor.Role role, String displayName,
                                       boolean carriesFlag,
                                       Supplier<Collection<Player>> viewers) {
        String mode = settings.refereeEntityType == null
                ? "auto" : settings.refereeEntityType.toLowerCase();
        boolean preferMannequin = switch (mode) {
            case "mannequin" -> true;
            case "armor_stand", "armorstand" -> false;
            default -> MannequinSupport.isAvailable();
        };
        if (preferMannequin && MannequinSupport.isAvailable()) {
            return new MannequinRefereeVisual(plugin, settings, role, displayName, carriesFlag, viewers);
        }
        return new ArmorStandRefereeVisual(plugin, role, displayName, carriesFlag);
    }
}
