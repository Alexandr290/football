package com.ballgame.soccer.mechanics;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Anything that can kick the ball (human player or match bot). */
public interface KickActor {

    Location getEyeLocation();

    Location getLocation();

    Vector getVelocity();

    boolean isSprinting();
}
