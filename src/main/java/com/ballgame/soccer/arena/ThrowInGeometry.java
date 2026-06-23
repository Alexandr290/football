package com.ballgame.soccer.arena;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Touchline throw-in spot and the direction back onto the pitch. */
public record ThrowInGeometry(Location spot, Vector inward, boolean touchlineOnX, boolean exitFromMin) {

    public Vector outward() {
        return inward.clone().multiply(-1);
    }
}
