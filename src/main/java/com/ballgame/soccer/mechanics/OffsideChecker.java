package com.ballgame.soccer.mechanics;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * IFAB offside position check. A player is in an offside position when, at the
 * moment the ball is played by a teammate, they are in the opponents' half and
 * nearer to the opponents' goal line than both the ball and the second-last
 * opponent (any part of head, body or feet).
 */
public final class OffsideChecker {

    private OffsideChecker() {
    }

    /**
     * Goal-to-goal axis derived from arena geometry. Goals sit on the shorter
     * field axis; each team's attacking direction points toward the goal they score in.
     */
    public static final class PitchAxis {

        private final boolean useX;
        private final double min;
        private final double max;
        private final double halfway;
        private final double redDefendsAt;
        private final double blueDefendsAt;

        private PitchAxis(boolean useX, double min, double max,
                          double redDefendsAt, double blueDefendsAt) {
            this.useX = useX;
            this.min = min;
            this.max = max;
            this.halfway = (min + max) / 2.0;
            this.redDefendsAt = redDefendsAt;
            this.blueDefendsAt = blueDefendsAt;
        }

        public static PitchAxis fromArena(Arena arena) {
            Cuboid field = arena.getField();
            if (field == null) {
                return null;
            }
            boolean useX = field.getSizeX() < field.getSizeZ();
            double min = useX ? field.getMinX() : field.getMinZ();
            double max = useX ? field.getMaxX() : field.getMaxZ();

            double redDefendsAt = goalLineCoordinate(arena.getRedGoal(), field, useX);
            double blueDefendsAt = goalLineCoordinate(arena.getBlueGoal(), field, useX);
            if (redDefendsAt == blueDefendsAt) {
                // Fallback: red on low end, blue on high end.
                redDefendsAt = min;
                blueDefendsAt = max;
            }
            return new PitchAxis(useX, min, max, redDefendsAt, blueDefendsAt);
        }

        private static double goalLineCoordinate(Cuboid goal, Cuboid field, boolean useX) {
            if (goal == null || field == null) {
                return Double.NaN;
            }
            double center = useX
                    ? (goal.getMinX() + goal.getMaxX()) / 2.0
                    : (goal.getMinZ() + goal.getMaxZ()) / 2.0;
            double fieldCenter = useX
                    ? (field.getMinX() + field.getMaxX()) / 2.0
                    : (field.getMinZ() + field.getMaxZ()) / 2.0;
            return center;
        }

        /** Higher progress = closer to the goal the attacking team scores in. */
        public double attackProgress(TeamSide attacking, Location loc) {
            if (loc == null) {
                return Double.NaN;
            }
            double coord = useX ? loc.getX() : loc.getZ();
            double defendsAt = attacking == TeamSide.RED ? redDefendsAt : blueDefendsAt;
            double attacksToward = attacking == TeamSide.RED ? blueDefendsAt : redDefendsAt;
            if (attacksToward > defendsAt) {
                return coord - min;
            }
            return max - coord;
        }

        public boolean isInOpponentsHalf(TeamSide attacking, double progress) {
            double halfLength = max - min;
            return progress > halfLength / 2.0;
        }

        public Location clampToField(Location loc) {
            if (loc == null || loc.getWorld() == null) {
                return loc;
            }
            double inset = 0.35;
            double x = loc.getX();
            double z = loc.getZ();
            if (useX) {
                x = Math.max(min + inset, Math.min(max - inset, x));
            } else {
                z = Math.max(min + inset, Math.min(max - inset, z));
            }
            return new Location(loc.getWorld(), x, loc.getY(), z, loc.getYaw(), loc.getPitch());
        }

        /** Unit vector from this team's goal toward the goal they attack. */
        public Vector attackDirection(TeamSide attacking) {
            double defendsAt = attacking == TeamSide.RED ? redDefendsAt : blueDefendsAt;
            double attacksToward = attacking == TeamSide.RED ? blueDefendsAt : redDefendsAt;
            if (useX) {
                double sign = attacksToward >= defendsAt ? 1.0 : -1.0;
                return new Vector(sign, 0, 0);
            }
            double sign = attacksToward >= defendsAt ? 1.0 : -1.0;
            return new Vector(0, 0, sign);
        }
    }

    public static double secondLastOpponentProgress(List<Double> opponentProgresses) {
        if (opponentProgresses == null || opponentProgresses.isEmpty()) {
            // Nobody defending: there is no line to be beyond.
            return Double.POSITIVE_INFINITY;
        }
        List<Double> sorted = new ArrayList<>(opponentProgresses);
        Collections.sort(sorted, Collections.reverseOrder());
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        return sorted.get(1);
    }

    /**
     * Returns true if the player was in an offside position at the moment the
     * ball was played, using progress values captured at that instant.
     */
    public static boolean wasInOffsidePosition(PitchAxis axis, TeamSide attackingTeam,
                                                 double playerProgress, double ballProgress,
                                                 List<Double> opponentProgresses) {
        if (axis == null || Double.isNaN(playerProgress) || Double.isNaN(ballProgress)) {
            return false;
        }
        if (!axis.isInOpponentsHalf(attackingTeam, playerProgress)) {
            return false;
        }
        double line = secondLastOpponentProgress(opponentProgresses);
        return playerProgress > ballProgress && playerProgress > line;
    }

    public static List<Double> collectOpponentProgresses(PitchAxis axis, TeamSide attackingTeam,
                                                         Iterable<Location> opponentLocations) {
        List<Double> progresses = new ArrayList<>();
        for (Location loc : opponentLocations) {
            if (loc == null) {
                continue;
            }
            double p = axis.attackProgress(attackingTeam, loc);
            if (!Double.isNaN(p)) {
                progresses.add(p);
            }
        }
        return progresses;
    }
}
