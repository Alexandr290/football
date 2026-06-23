package com.ballgame.soccer.referee;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Renders a single match official on the pitch (mannequin or armor-stand fallback).
 */
public interface RefereeVisual {

    void spawn(Location location);

    void remove();

    Location getLocation();

    void setHidden(boolean hide);

    void teleport(Location location, float yaw);

    void setLocomotion(boolean moving, boolean sprinting);

    /** Walk / run limb or sprint animation for the current tick. */
    void animateMovement(long tick, boolean sprinting);

    void showGesture(RefereeGesture gesture, long tick);

    /** Swing main hand to nearby match viewers (whistle / flag signal). */
    void swingArm(Collection<Player> viewers);

    /** True when rendered as a full player model (Mannequin) with vanilla run animations. */
    boolean usesPlayerModel();
}
