package com.ballgame.soccer.crowd;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stadium crowd shouts using vanilla mob voice sounds (no resource pack).
 * Volume follows ball position: midfield — medium, near goal — loud, goal — max.
 */
public final class VanillaNpcCrowd {

    /** Midfield — medium volume. */
    public static final float VOL_MIDFIELD = 0.42f;
    /** Attacking third / near the box — loud. */
    public static final float VOL_ATTACK = 0.78f;
    /** Dangerously close to goal — very loud. */
    public static final float VOL_DANGER = 0.92f;
    /** Goal scored — maximum. */
    public static final float VOL_GOAL = 1.0f;

    private static final Sound[] SHOUTS = {
            Sound.ENTITY_VILLAGER_YES,
            Sound.ENTITY_VILLAGER_NO,
            Sound.ENTITY_VILLAGER_CELEBRATE,
            Sound.ENTITY_PILLAGER_AMBIENT,
            Sound.ENTITY_PILLAGER_CELEBRATE,
            Sound.ENTITY_GOAT_SCREAMING_AMBIENT,
            Sound.ENTITY_GOAT_SCREAMING_HURT,
            Sound.ENTITY_RAVAGER_CELEBRATE,
            Sound.ENTITY_RAVAGER_ROAR,
            Sound.ENTITY_WITCH_CELEBRATE,
    };

    private static final Sound[] GOAL_SHOUTS = {
            Sound.ENTITY_RAVAGER_CELEBRATE,
            Sound.ENTITY_RAVAGER_ROAR,
            Sound.ENTITY_PILLAGER_CELEBRATE,
            Sound.ENTITY_GOAT_SCREAMING_HURT,
            Sound.ENTITY_GOAT_SCREAMING_AMBIENT,
            Sound.ENTITY_VILLAGER_CELEBRATE,
            Sound.ENTITY_WITCH_CELEBRATE,
    };

    private VanillaNpcCrowd() {
    }

    /**
     * @return 0 when the ball is at the centre circle, 1 when it is on a goal line.
     */
    public static double attackFactor(Arena arena, Location ballLoc) {
        if (ballLoc == null) {
            return 0.0;
        }
        Location red = goalCenter(arena, TeamSide.RED);
        Location blue = goalCenter(arena, TeamSide.BLUE);
        if (red == null || blue == null) {
            return fallbackThreat(arena, ballLoc);
        }

        double axisX = blue.getX() - red.getX();
        double axisZ = blue.getZ() - red.getZ();
        double lenSq = axisX * axisX + axisZ * axisZ;
        if (lenSq < 1e-4) {
            return fallbackThreat(arena, ballLoc);
        }

        double bx = ballLoc.getX() - red.getX();
        double bz = ballLoc.getZ() - red.getZ();
        double t = (bx * axisX + bz * axisZ) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.abs(t - 0.5) * 2.0;
    }

    public static float volumeForAttackFactor(double attackFactor) {
        if (attackFactor >= 0.82) {
            return lerp(VOL_ATTACK, VOL_DANGER, (attackFactor - 0.82) / 0.18);
        }
        if (attackFactor >= 0.38) {
            return lerp(VOL_MIDFIELD, VOL_ATTACK, (attackFactor - 0.38) / 0.44);
        }
        return VOL_MIDFIELD;
    }

    public static int shoutIntervalTicks(double attackFactor) {
        return (int) Math.max(10, 48 - attackFactor * 34);
    }

    public static void playShout(Player player, List<Location> stands, float volume, double crowdVolumeScale) {
        playShout(player, stands, pick(SHOUTS), volume, crowdVolumeScale, randomPitch(0.88f, 1.14f));
    }

    public static void playGoalBurst(Player player, List<Location> stands, double crowdVolumeScale) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int bursts = 4 + rng.nextInt(3);
        for (int i = 0; i < bursts; i++) {
            Sound sound = GOAL_SHOUTS[rng.nextInt(GOAL_SHOUTS.length)];
            float vol = VOL_GOAL - i * 0.06f;
            float pitch = randomPitch(0.85f, 1.2f);
            playShout(player, stands, sound, vol, crowdVolumeScale, pitch);
        }
    }

    public static List<Location> buildStandPoints(Arena arena) {
        List<Location> points = new ArrayList<>();
        Cuboid field = arena.getField();
        if (field == null) {
            Location fallback = standFallback(arena);
            if (fallback != null) {
                points.add(fallback);
            }
            return points;
        }

        World world = field.getWorld();
        double y = field.getCenter().getY() + 5.0;
        double minX = field.getMinX() - 2.0;
        double maxX = field.getMaxX() + 2.0;
        double minZ = field.getMinZ() - 2.0;
        double maxZ = field.getMaxZ() + 2.0;
        double midX = (field.getMinX() + field.getMaxX()) / 2.0;
        double midZ = (field.getMinZ() + field.getMaxZ()) / 2.0;

        addStand(points, world, minX, y, minZ);
        addStand(points, world, maxX, y, minZ);
        addStand(points, world, minX, y, maxZ);
        addStand(points, world, maxX, y, maxZ);
        addStand(points, world, midX, y, minZ);
        addStand(points, world, midX, y, maxZ);
        addStand(points, world, minX, y, midZ);
        addStand(points, world, maxX, y, midZ);
        return points;
    }

    private static void playShout(Player player, List<Location> stands, Sound sound,
                                  float volume, double crowdVolumeScale, float pitch) {
        if (!player.isOnline()) {
            return;
        }
        Location at = pickStand(stands, player.getLocation());
        float scaled = (float) (volume * crowdVolumeScale);
        player.playSound(at, sound, SoundCategory.NEUTRAL, scaled, pitch);
    }

    private static Location pickStand(List<Location> stands, Location near) {
        if (stands.isEmpty()) {
            return near;
        }
        if (stands.size() == 1) {
            return stands.get(0);
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (near == null || near.getWorld() == null) {
            return stands.get(rng.nextInt(stands.size()));
        }
        Location best = stands.get(0);
        double bestDist = Double.MAX_VALUE;
        int samples = Math.min(4, stands.size());
        for (int i = 0; i < samples; i++) {
            Location candidate = stands.get(rng.nextInt(stands.size()));
            if (!candidate.getWorld().equals(near.getWorld())) {
                continue;
            }
            double dist = candidate.distanceSquared(near);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static void addStand(List<Location> points, World world, double x, double y, double z) {
        points.add(new Location(world, x, y, z));
    }

    private static Location standFallback(Arena arena) {
        Location spec = arena.getSpectator();
        if (spec != null) {
            return spec.clone();
        }
        if (arena.getField() != null) {
            return arena.getField().getCenter().clone().add(0, 6, 0);
        }
        if (arena.getBallSpawn() != null) {
            return arena.getBallSpawn().clone().add(0, 6, 0);
        }
        return null;
    }

    private static Location goalCenter(Arena arena, TeamSide defendedBy) {
        if (arena.getGoal(defendedBy) == null) {
            return null;
        }
        return arena.getGoal(defendedBy).getCenter();
    }

    private static double fallbackThreat(Arena arena, Location ballLoc) {
        double best = 0.0;
        for (TeamSide side : TeamSide.values()) {
            Location center = goalCenter(arena, side);
            if (center == null) {
                continue;
            }
            double dx = ballLoc.getX() - center.getX();
            double dz = ballLoc.getZ() - center.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            best = Math.max(best, 1.0 - Math.min(1.0, horiz / 24.0));
        }
        return best;
    }

    private static float lerp(float a, float b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return (float) (a + (b - a) * t);
    }

    private static Sound pick(Sound[] sounds) {
        return sounds[ThreadLocalRandom.current().nextInt(sounds.length)];
    }

    private static float randomPitch(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }
}
