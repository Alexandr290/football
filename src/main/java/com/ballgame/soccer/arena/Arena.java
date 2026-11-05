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

    /** Ball radius tolerance for line / post checks ({@link com.ballgame.soccer.ball.Ball}). */
    private static final double GOAL_BALL_PADDING = 0.30;
    /** Scoring window: goal-line floor (pos1/pos2) through 3 blocks up (see {@link #scoringCeiling}). */
    private static final double GOAL_SCORING_HEIGHT = 3.0;
    /** Post band — only lateral edges; crossbar / top-corner shots are valid goals. */
    private static final double GOAL_FRAME_BAND = 0.35;

    private final String name;

    private Cuboid field;          // overall play boundary
    private Cuboid redGoal;        // defended by RED, scored in by BLUE
    private Cuboid blueGoal;       // defended by BLUE, scored in by RED

    private Location ballSpawn;    // center kickoff point
    private Location redSpawn;     // RED team spawn
    private Location blueSpawn;    // BLUE team spawn
    private Location lobby;        // pre-game waiting spot
    private Location spectator;    // optional spectator/camera spot
    /** Penalty mark in front of the goal defended by RED (penalties awarded to BLUE). */
    private Location redPenaltySpot;
    /** Penalty mark in front of the goal defended by BLUE (penalties awarded to RED). */
    private Location bluePenaltySpot;

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
     * True when the ball hits a goalpost (lateral edge). Crossbar / upper-corner
     * contact is not treated as woodwork — those can still be goals in the air.
     */
    public boolean isNearGoalFrame(Location loc) {
        if (loc == null) {
            return false;
        }
        return isNearGoalPost(redGoal, loc) || isNearGoalPost(blueGoal, loc);
    }

    private boolean isNearGoalPost(Cuboid goal, Location loc) {
        if (goal == null || loc.getWorld() == null || !loc.getWorld().equals(goal.getWorld())) {
            return false;
        }
        double floor = goalLineFloor(goal);
        double y = loc.getY();
        if (y < floor - GOAL_BALL_PADDING || y > scoringCeiling(goal) + GOAL_BALL_PADDING) {
            return false;
        }
        double mouthMin = mouthMin(goal);
        double mouthMax = mouthMax(goal);
        double lateral = mouthLateralCoord(goal, loc);
        return lateral - mouthMin < GOAL_FRAME_BAND || mouthMax - lateral < GOAL_FRAME_BAND;
    }

    /**
     * Returns which goal the ball is in, or null if neither.
     *
     * <p>The ball counts once any part of it has crossed the field-facing goal line
     * (pos1/pos2) within that width and up to {@value #GOAL_SCORING_HEIGHT}
     * blocks 1–3 above the line (in the air — not only when the ball lands).</p>
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
     * through a thin goal region between ticks. Also tests 3D plane crossings
     * at the goal line plane within the allowed height window.
     */
    public TeamSide goalContainingAlongPath(Location from, Location to) {
        if (from == null || to == null) {
            return null;
        }
        TeamSide crossed = goalCrossedOnPath(from, to);
        if (crossed != null) {
            return crossed;
        }

        double pathLen = from.distance(to);
        int steps = Math.max(16, (int) Math.ceil(pathLen * 8.0));
        Location prev = from;
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Location sample = new Location(from.getWorld(),
                    from.getX() + (to.getX() - from.getX()) * t,
                    from.getY() + (to.getY() - from.getY()) * t,
                    from.getZ() + (to.getZ() - from.getZ()) * t);
            crossed = goalCrossedOnPath(prev, sample);
            if (crossed != null) {
                return crossed;
            }
            TeamSide at = goalContaining(sample);
            if (at != null) {
                return at;
            }
            prev = sample;
        }
        return null;
    }

    /**
     * @deprecated Goals are detected only through {@link #goalContaining} and
     * {@link #goalContainingAlongPath} on the pos1/pos2 segment.
     */
    @Deprecated
    public TeamSide goalAtGoalLineExit(Location loc) {
        return null;
    }

    /** Mouth runs along X when pos1/pos2 span X wider than Z. */
    private static boolean mouthAlongX(Cuboid goal) {
        return goal.getSizeX() >= goal.getSizeZ();
    }

    private static boolean lineAxisIsZ(Cuboid goal) {
        return mouthAlongX(goal);
    }

    private static double goalLineFloor(Cuboid goal) {
        return goal.getMinY();
    }

    /** Top of the 3-block scoring window (pos1/pos2 floor + {@value #GOAL_SCORING_HEIGHT}). */
    private static double scoringCeiling(Cuboid goal) {
        double configured = goal.getMaxY();
        double expected = goalLineFloor(goal) + GOAL_SCORING_HEIGHT;
        return Math.max(configured, expected);
    }

    private static double mouthMin(Cuboid goal) {
        return mouthAlongX(goal) ? goal.getMinX() : goal.getMinZ();
    }

    private static double mouthMax(Cuboid goal) {
        return mouthAlongX(goal) ? goal.getMaxX() : goal.getMaxZ();
    }

    private static double mouthLateralCoord(Cuboid goal, Location loc) {
        return mouthAlongX(goal) ? loc.getX() : loc.getZ();
    }

    /** Goal line floor up through 3 blocks (blocks 1, 2, 3 above the line). */
    private static boolean isWithinScoringHeight(Cuboid goal, double y) {
        double floor = goalLineFloor(goal);
        double ceiling = scoringCeiling(goal);
        return y >= floor - GOAL_BALL_PADDING && y <= ceiling + GOAL_BALL_PADDING;
    }

    /** Exact pos1/pos2 mouth width (ball centre inside the posts). */
    private static boolean isWithinScoringMouth(Cuboid goal, double lateral) {
        double pad = 0.18;
        double min = mouthMin(goal) + pad;
        double max = mouthMax(goal) - pad;
        if (max <= min) {
            return false;
        }
        return lateral >= min && lateral <= max;
    }

    private static boolean isWithinScoringMouth(Cuboid goal, Location loc) {
        return isWithinScoringMouth(goal, mouthLateralCoord(goal, loc));
    }

    private boolean isInsideGoalVolume(Cuboid goal, Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(goal.getWorld())) {
            return false;
        }
        if (!isWithinScoringHeight(goal, loc.getY())) {
            return false;
        }
        if (!isWithinScoringMouth(goal, loc)) {
            return false;
        }
        return isPastGoalLine(goal, loc);
    }

    /** True when any part of the ball has crossed onto the net side of the goal line. */
    private boolean isPastGoalLine(Cuboid goal, Location loc) {
        if (field == null) {
            return false;
        }
        if (lineAxisIsZ(goal)) {
            return isPastGoalLineCoord(goal, loc.getZ(), true);
        }
        return isPastGoalLineCoord(goal, loc.getX(), false);
    }

    private boolean isPastGoalLineCoord(Cuboid goal, double coord, boolean axisZ) {
        double line = axisZ ? goalLineCoordinateZ(goal) : goalLineCoordinateX(goal);
        boolean lowEnd = axisZ ? isGoalOnLowZEnd(goal) : isGoalOnLowXEnd(goal);
        if (lowEnd) {
            return coord - GOAL_BALL_PADDING < line;
        }
        return coord + GOAL_BALL_PADDING > line;
    }

    private boolean isOnFieldSideOfGoalLine(Cuboid goal, double coord, boolean axisZ) {
        double line = axisZ ? goalLineCoordinateZ(goal) : goalLineCoordinateX(goal);
        boolean lowEnd = axisZ ? isGoalOnLowZEnd(goal) : isGoalOnLowXEnd(goal);
        if (lowEnd) {
            return coord + GOAL_BALL_PADDING > line;
        }
        return coord - GOAL_BALL_PADDING < line;
    }

    /**
     * Detects whether the ball path crossed the goal mouth plane from the pitch
     * into the net within the allowed height window.
     */
    private TeamSide goalCrossedOnPath(Location from, Location to) {
        TeamSide red = tryCrossGoalMouth(from, to, redGoal, TeamSide.RED);
        if (red != null) {
            return red;
        }
        return tryCrossGoalMouth(from, to, blueGoal, TeamSide.BLUE);
    }

    private TeamSide tryCrossGoalMouth(Location from, Location to, Cuboid goal, TeamSide side) {
        if (goal == null || field == null || from.getWorld() == null
                || !from.getWorld().equals(goal.getWorld())) {
            return null;
        }

        boolean axisZ = lineAxisIsZ(goal);
        double lineCoord = axisZ ? goalLineCoordinateZ(goal) : goalLineCoordinateX(goal);
        double fromCoord = axisZ ? from.getZ() : from.getX();
        double toCoord = axisZ ? to.getZ() : to.getX();

        if (Math.abs(toCoord - fromCoord) < 1.0e-8) {
            return null;
        }

        boolean fromPast = isPastGoalLineCoord(goal, fromCoord, axisZ);
        boolean toPast = isPastGoalLineCoord(goal, toCoord, axisZ);

        // Ball leaving the net — not a goal.
        if (fromPast && !toPast) {
            return null;
        }
        // Already fully inside; goalContaining handles the current position.
        if (fromPast && toPast) {
            return null;
        }

        boolean fromField = isOnFieldSideOfGoalLine(goal, fromCoord, axisZ);
        boolean fromOnLine = Math.abs(fromCoord - lineCoord) <= GOAL_BALL_PADDING + 0.05;
        if (!fromField && !fromOnLine) {
            return null;
        }

        double t = (lineCoord - fromCoord) / (toCoord - fromCoord);
        if (t < 0.0 || t > 1.0) {
            return null;
        }

        double hitX = from.getX() + (to.getX() - from.getX()) * t;
        double hitY = from.getY() + (to.getY() - from.getY()) * t;
        double hitZ = from.getZ() + (to.getZ() - from.getZ()) * t;
        if (!isWithinScoringHeight(goal, hitY)) {
            return null;
        }

        Location hit = new Location(from.getWorld(), hitX, hitY, hitZ);
        if (!isWithinScoringMouth(goal, hit)) {
            return null;
        }
        return side;
    }

    /** Field-facing Z of the goal mouth (touchlines run along X). */
    private double goalLineCoordinateZ(Cuboid goal) {
        return isGoalOnLowZEnd(goal) ? goal.getMaxZ() : goal.getMinZ();
    }

    /** Field-facing X of the goal mouth (touchlines run along Z). */
    private double goalLineCoordinateX(Cuboid goal) {
        return isGoalOnLowXEnd(goal) ? goal.getMaxX() : goal.getMinX();
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

    /**
     * Marked penalty spot in front of the goal defended by {@code defendingGoal}.
     * Optional — when unset, the game computes a spot from goal geometry.
     */
    public Location getPenaltySpotAgainst(TeamSide defendingGoal) {
        if (defendingGoal == TeamSide.RED) {
            return redPenaltySpot;
        }
        if (defendingGoal == TeamSide.BLUE) {
            return bluePenaltySpot;
        }
        return null;
    }

    /** Penalty mark for the attacking team (they shoot at the opposite goal). */
    public Location getPenaltySpotForAttack(TeamSide attacking) {
        if (attacking == null) {
            return null;
        }
        return getPenaltySpotAgainst(attacking.opposite());
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

    public Location getRedPenaltySpot() { return redPenaltySpot; }
    public void setRedPenaltySpot(Location redPenaltySpot) { this.redPenaltySpot = redPenaltySpot; }

    public Location getBluePenaltySpot() { return bluePenaltySpot; }
    public void setBluePenaltySpot(Location bluePenaltySpot) { this.bluePenaltySpot = bluePenaltySpot; }

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
        Locations.save(section, "red-penalty-spot", redPenaltySpot);
        Locations.save(section, "blue-penalty-spot", bluePenaltySpot);
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
        arena.redPenaltySpot = Locations.load(section, "red-penalty-spot");
        arena.bluePenaltySpot = Locations.load(section, "blue-penalty-spot");
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
