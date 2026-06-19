package com.ballgame.soccer.replay;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Snapshot of a player's position, facing, and short-lived animation state.
 */
public final class PlayerPose {

    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final boolean sneaking;
    public final boolean sprinting;
    public final boolean armSwing;
    public final boolean interacting;

    public PlayerPose(double x, double y, double z, float yaw, float pitch,
                        boolean sneaking, boolean sprinting,
                        boolean armSwing, boolean interacting) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
        this.armSwing = armSwing;
        this.interacting = interacting;
    }

    public PlayerPose(Location loc, boolean sneaking, boolean sprinting,
                      boolean armSwing, boolean interacting) {
        this(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                sneaking, sprinting, armSwing, interacting);
    }

    public static PlayerPose capture(Player player, ReplayAnimationTracker tracker) {
        UUID id = player.getUniqueId();
        return new PlayerPose(
                player.getLocation(),
                player.isSneaking(),
                player.isSprinting(),
                tracker.isArmSwinging(id),
                tracker.isInteracting(id));
    }

    public Location toLocation(org.bukkit.World world) {
        Location loc = new Location(world, x, y, z, yaw, pitch);
        if (sneaking) {
            loc.subtract(0, 0.15, 0);
        }
        return loc;
    }

    public PlayerPose lerp(PlayerPose other, double t) {
        boolean blendBool = t < 0.5;
        return new PlayerPose(
                x + (other.x - x) * t,
                y + (other.y - y) * t,
                z + (other.z - z) * t,
                lerpAngle(yaw, other.yaw, (float) t),
                (float) (pitch + (other.pitch - pitch) * t),
                blendBool ? sneaking : other.sneaking,
                blendBool ? sprinting : other.sprinting,
                blendBool ? armSwing : other.armSwing,
                blendBool ? interacting : other.interacting);
    }

    private static float lerpAngle(float from, float to, float t) {
        float delta = wrapDegrees(to - from);
        return from + delta * t;
    }

    private static float wrapDegrees(float degrees) {
        degrees %= 360f;
        if (degrees >= 180f) {
            degrees -= 360f;
        }
        if (degrees < -180f) {
            degrees += 360f;
        }
        return degrees;
    }
}
