package com.ballgame.soccer.bot;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Head-aiming maths shared by bot bodies and cutscene stand-ins. */
final class HeadLook {

    static final double EYE_HEIGHT = 1.62;

    private HeadLook() {
    }

    static float yaw(Vector flat) {
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /**
     * Yaw/pitch for a head at {@code feet} looking at {@code target}
     * ({@code [yaw, pitch]}, Minecraft convention: positive pitch looks down).
     */
    static float[] toward(Location feet, Location target, float fallbackYaw) {
        if (feet == null || target == null) {
            return new float[] {fallbackYaw, 0f};
        }
        double dx = target.getX() - feet.getX();
        double dz = target.getZ() - feet.getZ();
        double horiz = Math.hypot(dx, dz);
        float yaw = horiz < 1.0e-3 ? fallbackYaw : (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dy = target.getY() - (feet.getY() + EYE_HEIGHT);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.35, horiz)));
        return new float[] {yaw, clampPitch(pitch)};
    }

    static float clampPitch(float pitch) {
        return Math.max(-55f, Math.min(70f, pitch));
    }

    static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    static float approachYaw(float current, float target, float maxStep) {
        float delta = wrap(target - current);
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.signum(delta) * maxStep;
    }

    static float approach(float current, float target, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.signum(delta) * maxStep;
    }
}
