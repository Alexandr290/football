package com.ballgame.soccer.arena;

import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import com.ballgame.soccer.util.Locations;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fully-described soccer pitch. An arena is "setup-complete" once it has a
 * field region, a ball/center spawn, both team spawns and both goals defined.
 *
 * <p>Goals are named after the team that <em>defends</em> them. The opposing
 * team scores by putting the ball into it: i.e. BLUE scores in the RED goal.</p>
 */
public final class Arena {

    /** FIFA goal mouth height (blocks). Used when admins mark only the goal line on the ground. */
    private static final double STANDARD_GOAL_HEIGHT = 2.44;
    /** Ball center tolerance below/above the goal frame ({@link com.ballgame.soccer.ball.Ball} radius). */
    private static final double GOAL_BALL_PADDING = 0.35;
    /** Flat goal regions (pos1/pos2 at ground) use this minimum vertical span before trusting maxY. */
    private static final double GOAL_MIN_MARKED_HEIGHT = 1.0;

    private final String name;

    private Cuboid field;          // overall play boundary
    private Cuboid redGoal;        // defended by RED, scored in by BLUE
    private Cuboid blueGoal;       // defended by BLUE, scored in by RED

    private Location ballSpawn;    // center kickoff point
    private Location redSpawn;     // RED team spawn
    private Location blueSpawn;    // BLUE team spawn
    private Location lobby;        // pre-game waiting spot
    private Location spectator;    // optional spectator/camera spot

    /** Custom touchlines marked with pos1/pos2 ({@code /soccer addoutline}). */
    private final List<TouchlineSegment> outLines = new ArrayList<>();

    private boolean enabled = true;

    public Arena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isSetupComplete() {
        return field != null
                && redGoal != null
                && blueGoal != null
                && ballSpawn != null
                && redSpawn != null
                && blueSpawn != null;
    }

    public boolean isPlayable() {
        return enabled && isSetupComplete();
    }

    /**
     * Returns which goal the ball is in, or null if neither.
     *
     * <p>The ball centre must be inside the goal width/depth and below the
     * crossbar (with a small padding for the ball radius). Shots over the bar
     * do not count.</p>
     */
    public TeamSide goalContaining(Location loc) {
        if (loc == null) {
            return null;
        }
        if (redGoal != null && isInsideGoalVolume(redGoal, loc)) {
            return TeamSide.RED;
        }
        if (blueGoal != null && isInsideGoalVolume(blueGoal, loc)) {
            return TeamSide.BLUE;
        }
        return null;
    }

    /**
     * Checks several points along the ball's path so fast shots cannot skip
     * through a thin goal region between ticks.
     */
    public TeamSide goalContainingAlongPath(Location from, Location to) {
        if (from == null || to == null) {
            return null;
        }
        TeamSide atEnd = goalContaining(to);
        if (atEnd != null) {
            return atEnd;
        }
        TeamSide atStart = goalContaining(from);
        if (atStart != null) {
            return atStart;
        }
        int samples = 10;
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            Location sample = new Location(from.getWorld(),
                    from.getX() + (to.getX() - from.getX()) * t,
                    from.getY() + (to.getY() - from.getY()) * t,
                    from.getZ() + (to.getZ() - from.getZ()) * t);
            TeamSide goal = goalContaining(sample);
            if (goal != null) {
                return goal;
            }
        }
        return null;
    }

    /**
     * When the ball crosses the goal line outside the field footprint, award a
     * goal if it is within the mouth (width) of a goal on that end.
     */
    public TeamSide goalAtGoalLineExit(Location loc) {
        if (field == null || loc == null || loc.getWorld() == null) {
            return null;
        }
        double x = loc.getX();
        double z = loc.getZ();
        boolean insideX = x >= field.getMinX() && x <= field.getMaxX();
        boolean insideZ = z >= field.getMinZ() && z <= field.getMaxZ();
        if (insideX && insideZ) {
            return null;
        }

        boolean touchlinesOnX = field.getSizeX() >= field.getSizeZ();
        if (touchlinesOnX) {
            if (!insideZ && insideX) {
                return goalOnEndForAxisZ(x, loc.getY(), z < field.getMinZ());
            }
        } else {
            if (!insideX && insideZ) {
                return goalOnEndForAxisX(z, loc.getY(), x < field.getMinX());
            }
        }
        return null;
    }

    private TeamSide goalOnEndForAxisZ(double x, double y, boolean lowEnd) {
        TeamSide red = tryGoalMouthX(redGoal, TeamSide.RED, x, y);
        TeamSide blue = tryGoalMouthX(blueGoal, TeamSide.BLUE, x, y);
        if (red != null && blue == null) {
            return red;
        }
        if (blue != null && red == null) {
            return blue;
        }
        if (red != null) {
            if (redGoal != null && isGoalOnLowZEnd(redGoal) == lowEnd) {
                return TeamSide.RED;
            }
            if (blueGoal != null && isGoalOnLowZEnd(blueGoal) == lowEnd) {
                return TeamSide.BLUE;
            }
            return red;
        }
        return null;
    }

    private TeamSide goalOnEndForAxisX(double z, double y, boolean lowEnd) {
        TeamSide red = tryGoalMouthZ(redGoal, TeamSide.RED, z, y);
        TeamSide blue = tryGoalMouthZ(blueGoal, TeamSide.BLUE, z, y);
        if (red != null && blue == null) {
            return red;
        }
        if (blue != null && red == null) {
            return blue;
        }
        if (red != null) {
            if (redGoal != null && isGoalOnLowXEnd(redGoal) == lowEnd) {
                return TeamSide.RED;
            }
            if (blueGoal != null && isGoalOnLowXEnd(blueGoal) == lowEnd) {
                return TeamSide.BLUE;
            }
            return red;
        }
        return null;
    }

    private TeamSide tryGoalMouthX(Cuboid goal, TeamSide side, double x, double y) {
        if (goal == null || !isWithinGoalHeight(goal, y)) {
            return null;
        }
        double pad = 0.35;
        if (x >= goal.getMinX() - pad && x <= goal.getMaxX() + pad) {
            return side;
        }
        return null;
    }

    private TeamSide tryGoalMouthZ(Cuboid goal, TeamSide side, double z, double y) {
        if (goal == null || !isWithinGoalHeight(goal, y)) {
            return null;
        }
        double pad = 0.35;
        if (z >= goal.getMinZ() - pad && z <= goal.getMaxZ() + pad) {
            return side;
        }
        return null;
    }

    private boolean isInsideGoalVolume(Cuboid goal, Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(goal.getWorld())) {
            return false;
        }
        double pad = 0.35;
        if (loc.getX() < goal.getMinX() - pad || loc.getX() > goal.getMaxX() + pad) {
            return false;
        }
        if (loc.getZ() < goal.getMinZ() - pad || loc.getZ() > goal.getMaxZ() + pad) {
            return false;
        }
        return isWithinGoalHeight(goal, loc.getY());
    }

    private boolean isWithinGoalHeight(Cuboid goal, double y) {
        double floorY = goal.getMinY() - GOAL_BALL_PADDING;
        double ceilingY;
        if (goal.getSizeY() < GOAL_MIN_MARKED_HEIGHT) {
            ceilingY = goal.getMinY() + STANDARD_GOAL_HEIGHT + GOAL_BALL_PADDING;
        } else {
            ceilingY = goal.getMaxY() + GOAL_BALL_PADDING;
        }
        return y >= floorY && y <= ceilingY;
    }

    private boolean isGoalOnLowZEnd(Cuboid goal) {
        if (field == null) {
            return goal.getMinZ() <= goal.getMaxZ();
        }
        double goalCenterZ = (goal.getMinZ() + goal.getMaxZ()) / 2.0;
        double fieldCenterZ = (field.getMinZ() + field.getMaxZ()) / 2.0;
        return goalCenterZ < fieldCenterZ;
    }

    private boolean isGoalOnLowXEnd(Cuboid goal) {
        if (field == null) {
            return goal.getMinX() <= goal.getMaxX();
        }
        double goalCenterX = (goal.getMinX() + goal.getMaxX()) / 2.0;
        double fieldCenterX = (field.getMinX() + field.getMaxX()) / 2.0;
        return goalCenterX < fieldCenterX;
    }

    public boolean isInsideField(Location loc) {
        return field != null && field.contains(loc);
    }

    /**
     * Whether the ball should be considered out of play and reset to center.
     *
     * <p>Only the horizontal footprint of the field matters: a ball kicked high
     * into the air is still "in play". The vertical check only triggers if the
     * ball drops well below the pitch (e.g. fell into the void), so a field
     * region selected at ground level doesn't constantly reset the ball.</p>
     */
    public boolean isBallOutOfPlay(Location loc) {
        if (field == null || loc == null) {
            return false;
        }
        if (loc.getWorld() == null || !loc.getWorld().equals(field.getWorld())) {
            return true;
        }
        if (!field.containsHorizontally(loc.getX(), loc.getZ())) {
            return true;
        }
        return loc.getY() < field.getMinY() - 5.0;
    }

    /**
     * Classifies how the ball left the pitch. Touchlines are the two longer sides
     * of the field footprint; goal lines are the shorter ends.
     */
    public BoundaryExit classifyBoundaryExit(Location loc) {
        if (field == null || loc == null) {
            return BoundaryExit.IN_PLAY;
        }
        if (loc.getWorld() == null || !loc.getWorld().equals(field.getWorld())) {
            return BoundaryExit.GOAL_LINE;
        }
        if (loc.getY() < field.getMinY() - 5.0) {
            return BoundaryExit.BELOW_PITCH;
        }

        double x = loc.getX();
        double z = loc.getZ();
        boolean insideX = x >= field.getMinX() && x <= field.getMaxX();
        boolean insideZ = z >= field.getMinZ() && z <= field.getMaxZ();
        if (insideX && insideZ) {
            return BoundaryExit.IN_PLAY;
        }

        // Longer axis = touchlines; shorter axis = goal lines.
        boolean touchlinesOnX = field.getSizeX() >= field.getSizeZ();
        if (touchlinesOnX) {
            if (!insideX && insideZ) {
                return BoundaryExit.TOUCHLINE;
            }
        } else {
            if (!insideZ && insideX) {
                return BoundaryExit.TOUCHLINE;
            }
        }
        return BoundaryExit.GOAL_LINE;
    }

    /**
     * Position for a throw-in: on the touchline where the ball exited, nudged
     * slightly back onto the pitch.
     */
    public Location computeThrowInSpot(Location exitLoc) {
        if (field == null || exitLoc == null || field.getWorld() == null) {
            return exitLoc;
        }
        double inset = 0.35;
        double minX = field.getMinX();
        double maxX = field.getMaxX();
        double minZ = field.getMinZ();
        double maxZ = field.getMaxZ();

        double x = exitLoc.getX();
        double z = exitLoc.getZ();
        boolean touchlinesOnX = field.getSizeX() >= field.getSizeZ();

        if (touchlinesOnX) {
            z = Math.max(minZ + inset, Math.min(maxZ - inset, z));
            if (x < minX) {
                x = minX + inset;
            } else if (x > maxX) {
                x = maxX - inset;
            } else {
                x = (x - minX < maxX - x) ? minX + inset : maxX - inset;
            }
        } else {
            x = Math.max(minX + inset, Math.min(maxX - inset, x));
            if (z < minZ) {
                z = minZ + inset;
            } else if (z > maxZ) {
                z = maxZ - inset;
            } else {
                z = (z - minZ < maxZ - z) ? minZ + inset : maxZ - inset;
            }
        }

        double y = Math.max(field.getMinY() + 0.6, exitLoc.getY());
        return new Location(field.getWorld(), x, y, z);
    }

    /**
     * Resolves the throw-in spot plus the inward (pitch) direction for facing and
     * touchline confinement.
     */
    public ThrowInGeometry computeThrowInGeometry(Location exitLoc) {
        Location spot = computeThrowInSpot(exitLoc);
        if (field == null || spot == null) {
            return new ThrowInGeometry(spot, new Vector(0, 0, 1), false, true);
        }

        double inset = 0.35;
        double x = spot.getX();
        double z = spot.getZ();
        boolean touchlineOnX = Math.abs(x - field.getMinX()) < inset + 0.05
                || Math.abs(x - field.getMaxX()) < inset + 0.05;

        if (touchlineOnX) {
            if (Math.abs(x - field.getMinX()) < Math.abs(x - field.getMaxX())) {
                return new ThrowInGeometry(spot, new Vector(1, 0, 0), true, true);
            }
            return new ThrowInGeometry(spot, new Vector(-1, 0, 0), true, false);
        }
        if (Math.abs(z - field.getMinZ()) < Math.abs(z - field.getMaxZ())) {
            return new ThrowInGeometry(spot, new Vector(0, 0, 1), false, true);
        }
        return new ThrowInGeometry(spot, new Vector(0, 0, -1), false, false);
    }

    public Location getTeamSpawn(TeamSide side) {
        return side == TeamSide.RED ? redSpawn : blueSpawn;
    }

    public Cuboid getGoal(TeamSide side) {
        return side == TeamSide.RED ? redGoal : blueGoal;
    }

    public List<TouchlineSegment> getOutLines() {
        return Collections.unmodifiableList(outLines);
    }

    public boolean hasOutLines() {
        return !outLines.isEmpty();
    }

    public boolean addOutLine(Location pos1, Location pos2) {
        if (pos1 == null || pos2 == null || pos1.getWorld() == null || pos2.getWorld() == null) {
            return false;
        }
        if (!pos1.getWorld().equals(pos2.getWorld())) {
            return false;
        }
        if (pos1.distanceSquared(pos2) < 0.25) {
            return false;
        }
        outLines.add(TouchlineSegment.fromMarkedPoints(pos1, pos2, field));
        return true;
    }

    public void clearOutLines() {
        outLines.clear();
    }

    /**
     * Detects whether the ball path crossed a marked touchline (any height).
     * Returns the earliest crossing along the path.
     */
    public OutLineCrossing detectOutLineCrossing(Location prev, Location curr) {
        if (outLines.isEmpty() || curr == null || prev == null) {
            return null;
        }
        if (prev.getWorld() == null || curr.getWorld() == null || !prev.getWorld().equals(curr.getWorld())) {
            return null;
        }

        OutLineCrossing best = null;
        double bestT = Double.MAX_VALUE;

        for (TouchlineSegment segment : outLines) {
            Double t = TouchlineMath.pathCrossesSegment(prev, curr, segment);
            if (t == null || t >= bestT) {
                continue;
            }
            bestT = t;
            Location exit = TouchlineMath.interpolate(prev, curr, t);
            best = buildOutLineCrossing(exit, segment);
        }
        return best;
    }

    private OutLineCrossing buildOutLineCrossing(Location exit, TouchlineSegment segment) {
        double param = TouchlineMath.projectParam(exit, segment.from(), segment.to());
        Location onLine = segment.pointAt(param);
        Vector outward = segment.outward();

        double groundY = field != null ? field.getMinY() + 0.35 : exit.getY();
        Location spot = onLine.clone().add(outward.clone().multiply(0.55));
        spot.setY(groundY);

        Vector inward = segment.inward().clone();
        if (inward.lengthSquared() > 1e-6) {
            inward.normalize();
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-inward.getX(), inward.getZ()));
        spot.setYaw(yaw);
        spot.setPitch(0f);

        ThrowInGeometry geometry = new ThrowInGeometry(
                spot.clone(),
                inward,
                false,
                false,
                segment.from(),
                segment.to());
        return new OutLineCrossing(exit.clone(), spot, geometry);
    }

    // --- getters / setters ---

    public Cuboid getField() { return field; }
    public void setField(Cuboid field) { this.field = field; }

    public Cuboid getRedGoal() { return redGoal; }
    public void setRedGoal(Cuboid redGoal) { this.redGoal = redGoal; }

    public Cuboid getBlueGoal() { return blueGoal; }
    public void setBlueGoal(Cuboid blueGoal) { this.blueGoal = blueGoal; }

    public Location getBallSpawn() { return ballSpawn; }
    public void setBallSpawn(Location ballSpawn) { this.ballSpawn = ballSpawn; }

    public Location getRedSpawn() { return redSpawn; }
    public void setRedSpawn(Location redSpawn) { this.redSpawn = redSpawn; }

    public Location getBlueSpawn() { return blueSpawn; }
    public void setBlueSpawn(Location blueSpawn) { this.blueSpawn = blueSpawn; }

    public Location getLobby() { return lobby; }
    public void setLobby(Location lobby) { this.lobby = lobby; }

    public Location getSpectator() { return spectator; }
    public void setSpectator(Location spectator) { this.spectator = spectator; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // --- persistence ---

    public void save(ConfigurationSection section) {
        section.set("enabled", enabled);
        if (field != null) {
            field.save(section.createSection("field"));
        }
        if (redGoal != null) {
            redGoal.save(section.createSection("red-goal"));
        }
        if (blueGoal != null) {
            blueGoal.save(section.createSection("blue-goal"));
        }
        Locations.save(section, "ball-spawn", ballSpawn);
        Locations.save(section, "red-spawn", redSpawn);
        Locations.save(section, "blue-spawn", blueSpawn);
        Locations.save(section, "lobby", lobby);
        Locations.save(section, "spectator", spectator);
        if (!outLines.isEmpty()) {
            ConfigurationSection lines = section.createSection("out-lines");
            for (int i = 0; i < outLines.size(); i++) {
                outLines.get(i).save(lines.createSection(String.valueOf(i)));
            }
        }
    }

    public static Arena load(String name, ConfigurationSection section) {
        Arena arena = new Arena(name);
        arena.enabled = section.getBoolean("enabled", true);
        if (section.isConfigurationSection("field")) {
            arena.field = Cuboid.load(section.getConfigurationSection("field"), null);
        }
        if (section.isConfigurationSection("red-goal")) {
            arena.redGoal = Cuboid.load(section.getConfigurationSection("red-goal"), null);
        }
        if (section.isConfigurationSection("blue-goal")) {
            arena.blueGoal = Cuboid.load(section.getConfigurationSection("blue-goal"), null);
        }
        arena.ballSpawn = Locations.load(section, "ball-spawn");
        arena.redSpawn = Locations.load(section, "red-spawn");
        arena.blueSpawn = Locations.load(section, "blue-spawn");
        arena.lobby = Locations.load(section, "lobby");
        arena.spectator = Locations.load(section, "spectator");
        if (section.isConfigurationSection("out-lines")) {
            ConfigurationSection lines = section.getConfigurationSection("out-lines");
            for (String key : lines.getKeys(false)) {
                ConfigurationSection lineSection = lines.getConfigurationSection(key);
                if (lineSection == null) {
                    continue;
                }
                TouchlineSegment segment = TouchlineSegment.load(lineSection);
                if (segment != null) {
                    arena.outLines.add(segment);
                }
            }
        }
        return arena;
    }
}
