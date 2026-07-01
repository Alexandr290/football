package com.ballgame.soccer.bot;

import com.ballgame.soccer.config.SoccerSettings;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Player-like locomotion: acceleration, braking, and smooth turning instead of
 * snapping in a straight line every tick.
 */
final class BotMovement {

    private static final double WALK_BLOCKS_PER_TICK = 0.216;
    private static final double SPRINT_BLOCKS_PER_TICK = 0.32;
    private static final double ACCEL_PER_TICK = 0.046;
    private static final double DECEL_PER_TICK = 0.055;
    private static final double MAX_TURN_DEG_WALK = 42.0;
    private static final double MAX_TURN_DEG_SPRINT = 34.0;
    private static final double TURN_SPEED_PENALTY = 0.62;

    private Vector heading = new Vector(0, 0, 1);
    private double speed;

    static double configuredWalkSpeed(SoccerSettings settings) {
        return settings != null && settings.botWalkSpeed > 0.0
                ? settings.botWalkSpeed
                : WALK_BLOCKS_PER_TICK;
    }

    static double configuredSprintSpeed(SoccerSettings settings) {
        return settings != null && settings.botSprintSpeed > 0.0
                ? settings.botSprintSpeed
                : SPRINT_BLOCKS_PER_TICK;
    }

    /**
     * Advances {@code actor} toward {@code target} and returns the new flat velocity.
     *
     * @param speedMul per-bot top-speed multiplier (pace + fatigue)
     * @param accelMul per-bot acceleration/braking multiplier
     * @param agility  per-bot turning sharpness multiplier
     */
    Result advance(MatchBot actor, Location target, boolean sprint, SoccerSettings settings,
                   double speedMul, double accelMul, double agility) {
        Location location = actor.getLocation();
        if (location == null || target == null) {
            return Result.stopped(new Vector(0, 0, 0), false);
        }

        Vector toTarget = target.toVector().subtract(location.toVector());
        toTarget.setY(0);
        double dist = toTarget.length();
        if (dist < 0.06) {
            // Glide to a halt rather than snapping dead — preserves footwork feel.
            speed = Math.max(0.0, speed - DECEL_PER_TICK * accelMul);
            if (speed < 1.0e-3) {
                speed = 0.0;
                return Result.stopped(new Vector(0, 0, 0), false);
            }
            Vector coast = heading.clone().multiply(speed);
            location.add(coast);
            location.setPitch(0f);
            return new Result(coast, false, location.getYaw());
        }

        Vector desired = toTarget.clone().multiply(1.0 / dist);
        if (heading.lengthSquared() < 1.0e-6) {
            heading = directionFromYaw(location.getYaw());
        }

        double walk = configuredWalkSpeed(settings) * Math.max(0.4, speedMul);
        double run = configuredSprintSpeed(settings) * Math.max(0.4, speedMul);
        double maxSpeed = sprint ? run : walk;
        double desiredSpeed = maxSpeed;
        // Ease off the throttle when approaching the target so the bot arrives
        // under control instead of overshooting and oscillating.
        if (dist < maxSpeed * 2.8) {
            desiredSpeed = Math.max(maxSpeed * 0.42, dist * 0.72);
        }

        double turnAngle = angleBetween(heading, desired);
        double maxTurn = (sprint ? MAX_TURN_DEG_SPRINT : MAX_TURN_DEG_WALK) * Math.max(0.5, agility);
        heading = rotateToward(heading, desired, maxTurn);
        // Hard cuts scrub speed — you can't turn at full pace.
        if (turnAngle > 60.0) {
            desiredSpeed *= TURN_SPEED_PENALTY * 0.8;
        } else if (turnAngle > 18.0) {
            desiredSpeed *= TURN_SPEED_PENALTY;
        }

        double sprintBurst = sprint ? 1.18 : 1.0;
        double accel = ACCEL_PER_TICK * Math.max(0.4, accelMul) * sprintBurst;
        double decel = DECEL_PER_TICK * Math.max(0.4, accelMul);
        if (speed < desiredSpeed) {
            speed = Math.min(desiredSpeed, speed + accel);
        } else {
            speed = Math.max(desiredSpeed, speed - decel);
        }

        Vector velocity = heading.clone().multiply(speed);
        location.add(velocity);
        float yaw = yawToward(heading);
        location.setYaw(yaw);
        location.setPitch(0f);

        boolean sprinting = sprint && speed > walk * 0.9 && dist > 1.0;
        return new Result(velocity, sprinting, yaw);
    }

    void reset() {
        heading = new Vector(0, 0, 1);
        speed = 0.0;
    }

    void seedFromYaw(float yaw) {
        heading = directionFromYaw(yaw);
        speed = 0.0;
    }

    private static Vector directionFromYaw(float yaw) {
        double rad = Math.toRadians(yaw);
        return new Vector(-Math.sin(rad), 0, Math.cos(rad));
    }

    private static float yawToward(Vector flat) {
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    private static double angleBetween(Vector from, Vector to) {
        if (from.lengthSquared() < 1.0e-6 || to.lengthSquared() < 1.0e-6) {
            return 0.0;
        }
        Vector a = from.clone().normalize();
        Vector b = to.clone().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, a.dot(b)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static Vector rotateToward(Vector from, Vector to, double maxDegrees) {
        if (from.lengthSquared() < 1.0e-6) {
            return to.clone().normalize();
        }
        if (to.lengthSquared() < 1.0e-6) {
            return from.clone().normalize();
        }
        Vector a = from.clone().normalize();
        Vector b = to.clone().normalize();
        double angle = angleBetween(a, b);
        if (angle <= maxDegrees || angle < 1.0e-3) {
            return b;
        }
        double t = maxDegrees / angle;
        Vector blended = a.multiply(1.0 - t).add(b.multiply(t));
        if (blended.lengthSquared() < 1.0e-6) {
            return b;
        }
        return blended.normalize();
    }

    record Result(Vector velocity, boolean sprinting, float yaw) {
        static Result stopped(Vector velocity, boolean sprinting) {
            return new Result(velocity, sprinting, 0f);
        }
    }
}
