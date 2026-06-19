package com.ballgame.soccer.event;

import com.ballgame.soccer.ball.Ball;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;

/**
 * Fired just before an impulse from a player is applied to the ball. Cancelling
 * prevents the touch. The impulse and spin vectors are mutable so other plugins
 * can tweak the physics of a kick (e.g. power-ups).
 */
public final class BallKickEvent extends Event implements Cancellable {

    public enum KickType {
        TOUCH, PASS, SHOT, TACKLE, HEADER, THROW_IN
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Ball ball;
    private final KickType type;
    private Vector impulse;
    private Vector spin;
    private boolean cancelled;

    public BallKickEvent(Player player, Ball ball, KickType type, Vector impulse, Vector spin) {
        this.player = player;
        this.ball = ball;
        this.type = type;
        this.impulse = impulse;
        this.spin = spin;
    }

    public Player getPlayer() {
        return player;
    }

    public Ball getBall() {
        return ball;
    }

    public KickType getType() {
        return type;
    }

    public Vector getImpulse() {
        return impulse;
    }

    public void setImpulse(Vector impulse) {
        this.impulse = impulse;
    }

    public Vector getSpin() {
        return spin;
    }

    public void setSpin(Vector spin) {
        this.spin = spin;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
