package com.ballgame.soccer.arena;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Touchline throw-in spot and the direction back onto the pitch. */
public record ThrowInGeometry(
        Location spot,
        Vector inward,
        boolean touchlineOnX,
        boolean exitFromMin,
        Location lineStart,
        Location lineEnd) {

    public ThrowInGeometry(Location spot, Vector inward, boolean touchlineOnX, boolean exitFromMin) {
        this(spot, inward, touchlineOnX, exitFromMin, null, null);
    }

    public boolean customLine() {
        return lineStart != null && lineEnd != null;
    }

    public Vector outward() {
        return inward.clone().multiply(-1);
    }
}
