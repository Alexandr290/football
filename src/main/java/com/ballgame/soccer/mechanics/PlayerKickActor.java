package com.ballgame.soccer.mechanics;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Adapts a Bukkit player for {@link Mechanics}. */
public final class PlayerKickActor implements KickActor {

    private final Player player;

    public PlayerKickActor(Player player) {
        this.player = player;
    }

    @Override
    public Location getEyeLocation() {
        return player.getEyeLocation();
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public Vector getVelocity() {
        return player.getVelocity();
    }

    @Override
    public boolean isSprinting() {
        return player.isSprinting();
    }
}
