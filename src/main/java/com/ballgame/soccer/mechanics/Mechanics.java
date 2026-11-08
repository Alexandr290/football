package com.ballgame.soccer.mechanics;

import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.event.BallKickEvent.KickType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure functions that turn a player's intent (look direction, movement, sprint,
 * charge) into ball impulse and spin vectors. Kept side-effect free so it can be
 * unit-reasoned about and reused by tackles, headers, dribbles and shots.
 */
public final class Mechanics {

    /** Q-key low cross: exactly 3 blocks high, 15–20 blocks along look direction. */
    private static final double CROSS_PASS_APEX_BLOCKS = 3.0;
    /**
     * Per-tick physics undershoots the continuous sqrt(2gh) formula slightly;
     * this factor lands the cross peak at exactly 3 blocks in-game.
     */
    private static final double CROSS_PASS_APEX_CALIBRATION = 1.20;
    /** Compensates horizontal slowdown from per-tick air drag during flight. */
    private static final double CROSS_PASS_RANGE_DRAG_CALIBRATION = 0.77;
    private static final double CROSS_PASS_TRAVEL_MIN = 15.0;
    private static final double CROSS_PASS_TRAVEL_MAX = 20.0;

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
        if (type == KickType.LOFT_PASS) {
            return computeLoftPass(actor, s);
        }
        if (type == KickType.CROSS_PASS) {
            return computeCrossPass(actor, s);
        }

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
            double sprintScale = type == KickType.LOFT_PASS || type == KickType.CROSS_PASS ? 0.25 : 1.0;
            impulse.add(flatLook.clone().multiply(s.sprintBonus * sprintScale));
        }

        // ---- spin (curve + lift), turned into force by the Magnus term ----
        Vector spin = new Vector(0, 0, 0);
        if (type == KickType.SHOT || type == KickType.PASS || type == KickType.LOFT_PASS
                || type == KickType.CROSS_PASS || type == KickType.THROW_IN) {
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

    /** F-key loft pass: chipped ball along look direction, apex 5–7 blocks. */
    private static Kick computeLoftPass(KickActor actor, SoccerSettings s) {
        Vector look = actor.getEyeLocation().getDirection().normalize();
        Vector flatLook = new Vector(look.getX(), 0, look.getZ());
        if (flatLook.lengthSquared() < 1.0e-6) {
            flatLook = new Vector(0, 0, 1);
        }
        flatLook.normalize();

        double power = s.loftPassHorizontalPower;
        int apexBlocks = 5 + ThreadLocalRandom.current().nextInt(3);
        double vertical = Math.sqrt(2.0 * s.gravity * apexBlocks);

        Vector impulse = flatLook.clone().multiply(power);
        impulse.setY(vertical);

        if (actor.isSprinting()) {
            impulse.add(flatLook.clone().multiply(s.sprintBonus * 0.25));
        }

        Vector spin = loftPassSpin(actor, flatLook, look, s, 0.0);
        return new Kick(impulse, spin);
    }

    /** Q-key low cross: exactly 3 blocks high, 15–20 blocks along look direction. */
    private static Kick computeCrossPass(KickActor actor, SoccerSettings s) {
        Vector look = actor.getEyeLocation().getDirection().normalize();
        Vector flatLook = new Vector(look.getX(), 0, look.getZ());
        if (flatLook.lengthSquared() < 1.0e-6) {
            flatLook = new Vector(0, 0, 1);
        }
        flatLook.normalize();

        double travelBlocks = CROSS_PASS_TRAVEL_MIN
                + ThreadLocalRandom.current().nextDouble()
                * (CROSS_PASS_TRAVEL_MAX - CROSS_PASS_TRAVEL_MIN);

        double vertical = crossPassVerticalSpeed(s);
        double horizontal = horizontalSpeedForRange(s, travelBlocks, vertical);

        Vector impulse = flatLook.clone().multiply(horizontal);
        impulse.setY(vertical);

        if (actor.isSprinting()) {
            impulse.add(flatLook.clone().multiply(s.sprintBonus * 0.12));
        }

        // No spin: Magnus lift would push the cross above the 3-block cap.
        return new Kick(impulse, new Vector(0, 0, 0));
    }

    private static double crossPassVerticalSpeed(SoccerSettings s) {
        return Math.sqrt(2.0 * s.gravity * CROSS_PASS_APEX_BLOCKS * CROSS_PASS_APEX_CALIBRATION);
    }

    private static double horizontalSpeedForRange(SoccerSettings s, double rangeBlocks, double vertical) {
        if (vertical <= 1.0e-6) {
            return s.loftPassHorizontalPower;
        }
        double flightTicks = 2.0 * vertical / s.gravity;
        return (rangeBlocks / Math.max(6.0, flightTicks)) * CROSS_PASS_RANGE_DRAG_CALIBRATION;
    }

    private static Vector loftPassSpin(KickActor actor, Vector flatLook, Vector look,
                                       SoccerSettings s, double chargeFraction) {
        Vector spin = new Vector(0, 0, 0);
        Vector move = actor.getVelocity();
        Vector flatMove = new Vector(move.getX(), 0, move.getZ());
        if (flatMove.lengthSquared() > 1.0e-4) {
            double side = flatLook.clone().crossProduct(flatMove).getY();
            spin.setY(side * s.curveStrength);
        }
        double up = Math.max(0.0, look.getY());
        if (up > 0.05) {
            Vector liftAxis = flatLook.clone().crossProduct(new Vector(0, 1, 0));
            if (liftAxis.lengthSquared() > 1.0e-6) {
                liftAxis.normalize().multiply(
                        up * s.curveStrength * 0.35 * (0.4 + chargeFraction));
                spin.add(liftAxis);
            }
        }
        return spin;
    }

    /** Bot loft pass aimed at a teammate over defenders (apex scales with distance). */
    public static Kick computeBotLoftPassToward(Location from, Location target, SoccerSettings s) {
        Vector flat = flatVector(from, target);
        double dist = flat.length();
        if (flat.lengthSquared() < 1.0e-6) {
            flat = new Vector(0, 0, 1);
            dist = 12.0;
        } else {
            flat.normalize();
        }
        int apexBlocks = (int) Math.max(4.0, Math.min(7.0, 4.0 + dist / 7.0));
        double vertical = Math.sqrt(2.0 * s.gravity * apexBlocks);
        double power = Math.max(0.22, Math.min(0.52, s.loftPassHorizontalPower * (0.75 + dist / 28.0)));
        Vector impulse = flat.clone().multiply(power);
        impulse.setY(vertical);
        return new Kick(impulse, new Vector(0, 0, 0));
    }

    /** Bot low cross aimed at a runner in the box (3-block apex). */
    public static Kick computeBotCrossPassToward(Location from, Location target, SoccerSettings s) {
        Vector flat = flatVector(from, target);
        double dist = flat.length();
        if (flat.lengthSquared() < 1.0e-6) {
            flat = new Vector(0, 0, 1);
            dist = 16.0;
        } else {
            flat.normalize();
        }
        double vertical = crossPassVerticalSpeed(s);
        double horizontal = horizontalSpeedForRange(s, Math.max(12.0, Math.min(22.0, dist)), vertical);
        Vector impulse = flat.clone().multiply(horizontal);
        impulse.setY(vertical);
        return new Kick(impulse, new Vector(0, 0, 0));
    }

    public static Kick computeBotPassToward(Location from, Location target, SoccerSettings s,
                                             double chargeFraction) {
        Vector flat = flatVector(from, target);
        if (flat.lengthSquared() < 1.0e-6) {
            flat = new Vector(0, 0, 1);
        }
        double dist = flat.length();
        flat.normalize();
        double friction = Math.max(0.8, Math.min(0.98, s.groundFriction));
        // Die just short of the receiver so the pass is taken, not smashed on.
        double desiredTravel = Math.max(3.2, dist * 0.9);
        double power = desiredTravel * (1.0 - friction);
        double bias = 0.85 + 0.25 * Math.max(0.4, Math.min(1.2, s.passPowerMultiplier));
        double frac = Math.max(0.0, Math.min(1.0, chargeFraction));
        power *= bias * (0.92 + 0.12 * frac);
        power = Math.max(0.18, Math.min(1.55, power));
        return new Kick(flat.clone().multiply(power), new Vector(0, 0, 0));
    }

    /**
     * Strike toward a point in the goal mouth using ball-to-target aim (not body
     * facing), so wide-angle finishes stay on target instead of sailing into touch.
     */
    public static Kick computeBotShotToward(Location from, Location target, SoccerSettings s,
                                            double powerFraction, double verticalLift) {
        Vector flatLook = flatVector(from, target);
        if (flatLook.lengthSquared() < 1.0e-6) {
            flatLook = new Vector(0, 0, 1);
        }
        flatLook.normalize();

        double frac = Math.max(0.0, Math.min(1.0, powerFraction));
        double power = s.lightTouchPower + (s.maxShotPower - s.lightTouchPower) * frac;

        Vector impulse = flatLook.clone().multiply(power);
        impulse.setY(Math.max(0.0, verticalLift));
        return new Kick(impulse, new Vector(0, 0, 0));
    }

    private static Vector flatVector(Location from, Location to) {
        if (from == null || to == null) {
            return new Vector();
        }
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        return v;
    }

    /**
     * A bot shot with explicitly controlled power and launch height. Unlike the
     * generic {@link #compute} SHOT path (which always lofts and made bots blast
     * the ball over the bar from close range), this drives the ball along the
     * flattened aim with only as much lift as the caller asks for, so the AI can
     * keep close-range efforts low and only loft long-range strikes.
     *
     * @param powerFraction 0..1 horizontal drive between light-touch and max power
     * @param verticalLift  absolute upward launch speed (blocks/tick)
     */
    public static Kick computeBotShot(KickActor actor, SoccerSettings s,
                                      double powerFraction, double verticalLift) {
        Vector look = actor.getEyeLocation().getDirection();
        Vector flatLook = new Vector(look.getX(), 0, look.getZ());
        if (flatLook.lengthSquared() < 1.0e-6) {
            flatLook = new Vector(0, 0, 1);
        }
        flatLook.normalize();

        double frac = Math.max(0.0, Math.min(1.0, powerFraction));
        double power = s.lightTouchPower + (s.maxShotPower - s.lightTouchPower) * frac;

        Vector impulse = flatLook.clone().multiply(power);
        impulse.setY(Math.max(0.0, verticalLift));

        // A bot on the move puts a little extra weight behind the strike, but far
        // less than a charging human so it doesn't sail long.
        if (actor.isSprinting()) {
            impulse.add(flatLook.clone().multiply(s.sprintBonus * 0.4));
        }

        // Slight curve from lateral movement keeps shots looking natural.
        Vector spin = new Vector(0, 0, 0);
        Vector move = actor.getVelocity();
        Vector flatMove = new Vector(move.getX(), 0, move.getZ());
        if (flatMove.lengthSquared() > 1.0e-4) {
            double side = flatLook.clone().crossProduct(flatMove).getY();
            spin.setY(side * s.curveStrength * 0.5);
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
