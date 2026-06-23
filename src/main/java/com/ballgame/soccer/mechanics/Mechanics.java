package com.ballgame.soccer.mechanics;

import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.event.BallKickEvent.KickType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Pure functions that turn a player's intent (look direction, movement, sprint,
 * charge) into ball impulse and spin vectors. Kept side-effect free so it can be
 * unit-reasoned about and reused by tackles, headers, dribbles and shots.
 */
public final class Mechanics {

    /** Result of a kick computation: the linear impulse plus imparted spin. */
    public static final class Kick {
        public final Vector impulse;
        public final Vector spin;

        public Kick(Vector impulse, Vector spin) {
            this.impulse = impulse;
            this.spin = spin;
        }
    }

    private Mechanics() {
    }

    /**
     * Build the impulse + spin for a player action against the ball.
     *
     * @param chargeFraction 0..1 charge level for shots/passes
     */
    public static Kick compute(Player player, SoccerSettings s, KickType type, double chargeFraction) {
        return compute(new PlayerKickActor(player), s, type, chargeFraction);
    }

    public static Kick compute(KickActor actor, SoccerSettings s, KickType type, double chargeFraction) {
        Vector look = actor.getEyeLocation().getDirection().normalize();
        Vector flatLook = new Vector(look.getX(), 0, look.getZ());
        if (flatLook.lengthSquared() < 1.0e-6) {
            flatLook = new Vector(0, 0, 1);
        }
        flatLook.normalize();

        // power     = horizontal drive along the (flattened) aim direction.
        // vertical  = upward launch speed. PASS keeps this at 0 so the ball
        //             rolls along the ground; SHOT always lofts so the ball
        //             flies up and arcs back down regardless of where you look.
        double power;
        double vertical;
        switch (type) {
            case PASS:
                // Right-click: a ground pass that rolls forward.
                power = (s.lightTouchPower
                        + (s.maxShotPower - s.lightTouchPower) * 0.5 * chargeFraction)
                        * s.passPowerMultiplier;
                vertical = 0.0;
                break;
            case THROW_IN:
                // Throw-in restart: put the ball back in play along the ground.
                power = (s.lightTouchPower
                        + (s.maxShotPower - s.lightTouchPower) * 0.35)
                        * s.passPowerMultiplier * 1.15;
                vertical = 0.0;
                break;
            case SHOT:
                // Left-click: a lofted strike that always rises and falls.
                power = s.lightTouchPower
                        + (s.maxShotPower - s.lightTouchPower) * chargeFraction;
                vertical = s.shotLift * (0.7 + 0.9 * chargeFraction) + power * 0.18;
                break;
            case TACKLE:
                power = s.tacklePower;
                vertical = s.shotLift * 0.15;
                break;
            case HEADER:
                power = s.lightTouchPower * 1.6;
                vertical = s.shotLift * 0.9;
                break;
            case TOUCH:
            default:
                power = s.lightTouchPower;
                vertical = 0.04;
                break;
        }

        // Horizontal drive always follows the flattened aim so that looking up
        // or down doesn't shorten the shot - it only changes how high it goes
        // (which is controlled explicitly by `vertical`).
        Vector impulse = flatLook.clone().multiply(power);
        impulse.setY(vertical);

        // Sprinting adds the player's forward momentum to the strike.
        if (actor.isSprinting()) {
            impulse.add(flatLook.clone().multiply(s.sprintBonus));
        }

        // ---- spin (curve + lift), turned into force by the Magnus term ----
        Vector spin = new Vector(0, 0, 0);
        if (type == KickType.SHOT || type == KickType.PASS || type == KickType.THROW_IN) {
            // Side-spin: striking across the ball while moving sideways relative
            // to your aim bends the ball's flight left or right.
            Vector move = actor.getVelocity();
            Vector flatMove = new Vector(move.getX(), 0, move.getZ());
            if (flatMove.lengthSquared() > 1.0e-4) {
                double side = flatLook.clone().crossProduct(flatMove).getY();
                spin.setY(side * s.curveStrength);
            }

            // Back-spin: lofted strikes get back-spin, whose Magnus force adds a
            // touch of lift so the ball floats and then dips - like a real chip
            // or a dipping long-range effort.
            double up = Math.max(0.0, look.getY());
            if (up > 0.05) {
                Vector liftAxis = flatLook.clone().crossProduct(new Vector(0, 1, 0));
                if (liftAxis.lengthSquared() > 1.0e-6) {
                    liftAxis.normalize().multiply(
                            up * s.curveStrength * 0.35 * (0.4 + chargeFraction));
                    spin.add(liftAxis);
                }
            }
        }
        return new Kick(impulse, spin);
    }

    /**
     * A soft directional touch used for dribbling: nudges the ball just ahead of
     * the player so it stays at their feet while running.
     */
    public static Vector dribbleNudge(Player player, SoccerSettings s) {
        return dribbleNudge(new PlayerKickActor(player), s);
    }

    public static Vector dribbleNudge(KickActor actor, SoccerSettings s) {
        Vector look = actor.getLocation().getDirection();
        Vector flat = new Vector(look.getX(), 0, look.getZ());
        if (flat.lengthSquared() < 1.0e-6) {
            return new Vector(0, 0, 0);
        }
        flat.normalize();
        double power = s.lightTouchPower * (actor.isSprinting() ? 0.85 : 0.6);
        return flat.multiply(power);
    }
}
