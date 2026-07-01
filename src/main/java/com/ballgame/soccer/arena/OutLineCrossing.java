package com.ballgame.soccer.arena;

import org.bukkit.Location;

/** Result of the ball crossing a marked touchline segment. */
public record OutLineCrossing(Location exitPoint, Location throwInSpot, ThrowInGeometry geometry) {
}
