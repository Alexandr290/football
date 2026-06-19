package com.ballgame.soccer.replay;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * A replay body shown on the pitch while real players watch from cameras.
 */
public interface ReplayActor {

    UUID sourceId();

    void spawn(Location location);

    void applyPose(PlayerPose pose, World world);

    void setGlowing(boolean glowing);

    void playSwingAnimation(Collection<Player> viewers);

    void remove();
}
