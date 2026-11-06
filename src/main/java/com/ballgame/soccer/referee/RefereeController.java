package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.GameState;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.mechanics.OffsideChecker;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;

/**
 * Controls a three-official crew (center referee + two assistants) that runs
 * the pitch, tracks play diagonally, sprints to restarts, and signals decisions.
 */
public final class RefereeController {

    private final SoccerPlugin plugin;
    private final Game game;
    private final Arena arena;
    private final SoccerSettings settings;

    private RefereePitch pitch;
    private RefereeActor center;
    private RefereeActor assistantLeft;
    private RefereeActor assistantRight;

    private Location centerPos;
    private Location leftPos;
    private Location rightPos;

    private Location focusSpot;
    private int focusTicks;
    private int whistleCooldown;
    private double lastBallAxisT = 0.5;
    private int preferredSide = 1;

    public RefereeController(SoccerPlugin plugin, Game game, Arena arena, SoccerSettings settings) {
        this.plugin = plugin;
        this.game = game;
        this.arena = arena;
        this.settings = settings;
    }

    public void spawn() {
        if (!settings.refereeEnabled) {
            return;
        }
        remove();
        pitch = RefereePitch.fromArena(arena);
        if (pitch == null) {
            return;
        }

        center = new RefereeActor(plugin, settings, RefereeActor.Role.CENTER,
                settings.refereeName, false, this::participants);
        assistantLeft = new RefereeActor(plugin, settings, RefereeActor.Role.ASSISTANT_LEFT,
                settings.refereeAssistantNames.size() > 0
                        ? settings.refereeAssistantNames.get(0) : "AR 1", true, this::participants);
        assistantRight = new RefereeActor(plugin, settings, RefereeActor.Role.ASSISTANT_RIGHT,
                settings.refereeAssistantNames.size() > 1
                        ? settings.refereeAssistantNames.get(1) : "AR 2", true, this::participants);

        Location start = pitch.midfield();
        centerPos = start.clone();
        leftPos = pitch.touchlineLeft(0.5);
        rightPos = pitch.touchlineRight(0.5);

        center.spawn(centerPos);
        assistantLeft.spawn(leftPos);
        assistantRight.spawn(rightPos);
    }

    public void remove() {
        if (center != null) {
            center.remove();
        }
        if (assistantLeft != null) {
            assistantLeft.remove();
        }
        if (assistantRight != null) {
            assistantRight.remove();
        }
        center = null;
        assistantLeft = null;
        assistantRight = null;
        focusSpot = null;
        focusTicks = 0;
    }

    public void tick(GameState state, boolean celebrating, Ball ball,
                     Collection<SoccerPlayer> players, OffsideChecker.PitchAxis pitchAxis) {
        if (!settings.refereeEnabled || pitch == null || center == null) {
            return;
        }
        if (state == GameState.INTRO) {
            // The pre-match show stages the officials itself.
            return;
        }

        if (whistleCooldown > 0) {
            whistleCooldown--;
        }
        if (focusTicks > 0) {
            focusTicks--;
        }

        boolean visible = isVisibleState(state, celebrating);
        center.setHidden(!visible);
        assistantLeft.setHidden(!visible);
        assistantRight.setHidden(!visible);
        if (!visible) {
            return;
        }

        Location ballLoc = ball != null ? ball.getLocation() : pitch.midfield();

        switch (state) {
            case KICKOFF -> updateKickoff(ballLoc);
            case THROW_IN -> updateThrowIn(ballLoc);
            case OFFSIDE -> updateSetPiece(ballLoc, true);
            case HALFTIME -> updateHalftime();
            case PLAYING, EXTRA_TIME -> updateLivePlay(ball, ballLoc, players, pitchAxis, celebrating);
            default -> updateLivePlay(ball, ballLoc, players, pitchAxis, celebrating);
        }
    }

    public void onOffside(Location restart) {
        if (!settings.refereeEnabled || pitch == null) {
            return;
        }
        focusSpot = pitch.clamp(restart);
        focusTicks = 50;
        blowWhistle(focusSpot, true);
        center.playWhistle(45);
        pickNearestAssistant(focusSpot).playFlag(55);
    }

    public void onThrowIn(Location spot) {
        if (!settings.refereeEnabled || pitch == null || spot == null) {
            return;
        }
        focusSpot = pitch.clamp(spot);
        focusTicks = 35;
        blowWhistle(focusSpot, false);
        pickNearestAssistant(focusSpot).playFlag(35);
    }

    public void onGoal() {
        if (!settings.refereeEnabled || center == null) {
            return;
        }
        focusTicks = 60;
        blowWhistle(centerPos, true);
        center.playPoint(50);
    }

    public void onKickoff() {
        if (!settings.refereeEnabled || pitch == null) {
            return;
        }
        focusSpot = pitch.midfield();
        focusTicks = settings.kickoffCountdown * 20 + 10;
        blowWhistle(focusSpot, true);
    }

    private void updateLivePlay(Ball ball, Location ballLoc, Collection<SoccerPlayer> players,
                                OffsideChecker.PitchAxis pitchAxis, boolean celebrating) {
        if (celebrating || ballLoc == null) {
            updateIdleAt(centerPos, ballLoc);
            return;
        }

        if (focusTicks > 0 && focusSpot != null) {
            moveOfficial(center, centerPos, focusSpot, ballLoc, true);
            centerPos = center.getLocation();
            updateAssistants(ballLoc, players, pitchAxis, focusSpot);
            return;
        }

        double ballT = pitch.axisT(ballLoc);
        int playSign = ballT >= lastBallAxisT ? 1 : -1;
        if (ball != null && ball.getSpeed() > 0.15) {
            Location prev = ball.getPreviousTickLocation();
            if (prev != null) {
                double prevT = pitch.axisT(prev);
                if (Math.abs(ballT - prevT) > 0.0005) {
                    playSign = ballT >= prevT ? 1 : -1;
                }
            }
        }
        lastBallAxisT = ballT;
        preferredSide = playSign >= 0 ? 1 : -1;

        double trail = settings.refereeTrailOffset;
        double targetT = Math.max(0.06, Math.min(0.94, ballT - playSign * trail));

        double perp = preferredSide * settings.refereeFollowDistance;
        Location target = pitch.atAxis(targetT, perp);

        moveOfficial(center, centerPos, target, ballLoc, true);
        centerPos = center.getLocation() != null ? center.getLocation() : target;

        updateAssistants(ballLoc, players, pitchAxis, null);
    }

    private void updateAssistants(Location ballLoc, Collection<SoccerPlayer> players,
                                  OffsideChecker.PitchAxis pitchAxis, Location overrideT) {
        double lineT = overrideT != null
                ? pitch.axisT(overrideT)
                : computeLinesmanDepth(ballLoc, players, pitchAxis);

        Location leftTarget = pitch.touchlineLeft(lineT);
        Location rightTarget = pitch.touchlineRight(lineT);

        moveOfficial(assistantLeft, leftPos, leftTarget, ballLoc, true);
        moveOfficial(assistantRight, rightPos, rightTarget, ballLoc, true);
        leftPos = assistantLeft.getLocation() != null ? assistantLeft.getLocation() : leftTarget;
        rightPos = assistantRight.getLocation() != null ? assistantRight.getLocation() : rightTarget;
    }

    /**
     * Linesmen stay level with the second-last defender (IFAB offside line depth).
     */
    private double computeLinesmanDepth(Location ballLoc, Collection<SoccerPlayer> players,
                                        OffsideChecker.PitchAxis pitchAxis) {
        if (pitchAxis == null || ballLoc == null) {
            return pitch.axisT(ballLoc);
        }

        TeamSide lastTouch = game.getBallLastTouchTeam();
        TeamSide attacking = lastTouch;
        if (attacking == null) {
            return pitch.axisT(ballLoc);
        }
        TeamSide defending = attacking.opposite();

        List<Double> defenderProgress = new ArrayList<>();

        for (SoccerPlayer sp : players) {
            Player p = sp.getBukkitPlayer();
            if (p == null || !p.isOnline() || sp.getTeam() != defending || sp.isReferee()) {
                continue;
            }
            double progress = pitchAxis.attackProgress(attacking, p.getLocation());
            defenderProgress.add(progress);
        }
        for (com.ballgame.soccer.bot.MatchBot bot : game.getBots()) {
            if (bot.getTeam() != defending || bot.getLocation() == null) {
                continue;
            }
            double progress = pitchAxis.attackProgress(attacking, bot.getLocation());
            if (!Double.isNaN(progress)) {
                defenderProgress.add(progress);
            }
        }

        if (defenderProgress.isEmpty()) {
            return pitch.axisT(ballLoc);
        }

        defenderProgress.sort((a, b) -> Double.compare(b, a));
        double lineProgress = defenderProgress.size() >= 2
                ? defenderProgress.get(1)
                : defenderProgress.get(0);
        // The assistant tracks the ball instead when it is nearer the goal line.
        double ballProgress = pitchAxis.attackProgress(attacking, ballLoc);
        if (!Double.isNaN(ballProgress)) {
            lineProgress = Math.max(lineProgress, ballProgress);
        }

        return progressToAxisT(pitchAxis, attacking, lineProgress);
    }

    private double progressToAxisT(OffsideChecker.PitchAxis axis, TeamSide attacking, double progress) {
        Location probeA = pitch.atAxis(0.0, 0);
        Location probeB = pitch.atAxis(1.0, 0);
        double progressA = axis.attackProgress(attacking, probeA);
        double progressB = axis.attackProgress(attacking, probeB);
        if (Math.abs(progressB - progressA) < 1e-6) {
            return pitch.axisT(probeA);
        }
        double t = (progress - progressA) / (progressB - progressA);
        return Math.max(0.05, Math.min(0.95, t));
    }

    private void updateKickoff(Location ballLoc) {
        Location target = pitch.atAxis(0.5, preferredSide * 2.5);
        moveOfficial(center, centerPos, target, ballLoc, false);
        centerPos = center.getLocation() != null ? center.getLocation() : target;
        updateAssistants(ballLoc, game.getParticipants(), game.getPitchAxis(), null);
    }

    private void updateThrowIn(Location ballLoc) {
        Location spot = focusSpot != null ? focusSpot : ballLoc;
        moveOfficial(center, centerPos, spot, ballLoc, true);
        centerPos = center.getLocation() != null ? center.getLocation() : spot;
        updateAssistants(ballLoc, game.getParticipants(), game.getPitchAxis(), spot);
    }

    private void updateSetPiece(Location ballLoc, boolean offside) {
        Location spot = focusSpot != null ? focusSpot : ballLoc;
        moveOfficial(center, centerPos, spot, ballLoc, true);
        centerPos = center.getLocation() != null ? center.getLocation() : spot;
        updateAssistants(ballLoc, game.getParticipants(), game.getPitchAxis(), spot);
        if (offside && focusTicks <= 0) {
            center.playFlag(20);
        }
    }

    private void updateHalftime() {
        Location target = pitch.atAxis(0.5, 0);
        updateIdleAt(target, target);
    }

    private void updateIdleAt(Location target, Location lookAt) {
        moveOfficial(center, centerPos, target, lookAt, false);
        centerPos = center.getLocation() != null ? center.getLocation() : target;
        moveOfficial(assistantLeft, leftPos, pitch.touchlineLeft(0.5), lookAt, false);
        moveOfficial(assistantRight, rightPos, pitch.touchlineRight(0.5), lookAt, false);
        leftPos = assistantLeft.getLocation();
        rightPos = assistantRight.getLocation();
    }

    private void moveOfficial(RefereeActor actor, Location current, Location target,
                              Location lookAt, boolean allowSprint) {
        if (actor == null || target == null) {
            return;
        }
        Location from = current != null ? current : target;
        double dist = horizontalDistance(from, target);
        double speed = settings.refereeWalkSpeed;
        boolean sprint = allowSprint && dist > settings.refereeSprintThreshold;
        if (sprint) {
            speed = settings.refereeSprintSpeed;
        }
        double alpha = dist < 0.05 ? 1.0 : Math.min(1.0, speed / Math.max(0.05, dist));
        Location next = pitch.lerp(from, target, alpha);
        float yaw = pitch.yawToward(next, lookAt != null ? lookAt : target);
        actor.moveTo(next, yaw, dist > 0.08, sprint, game.getMatchTick());
    }

    private RefereeActor pickNearestAssistant(Location spot) {
        if (spot == null) {
            return assistantLeft;
        }
        double leftDist = leftPos != null ? horizontalDistance(leftPos, spot) : Double.MAX_VALUE;
        double rightDist = rightPos != null ? horizontalDistance(rightPos, spot) : Double.MAX_VALUE;
        return leftDist <= rightDist ? assistantLeft : assistantRight;
    }

    private void blowWhistle(Location at, boolean loud) {
        if (!settings.refereeWhistleEnabled || whistleCooldown > 0 || at == null) {
            return;
        }
        whistleCooldown = 15;
        float volume = loud ? 1.2f : 0.95f;
        game.forEachParticipant(player -> {
            player.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, volume, 1.85f);
            player.playSound(at, Sound.ENTITY_VILLAGER_NO, volume * 0.65f, 1.65f);
        });
        center.playWhistle(18);
    }

    private static double horizontalDistance(Location a, Location b) {
        if (a == null || b == null) {
            return 0;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean isVisibleState(GameState state, boolean celebrating) {
        if (celebrating) {
            return false;
        }
        return switch (state) {
            case INTRO, PLAYING, EXTRA_TIME, KICKOFF, THROW_IN, OFFSIDE, HALFTIME -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Cutscene staging (pre-match show)
    // ------------------------------------------------------------------

    /** Whether the crew is spawned and can be choreographed. */
    public boolean isStageable() {
        return pitch != null && center != null;
    }

    public String officialName(RefereeActor.Role role) {
        return switch (role) {
            case CENTER -> settings.refereeName;
            case ASSISTANT_LEFT -> settings.refereeAssistantNames.size() > 0
                    ? settings.refereeAssistantNames.get(0) : "AR 1";
            case ASSISTANT_RIGHT -> settings.refereeAssistantNames.size() > 1
                    ? settings.refereeAssistantNames.get(1) : "AR 2";
        };
    }

    public Location stagedLocation(RefereeActor.Role role) {
        Location remembered = rememberedPos(role);
        if (remembered != null) {
            return remembered.clone();
        }
        RefereeActor actor = actor(role);
        return actor != null && actor.getLocation() != null ? actor.getLocation().clone() : null;
    }

    /** Puts an official on a spot instantly, facing {@code lookAt}. */
    public void stagePlace(RefereeActor.Role role, Location at, Location lookAt) {
        RefereeActor actor = actor(role);
        if (actor == null || at == null || pitch == null) {
            return;
        }
        actor.setHidden(false);
        Location spot = at.clone();
        float yaw = lookAt != null ? pitch.yawToward(spot, lookAt) : at.getYaw();
        actor.moveTo(spot, yaw, false, false, game.getMatchTick());
        remember(role, spot);
    }

    /**
     * Walks an official toward {@code target} at a fixed pace (blocks per tick).
     * Faces the direction of travel while moving and {@code lookAt} once there.
     */
    public void stageStep(RefereeActor.Role role, Location target, double pace, Location lookAt) {
        RefereeActor actor = actor(role);
        if (actor == null || target == null || pitch == null) {
            return;
        }
        actor.setHidden(false);
        Location from = stagedLocation(role);
        if (from == null) {
            from = target.clone();
        }
        double dist = horizontalDistance(from, target);
        boolean moving = pace > 0 && dist > 0.02;
        Location next;
        if (!moving || dist <= pace) {
            next = target.clone();
        } else {
            double alpha = pace / dist;
            next = new Location(target.getWorld(),
                    from.getX() + (target.getX() - from.getX()) * alpha,
                    target.getY(),
                    from.getZ() + (target.getZ() - from.getZ()) * alpha);
        }
        float yaw;
        if (moving) {
            yaw = pitch.yawToward(from, target);
        } else if (lookAt != null) {
            yaw = pitch.yawToward(next, lookAt);
        } else {
            yaw = from.getYaw();
        }
        next.setYaw(yaw);
        actor.moveTo(next, yaw, moving, moving && pace > settings.refereeWalkSpeed * 1.35,
                game.getMatchTick());
        remember(role, next);
    }

    /** Where the crew stands for a kickoff: centre ref near the spot, assistants on the halfway line. */
    public Location kickoffTarget(RefereeActor.Role role) {
        if (pitch == null) {
            return null;
        }
        return switch (role) {
            case CENTER -> pitch.atAxis(0.5, preferredSide * 2.5);
            case ASSISTANT_LEFT -> pitch.touchlineLeft(0.5);
            case ASSISTANT_RIGHT -> pitch.touchlineRight(0.5);
        };
    }

    private RefereeActor actor(RefereeActor.Role role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case CENTER -> center;
            case ASSISTANT_LEFT -> assistantLeft;
            case ASSISTANT_RIGHT -> assistantRight;
        };
    }

    private Location rememberedPos(RefereeActor.Role role) {
        return switch (role) {
            case CENTER -> centerPos;
            case ASSISTANT_LEFT -> leftPos;
            case ASSISTANT_RIGHT -> rightPos;
        };
    }

    private void remember(RefereeActor.Role role, Location at) {
        switch (role) {
            case CENTER -> centerPos = at.clone();
            case ASSISTANT_LEFT -> leftPos = at.clone();
            case ASSISTANT_RIGHT -> rightPos = at.clone();
        }
    }

    private Collection<Player> participants() {
        List<Player> list = new ArrayList<>();
        game.forEachParticipant(list::add);
        return list;
    }
}
