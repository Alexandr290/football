package com.ballgame.soccer.replay;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Computes broadcast-style camera positions for goal replays.
 */
public final class ReplayCamera {

    public enum Style {
        CHASE,
        BROADCAST,
        CINEMATIC
    }

    private ReplayCamera() {
    }

    public static Location compute(Style style, double progress, Location ball,
                                   Location prevBall, Arena arena,
                                   TeamSide scoringTeam, PlayerPose scorerPose) {
        Style effective = style == Style.CINEMATIC ? cinematicPhase(progress) : style;
        Location cam = switch (effective) {
            case CHASE -> chaseCamera(ball, prevBall);
            case BROADCAST -> broadcastCamera(ball, arena);
            case CINEMATIC -> chaseCamera(ball, prevBall);
        };
        if (effective == Style.CINEMATIC && progress >= 0.72) {
            cam = blend(cam, goalLineCamera(ball, arena, scoringTeam), smoothStep((progress - 0.72) / 0.28));
        }
        if (scorerPose != null && progress >= 0.45 && progress < 0.62) {
            Location scorerCam = scorerCamera(scorerPose.toLocation(ball.getWorld()), ball);
            cam = blend(cam, scorerCam, smoothStep((progress - 0.45) / 0.17));
        }
        cam.setDirection(ball.toVector().subtract(cam.toVector()).normalize());
        return cam;
    }

    private static Style cinematicPhase(double progress) {
        if (progress < 0.35) {
            return Style.CHASE;
        }
        if (progress < 0.72) {
            return Style.BROADCAST;
        }
        return Style.CHASE;
    }

    private static Location chaseCamera(Location ball, Location prevBall) {
        Vector move = flatDirection(ball, prevBall);
        Location cam = ball.clone().subtract(move.multiply(4.2)).add(0, 1.85, 0);
        return cam;
    }

    private static Location broadcastCamera(Location ball, Arena arena) {
        Location center = arena.getSpectator();
        if (center == null && arena.getField() != null) {
            center = arena.getField().getCenter().clone().add(0, 14, 0);
        } else if (center == null) {
            center = ball.clone().add(0, 12, 0);
        } else {
            center = center.clone();
        }
        Vector towardBall = ball.toVector().subtract(center.toVector());
        if (towardBall.lengthSquared() > 0.01) {
            center.setDirection(towardBall.normalize());
        }
        return center;
    }

    private static Location goalLineCamera(Location ball, Arena arena, TeamSide scoringTeam) {
        TeamSide defendedGoal = scoringTeam.opposite();
        Cuboid goal = arena.getGoal(defendedGoal);
        if (goal == null) {
            return chaseCamera(ball, ball);
        }
        double midX = (goal.getMinX() + goal.getMaxX()) / 2.0;
        double midZ = (goal.getMinZ() + goal.getMaxZ()) / 2.0;
        double outsideX = midX;
        double outsideZ = midZ;
        if (arena.getField() != null) {
            Location fieldCenter = arena.getField().getCenter();
            outsideX = midX + Math.signum(midX - fieldCenter.getX()) * 3.5;
            outsideZ = midZ + Math.signum(midZ - fieldCenter.getZ()) * 3.5;
        }
        return new Location(ball.getWorld(), outsideX, ball.getY() + 1.4, outsideZ);
    }

    private static Location scorerCamera(Location scorer, Location ball) {
        Vector behind = flatDirection(scorer, ball).multiply(-1);
        return scorer.clone().add(behind.multiply(2.8)).add(0, 1.6, 0);
    }

    private static Vector flatDirection(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) {
            dir.setX(0).setY(0).setZ(-1);
        }
        return dir.normalize();
    }

    private static Location blend(Location a, Location b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Location(a.getWorld(),
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t,
                a.getYaw(), a.getPitch());
    }

    private static double smoothStep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
