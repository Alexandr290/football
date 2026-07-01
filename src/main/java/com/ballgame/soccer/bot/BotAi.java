package com.ballgame.soccer.bot;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.event.BallKickEvent;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tactical AI for match bots.
 *
 * <p>The brain is split into three layers:
 * <ul>
 *   <li><b>Team shape</b> — every off-ball bot holds a formation slot that slides
 *       with the ball, so a side keeps its structure instead of swarming.</li>
 *   <li><b>Support play</b> — the players closest to the ball break from their slot
 *       to offer short, angled passing options in open lanes ("grouping to accept
 *       the ball"), while attackers make runs into space and a spare defender
 *       drops to cover.</li>
 *   <li><b>On-ball decisions</b> — the ball carrier reads pressure and looks for a
 *       line-breaking pass, a run onto a teammate, a shot, or a dribble.</li>
 * </ul>
 *
 * <p>The goalkeeper is modelled separately: he narrows the angle on his line,
 * sweeps off it to claim loose balls and through-balls, saves reliably, and then
 * launches the ball back up the field to the best-placed teammate.</p>
 */
public final class BotAi {

    private static final double SHOOT_RANGE = 26.0;
    private static final double PASS_RANGE = 26.0;
    /** Supporters offer a passing option inside this band of the ball (blocks). */
    private static final double SUPPORT_MIN = 4.5;
    private static final double SUPPORT_MAX = 9.0;
    /** Keep teammates at least this far apart (blocks). */
    private static final double TEAMMATE_SEP_MIN = 2.0;
    private static final double TEAMMATE_SEP_TARGET = 3.5;
    /** Field inset when checking kicks/passes so bots stay away from touchlines. */
    private static final double PATH_INSET = 2.0;
    /** Only switch chaser when a teammate is this many blocks closer (reduces yo-yo). */
    private static final double CHASER_SWITCH_MARGIN = 4.0;
    /** How far in front of the goal line the keeper may advance to cut angles. */
    private static final double KEEPER_MAX_ADVANCE = 5.0;
    /** Radius (around own goal) inside which the keeper will sweep out to claim. */
    private static final double KEEPER_CLAIM_RADIUS = 12.5;

    /** Per-team ball chaser resolution result. */
    public record BallChaserState(UUID botId, boolean humanChasing) {
    }

    private BotAi() {
    }

    static void think(Game game, MatchBot bot, long matchTick) {
        if (!game.getState().isLive() || game.isCelebrating() || !bot.canAct()) {
            return;
        }
        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return;
        }
        Location ballLoc = ball.getLocation();
        Location self = bot.getLocation();
        if (self == null) {
            return;
        }

        TeamSide team = bot.getTeam();
        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());
        double distToBall = horizontalDistance(self, ballLoc);
        TeamSide possession = ball.getLastTouchTeam();

        // Goalkeeper only tracks the ball — never joins the outfield swarm.
        if (isGoalkeeper(bot)) {
            handleGoalkeeper(game, bot, ball, ballLoc, ownGoal, oppGoal, matchTick);
            return;
        }

        UUID chaserId = game.getBallChaserBotId(team);
        boolean humanChases = game.isHumanChasingBall(team);
        boolean isChaser = !humanChases && bot.getUuid().equals(chaserId);

        if (!isChaser) {
            handleOffBall(game, bot, ballLoc, ownGoal, oppGoal, team, possession, chaserId);
            return;
        }

        if (distToBall > game.getSettings().kickReach * 0.9) {
            Location chaseTarget = interceptPoint(ball, ballLoc, self, true, bot.getAttributes().anticipation);
            double urgency = 0.85 + 0.15 * dangerLevel(game, ballLoc, ownGoal, oppGoal);
            bot.moveToward(chaseTarget, sprintDecision(bot, distToBall, 2.5, urgency), game.getSettings());
            return;
        }
        if (possession == team || possession == null) {
            handleAttack(game, bot, ballLoc, oppGoal, ownGoal, distToBall, matchTick);
        } else {
            handlePress(game, bot, ball, ballLoc, oppGoal, distToBall, matchTick);
        }
    }

    /**
     * Picks exactly one chaser per team per tick. Humans take priority; otherwise
     * the nearest eligible outfield bot, with sticky hysteresis so roles do not
     * flip every tick.
     */
    public static BallChaserState resolveBallChaser(Game game, Location ballLoc, TeamSide team, UUID stickyId) {
        UUID closestHuman = null;
        double closestHumanDist = Double.MAX_VALUE;

        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != team || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            double dist = horizontalDistance(player.getLocation(), ballLoc);
            if (dist < closestHumanDist) {
                closestHumanDist = dist;
                closestHuman = sp.getUuid();
            }
        }

        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());
        double ballProgress = progressAlongPitch(ballLoc, ownGoal, oppGoal);

        UUID bestBotId = null;
        double bestScore = Double.MAX_VALUE;

        for (MatchBot bot : game.getBots()) {
            if (bot.getTeam() != team || isGoalkeeper(bot)) {
                continue;
            }
            Location loc = bot.getLocation();
            if (loc == null) {
                continue;
            }
            double dist = horizontalDistance(loc, ballLoc);
            double roleWeight = bot.getRole().chaseWeight();
            if (bot.getRole() == BotRole.DEFENDER && ballProgress > 0.52) {
                roleWeight *= 0.12;
            }
            if (bot.getRole() == BotRole.MIDFIELDER && ballProgress > 0.68) {
                roleWeight *= 0.35;
            }
            if (bot.getRole() == BotRole.STRIKER && ballProgress < 0.30) {
                roleWeight *= 0.45;
            }
            if (bot.getRole() == BotRole.WINGER && ballProgress < 0.25) {
                roleWeight *= 0.55;
            }
            double score = dist / Math.max(0.15, roleWeight);
            if (score < bestScore) {
                bestScore = score;
                bestBotId = bot.getUuid();
            }
        }

        // Human takes the ball when clearly closer than the best bot.
        if (closestHuman != null) {
            MatchBot bestBot = bestBotId != null ? game.getBot(bestBotId) : null;
            double bestBotDist = bestBot != null && bestBot.getLocation() != null
                    ? horizontalDistance(bestBot.getLocation(), ballLoc) : Double.MAX_VALUE;
            if (closestHumanDist + 1.5 < bestBotDist) {
                return new BallChaserState(null, true);
            }
        }

        if (stickyId != null && bestBotId != null) {
            MatchBot sticky = game.getBot(stickyId);
            if (sticky != null && sticky.getTeam() == team && !isGoalkeeper(sticky)) {
                double stickyDist = horizontalDistance(sticky.getLocation(), ballLoc);
                MatchBot challenger = game.getBot(bestBotId);
                double challengerDist = challenger != null
                        ? horizontalDistance(challenger.getLocation(), ballLoc) : stickyDist;
                if (stickyDist <= challengerDist + CHASER_SWITCH_MARGIN) {
                    return new BallChaserState(stickyId, false);
                }
            }
        }

        return new BallChaserState(bestBotId, false);
    }

    /**
     * Stamina-paced sprint gate. The bot will burst when it matters, but conserves
     * its legs when tired and the situation is not urgent — so it can still sprint
     * for the decisive moments late in a match.
     */
    private static boolean sprintDecision(MatchBot bot, double distance, double minDistance, double urgency) {
        if (!bot.canSprint() || distance < minDistance) {
            return false;
        }
        double stamina = bot.staminaFraction();
        if (stamina < 0.18) {
            return urgency >= 0.85;
        }
        if (stamina < 0.4) {
            return urgency >= 0.48;
        }
        double eagerness = urgency + bot.getAttributes().workRate * 0.25;
        return eagerness >= 0.34;
    }

    /** 0..1 — how dangerous the ball's current position is (near either goal). */
    private static double dangerLevel(Game game, Location ballLoc, Location ownGoal, Location oppGoal) {
        double toOwn = horizontalDistance(ballLoc, ownGoal);
        double toOpp = horizontalDistance(ballLoc, oppGoal);
        double nearest = Math.min(toOwn, toOpp);
        return Math.max(0.0, Math.min(1.0, 1.0 - nearest / 22.0));
    }

    private static boolean isGoalkeeper(MatchBot bot) {
        return bot.getRole() == BotRole.GOALKEEPER;
    }

    // ==================================================================
    // Goalkeeper
    // ==================================================================

    /**
     * Keeper plays like a real shot-stopper: he stands on the line between the
     * ball and the centre of his goal (narrowing the angle), sweeps off his line
     * to claim loose balls and through-balls he can win, saves reliably, and then
     * distributes — see {@link #attemptGoalkeeperCatch}.
     */
    private static void handleGoalkeeper(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                         Location ownGoal, Location oppGoal, long matchTick) {
        KeeperRead read = readKeeperSituation(game, bot.getTeam(), ball, ballLoc, ownGoal, oppGoal);
        Location self = bot.getLocation();

        boolean rush = shouldKeeperRush(game, bot, ball, ballLoc, ownGoal);
        Location target = rush
                ? interceptPoint(ball, ballLoc, self, true, bot.getAttributes().anticipation)
                : read.target();
        boolean alert = read.alert() || rush;

        bot.faceLocation(ballLoc);

        double distToTarget = horizontalDistance(self, target);
        if (rush) {
            bot.moveToward(target, sprintDecision(bot, distToTarget, 0.6, 1.0), game.getSettings());
        } else if (distToTarget > 0.55) {
            double urgency = alert ? 1.0 : 0.55;
            bot.moveToward(target, sprintDecision(bot, distToTarget, 0.8, urgency), game.getSettings());
        } else if (distToTarget > 0.25) {
            bot.moveToward(target, false, game.getSettings());
        } else {
            bot.holdPosition();
        }

        attemptGoalkeeperCatch(game, bot, ball, ballLoc, alert, matchTick);
    }

    /**
     * The keeper leaves his line to claim the ball when it is loose in (or rolling
     * into) his box and he can reach it before any opponent — exactly when a real
     * keeper comes out to smother a through-ball or beat a striker to it.
     */
    private static boolean shouldKeeperRush(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                            Location ownGoal) {
        Location self = bot.getLocation();
        if (self == null || ballLoc == null) {
            return false;
        }
        double ballToGoal = horizontalDistance(ballLoc, ownGoal);
        if (ballToGoal > KEEPER_CLAIM_RADIUS) {
            return false;
        }
        double keeperToBall = horizontalDistance(self, ballLoc);
        if (keeperToBall > KEEPER_CLAIM_RADIUS - 2.0) {
            return false;
        }
        // Don't charge out for a ball that is already trickling behind the goal line.
        double selfToGoal = horizontalDistance(self, ownGoal);
        if (ballToGoal > selfToGoal + 3.0) {
            return false;
        }
        double oppToBall = nearestOpponentDistance(game, bot.getTeam(), ballLoc);
        double ballSpeed = ball != null ? ball.getSpeed() : 0.0;
        boolean canWin = keeperToBall <= oppToBall + 0.6;
        boolean loose = ballSpeed < 0.55 && keeperToBall < 6.5;
        return canWin || loose;
    }

    /** Spawn position centred on the goal line, facing the pitch. */
    public static Location keeperSpawnSpot(Game game, TeamSide team) {
        Location spot = goalLineSpot(game.getArena(), team);
        if (spot != null) {
            return spot.clone();
        }
        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());
        return keeperPost(game, team, null, ownGoal, oppGoal);
    }

    /**
     * Centre of the marked goal mouth ({@code setredgoal} / {@code setbluegoal}),
     * on the pitch side of the goal line, facing the opponent.
     */
    private static Location goalLineSpot(Arena arena, TeamSide team) {
        Cuboid goal = arena.getGoal(team);
        if (goal == null || goal.getWorld() == null) {
            return null;
        }

        Location oppCenter = goalCenter(arena, team.opposite());
        Cuboid field = arena.getField();
        Location pitchRef = oppCenter;
        if (pitchRef == null && field != null) {
            pitchRef = field.getCenter();
        }
        if (pitchRef == null) {
            pitchRef = goal.getCenter();
        }

        double goalMidX = (goal.getMinX() + goal.getMaxX()) * 0.5;
        double goalMidZ = (goal.getMinZ() + goal.getMaxZ()) * 0.5;
        double towardX = pitchRef.getX() - goalMidX;
        double towardZ = pitchRef.getZ() - goalMidZ;

        double y = goal.getMinY() + 0.05;
        if (field != null) {
            y = Math.max(field.getMinY() + 0.05, y);
        }

        double standOff = 0.35;
        double x;
        double z;
        if (Math.abs(towardX) >= Math.abs(towardZ)) {
            z = Math.max(goal.getMinZ() + 0.5, Math.min(goal.getMaxZ() - 0.5, goalMidZ));
            if (towardX >= 0.0) {
                x = goal.getMaxX() + standOff;
            } else {
                x = goal.getMinX() - standOff;
            }
        } else {
            x = Math.max(goal.getMinX() + 0.5, Math.min(goal.getMaxX() - 0.5, goalMidX));
            if (towardZ >= 0.0) {
                z = goal.getMaxZ() + standOff;
            } else {
                z = goal.getMinZ() - standOff;
            }
        }

        Location spot = new Location(goal.getWorld(), x, y, z);
        if (oppCenter != null) {
            Vector facing = flatDirection(spot, oppCenter);
            if (facing.lengthSquared() > 1e-6) {
                spot.setDirection(facing.normalize());
            }
        }
        return spot;
    }

    private static Location keeperPost(Game game, TeamSide team, Location ballLoc,
                                       Location ownGoal, Location oppGoal) {
        Location post = goalLineSpot(game.getArena(), team);
        if (post == null) {
            Vector fieldDir = flatDirection(ownGoal, oppGoal);
            if (fieldDir.lengthSquared() < 1e-6) {
                fieldDir = new Vector(0, 0, 1);
            }
            fieldDir.normalize();
            post = ownGoal.clone().add(fieldDir.clone().multiply(0.05));
            post.setY(ownGoal.getY());
        } else {
            post = post.clone();
        }

        Vector fieldDir = flatDirection(post, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            fieldDir = new Vector(0, 0, 1);
        }
        fieldDir.normalize();
        Vector lateral = new Vector(-fieldDir.getZ(), 0, fieldDir.getX());

        if (ballLoc != null) {
            Vector relativeBall = ballLoc.toVector().subtract(post.toVector());
            relativeBall.setY(0);
            double shift = relativeBall.dot(lateral);
            post.add(lateral.clone().multiply(Math.max(-6.0, Math.min(6.0, shift))));
            post.setY(Math.max(post.getY(), ballLoc.getY()));
        }
        Cuboid goal = game.getArena().getGoal(team);
        return clampNearGoalMouth(goal, post, fieldDir);
    }

    /** Keeps the keeper on the goal mouth width while tracking the ball laterally. */
    private static Location clampNearGoalMouth(Cuboid goal, Location post, Vector fieldDir) {
        if (goal == null || post == null || fieldDir == null) {
            return post;
        }
        Vector flat = fieldDir.clone();
        flat.setY(0);
        if (flat.lengthSquared() < 1e-6) {
            return post;
        }
        flat.normalize();
        if (Math.abs(flat.getX()) >= Math.abs(flat.getZ())) {
            post.setZ(Math.max(goal.getMinZ() + 0.5, Math.min(goal.getMaxZ() - 0.5, post.getZ())));
        } else {
            post.setX(Math.max(goal.getMinX() + 0.5, Math.min(goal.getMaxX() - 0.5, post.getX())));
        }
        return post;
    }

    private static KeeperRead readKeeperSituation(Game game, TeamSide team, Ball ball, Location ballLoc,
                                                  Location ownGoal, Location oppGoal) {
        Location centerPost = keeperPost(game, team, null, ownGoal, oppGoal);
        if (ball == null || ballLoc == null || centerPost == null) {
            return new KeeperRead(centerPost, false);
        }

        Cuboid goal = game.getArena().getGoal(team);
        double halfWidth = goalHalfWidth(goal, centerPost, oppGoal);
        Vector fieldDir = flatDirection(centerPost, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            fieldDir = new Vector(0, 0, 1);
        }
        fieldDir.normalize();
        Vector lateral = new Vector(-fieldDir.getZ(), 0, fieldDir.getX());

        Vector rel = ballLoc.toVector().subtract(centerPost.toVector());
        rel.setY(0);
        double depth = rel.dot(fieldDir);     // positive = in front of goal, negative = behind goal
        double side = rel.dot(lateral);
        double ballDist = Math.max(0.3, Math.hypot(depth, side));

        Vector vel = ball.getVelocity();
        vel.setY(0);
        double towardGoalSpeed = -vel.dot(fieldDir);
        double lateralSpeed = vel.dot(lateral);
        double speed = vel.length();

        boolean centralDanger = depth > -0.8 && depth < 12.0 && Math.abs(side) <= halfWidth + 1.6;
        boolean shotTowardGoal = false;
        double targetSide = side;

        if (towardGoalSpeed > 0.04 && speed > 0.14 && depth > -1.0 && depth < 36.0) {
            double ticksToLine = depth / towardGoalSpeed;
            double predictedSide = side + lateralSpeed * ticksToLine;
            shotTowardGoal = ticksToLine >= 0.0
                    && ticksToLine <= 70.0
                    && Math.abs(predictedSide) <= halfWidth + 1.0;
            if (shotTowardGoal) {
                targetSide = predictedSide;
            }
        }

        // Stand on the line between the ball and the centre of the goal, stepping
        // off the line to cut the angle. Come further out the further the ball is,
        // but cap it so he is never stranded in no-man's-land.
        double maxSide = Math.max(0.0, halfWidth - 0.25);
        double clampedSide = Math.max(-maxSide, Math.min(maxSide, targetSide * 0.92));
        double advance = Math.max(0.8, Math.min(KEEPER_MAX_ADVANCE, ballDist * 0.30));
        if (shotTowardGoal) {
            // Step out a touch more to meet a developing shot, then set.
            advance = Math.min(KEEPER_MAX_ADVANCE, advance + Math.min(2.0, depth / 12.0));
        }
        if (depth < 0) {
            // Ball level with or behind the line: sit on the line and just track across.
            advance = 0.4;
        }

        Location target = centerPost.clone()
                .add(lateral.clone().multiply(clampedSide))
                .add(fieldDir.clone().multiply(advance));
        target.setY(centerPost.getY());

        boolean alert = centralDanger || shotTowardGoal;
        return new KeeperRead(clampToField(game.getArena(), target), alert);
    }

    private static double goalHalfWidth(Cuboid goal, Location centerPost, Location oppGoal) {
        if (goal == null || centerPost == null || oppGoal == null) {
            return 2.6;
        }
        Vector fieldDir = flatDirection(centerPost, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            return 2.6;
        }
        fieldDir.normalize();
        return Math.abs(fieldDir.getX()) >= Math.abs(fieldDir.getZ())
                ? Math.max(1.2, goal.getSizeZ() * 0.5)
                : Math.max(1.2, goal.getSizeX() * 0.5);
    }

    /**
     * Reliable shot-stopping. A "smart" keeper claims nearly everything he can
     * physically reach; only genuinely fast, well-placed strikes near the edge of
     * his reach beat him. On a clean claim he immediately distributes the ball.
     */
    private static void attemptGoalkeeperCatch(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                               boolean alert, long matchTick) {
        if (!bot.canAct() || ballLoc == null || bot.getLocation() == null) {
            return;
        }
        double dist = horizontalDistance(bot.getLocation(), ballLoc);
        double vert = Math.abs(bot.getLocation().getY() - ballLoc.getY());
        BotDifficulty difficulty = game.getBotDifficulty(bot.getTeam());
        double reach = Math.max(1.85, game.getSettings().kickReach * 1.0);
        if (difficulty == BotDifficulty.EASY) {
            reach *= 0.78;
        } else if (difficulty == BotDifficulty.HARD) {
            reach *= 1.28;
        }
        if (dist > reach || vert > 2.4) {
            return;
        }
        double speed = ball != null ? ball.getSpeed() : 0.0;
        // Only act on balls that are a threat or genuinely within control range —
        // don't grab harmless balls dribbled around the edge of the box.
        if (!alert && speed < 0.45 && dist > reach * 0.8) {
            return;
        }

        double quality = 0.82 + bot.getAttributes().anticipation * 0.10 + bot.getAttributes().agility * 0.05;
        // Hard, placed shots at the limit of his reach can still beat him.
        quality -= Math.max(0.0, speed - 0.9) * 0.12;
        quality -= Math.max(0.0, dist / reach - 0.7) * 0.18;
        if (difficulty == BotDifficulty.EASY) {
            quality -= 0.34;
            quality -= Math.max(0.0, speed - 0.55) * 0.22;
        } else if (difficulty == BotDifficulty.HARD) {
            quality += 0.18;
            quality -= Math.max(0.0, speed - 1.2) * 0.05;
        }
        quality = Math.max(0.55, Math.min(0.97, quality));
        if (difficulty == BotDifficulty.EASY) {
            quality = Math.max(0.18, Math.min(0.58, quality));
        } else if (difficulty == BotDifficulty.HARD) {
            quality = Math.max(0.88, Math.min(0.995, quality));
        }
        if (keeperMistakeRoll(bot, matchTick) > quality) {
            // Parry: he gets a touch but doesn't gather it cleanly.
            bot.setActionCooldown(5);
            return;
        }
        game.botGoalkeeperSave(bot);
        // Recover quickly so he can reset on his line for any counter-attack.
        bot.setActionCooldown(8);
    }

    /**
     * Where the keeper should send the ball after gathering it: always try the
     * nearest outfield teammate first (human or bot), because keepers in real play
     * usually release to the closest safe outlet before punting long.
     */
    public static Location keeperDistributionTarget(Game game, MatchBot keeper) {
        if (keeper == null) {
            return null;
        }
        TeamSide team = keeper.getTeam();
        Location ballLoc = game.getBallLocation();
        Location from = ballLoc != null ? ballLoc : keeper.getLocation();
        if (from == null) {
            return null;
        }

        Location nearest = nearestTeammateOutlet(game, keeper, from);
        if (nearest != null) {
            return nearest;
        }

        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());
        return keeperClearanceFallback(game, team, from, ownGoal, oppGoal);
    }

    private static Location nearestTeammateOutlet(Game game, MatchBot keeper, Location from) {
        TeamSide team = keeper.getTeam();
        Location best = null;
        Vector bestVelocity = null;
        double bestDist = Double.MAX_VALUE;

        for (MatchBot mate : game.getBots()) {
            if (mate.getUuid().equals(keeper.getUuid()) || mate.getTeam() != team || isGoalkeeper(mate)) {
                continue;
            }
            Location loc = mate.getLocation();
            if (loc == null) {
                continue;
            }
            double dist = horizontalDistance(from, loc);
            if (dist < bestDist) {
                bestDist = dist;
                best = loc;
                bestVelocity = mate.getVelocity();
            }
        }

        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != team || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location loc = player.getLocation();
            double dist = horizontalDistance(from, loc);
            if (dist < bestDist) {
                bestDist = dist;
                best = loc;
                bestVelocity = player.getVelocity();
            }
        }

        if (best != null) {
            Location target = leadNearestOutlet(best, bestVelocity, bestDist);
            target = clampToField(game.getArena(), target);
            if (target != null && !kickThreatensOwnGoal(from, target, goalCenter(game.getArena(), team))) {
                return target;
            }
        }
        return best;
    }

    private static Location leadNearestOutlet(Location loc, Vector velocity, double distance) {
        if (loc == null || velocity == null) {
            return loc != null ? loc.clone() : null;
        }
        Vector flat = velocity.clone();
        flat.setY(0);
        double speed = flat.length();
        if (speed < 0.10 || distance < 8.0) {
            return loc.clone();
        }
        double lead = Math.min(4.0, Math.max(1.2, distance * 0.12));
        Location target = loc.clone().add(flat.normalize().multiply(lead));
        target.setY(loc.getY());
        return target;
    }

    private static PassOption scoreKeeperTarget(Game game, TeamSide team, Location from, Location target,
                                                BotRole role, Location oppGoal, Location ownGoal,
                                                boolean isHuman) {
        if (target == null) {
            return null;
        }
        double dist = horizontalDistance(from, target);
        if (dist < 6.0 || dist > 70.0) {
            return null;
        }
        if (!insideFieldInset(game.getArena().getField(), target, PATH_INSET)) {
            return null;
        }
        if (kickThreatensOwnGoal(from, target, ownGoal)) {
            return null;
        }
        // Must actually move the ball up the field.
        double fromGoalDist = horizontalDistance(from, oppGoal);
        double targetGoalDist = horizontalDistance(target, oppGoal);
        if (targetGoalDist > fromGoalDist - 4.0) {
            return null;
        }
        // A short, rolled throw/kick needs a clear ground lane; a long punt flies
        // over outfielders so only the landing spot needs to be safe-ish.
        if (dist < 16.0 && !hasPassLane(game, team, from, target)) {
            return null;
        }

        double forwardProgress = (fromGoalDist - targetGoalDist) / 60.0;
        double openness = 1.0 / (1.0 + countOpponentsNear(game, team, target, 5.0));
        double roleFit = switch (role) {
            case STRIKER, WINGER -> 1.2;
            case PLAYMAKER, MIDFIELDER -> 1.0;
            default -> 0.8;
        };
        double humanBonus = isHuman ? 0.6 : 0.0;
        double score = forwardProgress * 2.2 + openness * 1.6 + roleFit * 0.4 + humanBonus
                - Math.max(0.0, dist - 45.0) * 0.01;
        return new PassOption(target, score, 0.0, false, 0.0, isHuman);
    }

    /** Big clearance into the more open channel up the field when nobody is on. */
    private static Location keeperClearanceFallback(Game game, TeamSide team, Location from,
                                                    Location ownGoal, Location oppGoal) {
        PitchFrame pf = pitchFrame(game, ownGoal, oppGoal);
        Location bestSpot = null;
        double bestOpen = -1.0;
        for (double laneFrac : new double[] {-0.6, 0.0, 0.6}) {
            Location spot = pf.center().clone()
                    .add(pf.forward().clone().multiply(pf.halfLength() * 0.45))
                    .add(pf.lateral().clone().multiply(laneFrac * pf.halfWidth()));
            spot.setY(from.getY());
            spot = clampToField(game.getArena(), spot);
            if (kickThreatensOwnGoal(from, spot, ownGoal)) {
                continue;
            }
            double open = 1.0 / (1.0 + countOpponentsNear(game, team, spot, 6.0));
            if (open > bestOpen) {
                bestOpen = open;
                bestSpot = spot;
            }
        }
        if (bestSpot != null) {
            return bestSpot;
        }
        Location fallback = pf.center().clone().add(pf.forward().clone().multiply(pf.halfLength() * 0.4));
        fallback.setY(from.getY());
        return clampToField(game.getArena(), fallback);
    }

    private static double keeperMistakeRoll(MatchBot bot, long matchTick) {
        return aiRoll(bot, matchTick, 0x6BEE7A);
    }

    private static double aiRoll(MatchBot bot, long matchTick, long salt) {
        long bucket = matchTick / 8L;
        long seed = bot.getUuid().getMostSignificantBits()
                ^ Long.rotateLeft(bot.getUuid().getLeastSignificantBits(), 17)
                ^ (bucket * 0x9E3779B97F4A7C15L)
                ^ salt;
        seed ^= (seed >>> 33);
        seed *= 0xff51afd7ed558ccdL;
        seed ^= (seed >>> 33);
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= (seed >>> 33);
        return (seed >>> 11) * 0x1.0p-53;
    }

    // ==================================================================
    // On-ball: attacking
    // ==================================================================

    private static void handleAttack(Game game, MatchBot bot, Location ballLoc, Location oppGoal,
                                     Location ownGoal, double distToBall, long matchTick) {
        bot.faceLocation(oppGoal);
        Location self = bot.getLocation();
        double goalDist = horizontalDistance(self, oppGoal);
        double ownDist = horizontalDistance(self, ownGoal);
        double goalAngle = angleToGoal(self, oppGoal);
        int pressure = countOpponentsNear(game, bot.getTeam(), ballLoc, 4.5);
        BotDifficulty difficulty = game.getBotDifficulty(bot.getTeam());

        if (difficulty == BotDifficulty.EASY && aiRoll(bot, matchTick, 0xE45A) < 0.16) {
            bot.holdPosition();
            bot.setActionCooldown(3);
            return;
        }

        if (difficulty == BotDifficulty.HARD
                && distToBall <= game.getSettings().kickReach * 1.15
                && goalDist < 31.0
                && goalAngle < 72.0
                && ownDist > goalDist
                && avoidsOwnGoal(self, oppGoal, ownGoal)) {
            Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
            if (aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)
                    && isSafeBallPath(game, ballLoc, aim, PATH_INSET * 0.75)) {
                shootAtGoal(game, bot, oppGoal, ownGoal, goalDist, aim);
                bot.setActionCooldown(9);
                return;
            }
        }

        PassOption pass = findBestPass(game, bot, ballLoc, oppGoal, ownGoal);
        if (pass != null) {
            double targetGoalDist = horizontalDistance(pass.target(), oppGoal);
            boolean throughBall = targetGoalDist < goalDist - 0.8;
            boolean teammateAhead = targetGoalDist < goalDist - 0.3;
            boolean passToHuman = pass.receiverHuman();
            boolean underPressure = pressure >= 1;
            boolean playmaker = bot.getRole().passBias() >= 0.45;
            boolean inOwnHalf = ownDist < goalDist;
            boolean shouldPass = throughBall || teammateAhead || underPressure || passToHuman
                    || pass.score() > 0.5
                    || (playmaker && pass.score() > 0.32)
                    || (inOwnHalf && pass.score() > 0.25);
            if (difficulty == BotDifficulty.EASY) {
                shouldPass = shouldPass && pass.score() > 0.55;
            } else if (difficulty == BotDifficulty.HARD) {
                shouldPass = shouldPass || pass.score() > 0.22 || (throughBall && pass.score() > 0.12);
            }

            if (shouldPass && isSafeBallPath(game, ballLoc, pass.target(), PATH_INSET)
                    && !kickThreatensOwnGoal(ballLoc, pass.target(), ownGoal)) {
                bot.faceLocation(pass.target());
                if (distToBall <= game.getSettings().kickReach * 1.05) {
                    if (pass.strongKick()) {
                        game.botStrongPassAt(bot, pass.target(), pass.charge(), pass.lift());
                    } else {
                        game.botPassAt(bot, pass.target(), pass.charge());
                    }
                    bot.setActionCooldown(12);
                } else {
                    bot.moveToward(ballLoc, sprintDecision(bot, distToBall, 3.0, 0.65), game.getSettings());
                }
                return;
            }
        }

        double directShotRange = switch (difficulty) {
            case EASY -> 14.0;
            case MEDIUM -> 22.0;
            case HARD -> 31.0;
        };
        if (distToBall <= game.getSettings().kickReach * 1.05 && goalDist < directShotRange
                && ownDist > goalDist && avoidsOwnGoal(self, oppGoal, ownGoal)) {
            Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
            if (aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)
                    && isSafeBallPath(game, ballLoc, aim, PATH_INSET * 0.85)) {
                shootAtGoal(game, bot, oppGoal, ownGoal, goalDist, aim);
                bot.setActionCooldown(16);
                return;
            }
        }

        if (shouldShoot(game, bot, goalDist, goalAngle, pressure, ownDist, matchTick)
                && avoidsOwnGoal(self, oppGoal, ownGoal)) {
            bot.moveToward(ballLoc, false, game.getSettings());
            bot.faceLocation(oppGoal);
            if (distToBall <= game.getSettings().kickReach * 1.05) {
                Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
                if (aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)
                        && isSafeBallPath(game, ballLoc, aim, PATH_INSET * 0.85)) {
                    shootAtGoal(game, bot, oppGoal, ownGoal, goalDist, aim);
                    bot.setActionCooldown(16);
                }
            }
            return;
        }

        if (distToBall <= game.getSettings().kickReach * 1.05) {
            Vector carry = dribbleDirection(self, oppGoal, ownGoal, game, bot.getTeam());
            bot.faceDirection(carry);
            game.botDribble(bot);
            bot.setActionCooldown(2);
            if (goalDist > 6.0) {
                Location advance = curvedAdvance(self, oppGoal, carry, game, bot.getTeam());
                double space = pressure == 0 ? 0.75 : 0.4;
                bot.moveToward(advance, sprintDecision(bot, goalDist, 10.0, space), game.getSettings());
            }
            return;
        }

        bot.moveToward(ballLoc, sprintDecision(bot, distToBall, 4.0, 0.75), game.getSettings());
    }

    private static PassOption findBestPass(Game game, MatchBot bot, Location ballLoc,
                                           Location oppGoal, Location ownGoal) {
        PassOption best = null;
        double bestScore = 0.10;
        UUID chaserId = game.getBallChaserBotId(bot.getTeam());

        // Prefer real players when a safe lane exists. Aim at their current feet,
        // not far ahead of them, so the ball arrives directly to the player.
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != bot.getTeam() || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            PassOption option = scorePassTarget(game, bot, player.getLocation(), BotRole.STRIKER,
                    ballLoc, oppGoal, ownGoal, true, false);
            if (option != null && option.score() > bestScore) {
                bestScore = option.score();
                best = option;
            }
        }

        for (MatchBot teammate : game.getBots()) {
            if (teammate.getUuid().equals(bot.getUuid()) || teammate.getUuid().equals(chaserId)
                    || isGoalkeeper(teammate)) {
                continue;
            }
            Location loc = teammate.getLocation();
            if (loc == null) {
                continue;
            }
            PassOption option = scorePassTarget(game, bot, loc, teammate.getRole(),
                    ballLoc, oppGoal, ownGoal, false, false);
            if (option != null && option.score() > bestScore) {
                bestScore = option.score();
                best = option;
            }
        }
        return best;
    }

    private static PassOption scorePassTarget(Game game, MatchBot passer, Location targetLoc,
                                              BotRole targetRole, Location ballLoc, Location oppGoal,
                                              Location ownGoal, boolean isHuman, boolean intoSpace) {
        if (targetLoc == null) {
            return null;
        }
        if (!isSafeBallPath(game, ballLoc, targetLoc, PATH_INSET)) {
            return null;
        }
        if (kickThreatensOwnGoal(ballLoc, targetLoc, ownGoal)) {
            return null;
        }
        double distFromBall = horizontalDistance(targetLoc, ballLoc);
        double minDist = isHuman ? 1.6 : 2.5;
        if (distFromBall < minDist || distFromBall > PASS_RANGE) {
            return null;
        }
        if (countOpponentsNear(game, passer.getTeam(), targetLoc, 2.35) > 0) {
            return null;
        }
        if (!hasPassLane(game, passer.getTeam(), ballLoc, targetLoc)) {
            return null;
        }

        double passerGoalDist = horizontalDistance(ballLoc, oppGoal);
        double targetGoalDist = horizontalDistance(targetLoc, oppGoal);
        double forwardProgress = (passerGoalDist - targetGoalDist) / Math.max(1.0, PASS_RANGE);
        double goalProgress = 1.0 / (1.0 + targetGoalDist);
        double openness = 1.0 / (1.0 + countOpponentsNear(game, passer.getTeam(), targetLoc, 4.5));
        double roleFit = switch (targetRole) {
            case STRIKER -> 1.35;
            case WINGER -> 1.15;
            case PLAYMAKER -> 1.1;
            default -> 1.0;
        };
        double humanBonus = isHuman ? 4.0 : 0.0;
        double forwardBonus = forwardProgress > 0 ? forwardProgress * 2.4 : forwardProgress * 0.4;
        // A pass that takes the ball beyond the nearest defender breaks a line.
        double lineBreakBonus = breaksDefensiveLine(game, passer.getTeam(), ballLoc, targetLoc, oppGoal)
                ? 0.6 : 0.0;
        double spaceBonus = intoSpace && forwardProgress > 0 ? 0.5 : 0.0;
        double score = goalProgress * 1.8 + openness * 1.4 + roleFit * 0.45 + humanBonus
                + forwardBonus + lineBreakBonus + spaceBonus
                - distFromBall * 0.012 + passer.getRole().passBias() * 0.4;
        boolean lineBreakingPass = lineBreakBonus > 0.0 && distFromBall > 9.0;
        boolean passIntoRun = intoSpace && forwardProgress > 0.08 && distFromBall > 10.0;
        boolean strongKick = distFromBall > 16.0 || lineBreakingPass || passIntoRun;
        double distanceFactor = clamp((distFromBall - 8.0) / 22.0, 0.0, 1.0);
        double situationalBoost = 0.0;
        if (lineBreakingPass) {
            situationalBoost += 0.07;
        }
        if (passIntoRun) {
            situationalBoost += 0.05;
        }
        double charge = strongKick
                ? clamp(0.18 + distanceFactor * 0.24 + situationalBoost, 0.18, 0.52)
                : Math.min(0.72, distFromBall / 21.0);
        if (charge < 0.26) {
            charge = 0.26;
        }
        double lift = strongKick
                ? clamp(0.025 + distanceFactor * 0.06 + (lineBreakingPass ? 0.015 : 0.0), 0.02, 0.10)
                : 0.0;
        return new PassOption(targetLoc, score, charge, strongKick, lift, isHuman);
    }

    /** True when the target sits goal-side of the closest opponent to the ball. */
    private static boolean breaksDefensiveLine(Game game, TeamSide team, Location ballLoc,
                                               Location target, Location oppGoal) {
        Location nearestOpp = nearestOpponentLocation(game, team, ballLoc);
        if (nearestOpp == null) {
            return false;
        }
        double oppToGoal = horizontalDistance(nearestOpp, oppGoal);
        double targetToGoal = horizontalDistance(target, oppGoal);
        return targetToGoal + 1.5 < oppToGoal;
    }

    /**
     * Strike the ball goalward with distance-tuned power and a low launch height.
     * Close-range shots are driven almost flat so they stay under the bar; only
     * longer efforts get meaningful loft.
     */
    private static void shootAtGoal(Game game, MatchBot bot, Location oppGoal, Location ownGoal,
                                    double goalDist, Location aim) {
        Location ballLoc = game.getBallLocation();
        Location self = bot.getLocation();
        if (ballLoc == null || self == null) {
            return;
        }
        if (!aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)) {
            return;
        }

        BotDifficulty difficulty = game.getBotDifficulty(bot.getTeam());
        double power;
        double lift;
        if (goalDist < 8.0) {
            power = 0.58;
            lift = 0.02;
        } else if (goalDist < 14.0) {
            power = 0.68;
            lift = 0.04;
        } else if (goalDist < 20.0) {
            power = 0.82;
            lift = 0.08;
        } else {
            power = 0.92;
            lift = 0.12;
        }
        if (difficulty == BotDifficulty.EASY) {
            power *= 0.58;
            lift += 0.14;
        } else if (difficulty == BotDifficulty.HARD) {
            power *= 1.24;
            lift *= 0.42;
        }
        game.botShootAtGoal(bot, aim, power, lift);
    }

    private static Location shotAimPoint(Game game, MatchBot bot, Location ballLoc, Location goalCenter) {
        Location aim = aimPointInGoal(game.getArena(), ballLoc, bot.getTeam().opposite(), goalCenter);
        if (game.getBotDifficulty(bot.getTeam()) != BotDifficulty.HARD || aim == null || ballLoc == null) {
            return aim;
        }
        Cuboid goal = game.getArena().getGoal(bot.getTeam().opposite());
        if (goal == null) {
            return aim;
        }
        boolean useX = goal.getSizeX() >= goal.getSizeZ();
        double side = aiRoll(bot, game.getMatchTick(), 0xC001) < 0.5 ? -1.0 : 1.0;
        if (useX) {
            double half = Math.max(0.2, goal.getSizeX() * 0.38);
            aim.setX(((goal.getMinX() + goal.getMaxX()) * 0.5) + half * side);
        } else {
            double half = Math.max(0.2, goal.getSizeZ() * 0.38);
            aim.setZ(((goal.getMinZ() + goal.getMaxZ()) * 0.5) + half * side);
        }
        aim.setY(Math.max(goal.getMinY() + 0.35, Math.min(goal.getMinY() + 0.95, aim.getY())));
        return aim;
    }

    /** Centre of the defending team's goal mouth at ball height. */
    private static Location aimPointInGoal(Arena arena, Location ballLoc, TeamSide defendingTeam,
                                         Location goalCenter) {
        Cuboid goal = arena.getGoal(defendingTeam);
        if (goal == null || ballLoc == null) {
            return goalCenter;
        }
        double cx = (goal.getMinX() + goal.getMaxX()) * 0.5;
        double cz = (goal.getMinZ() + goal.getMaxZ()) * 0.5;
        double y = Math.max(goal.getMinY() + 0.5,
                Math.min(goal.getMaxY() - 0.3, ballLoc.getY()));
        return new Location(ballLoc.getWorld(), cx, y, cz);
    }

    private static boolean shouldShoot(Game game, MatchBot bot, double goalDist, double goalAngle, int pressure,
                                       double ownDist, long matchTick) {
        BotDifficulty difficulty = game.getBotDifficulty(bot.getTeam());
        double maxRange = difficulty == BotDifficulty.HARD ? SHOOT_RANGE + 8.0 : SHOOT_RANGE;
        double maxAngle = difficulty == BotDifficulty.HARD ? 76.0 : 50.0;
        if (goalDist > maxRange || goalAngle > maxAngle) {
            return false;
        }
        if (ownDist <= goalDist) {
            return false;
        }
        if (difficulty == BotDifficulty.EASY) {
            if (goalDist > 8.0 || goalAngle > 18.0) {
                return false;
            }
            if (aiRoll(bot, matchTick, 0x5C0) < 0.86) {
                return false;
            }
        }
        if (bot.getRole() == BotRole.DEFENDER && goalDist > 14.0) {
            return false;
        }
        if (difficulty != BotDifficulty.HARD
                && pressure >= 2 && goalDist > 10.0 && bot.getRole().passBias() > 0.4) {
            return false;
        }
        if (difficulty == BotDifficulty.HARD) {
            return goalDist < 29.0 || goalAngle < 54.0 || bot.getRole() == BotRole.STRIKER;
        }
        return goalDist < 14.0 || goalAngle < 20.0 || (bot.getRole() == BotRole.STRIKER && goalDist < 20.0);
    }

    private static Vector dribbleDirection(Location from, Location oppGoal, Location ownGoal,
                                           Game game, TeamSide team) {
        Vector toGoal = flatDirection(from, oppGoal);
        if (toGoal.lengthSquared() < 1e-6) {
            toGoal = new Vector(0, 0, 1);
        }
        toGoal.normalize();
        Vector lateral = new Vector(-toGoal.getZ(), 0, toGoal.getX());

        Location nearestOpp = nearestOpponentLocation(game, team, from);
        Vector direction;
        if (nearestOpp != null && horizontalDistance(from, nearestOpp) < 3.5) {
            double side = Math.signum(toGoal.getX() * (nearestOpp.getX() - from.getX())
                    + toGoal.getZ() * (nearestOpp.getZ() - from.getZ()));
            if (Math.abs(side) < 0.1) {
                side = 1.0;
            }
            direction = toGoal.clone().multiply(0.62).add(lateral.clone().multiply(0.78 * -side)).normalize();
        } else {
            direction = toGoal;
        }
        if (kickThreatensOwnGoal(from, from.clone().add(direction.clone().multiply(4.0)), ownGoal)) {
            direction = toGoal;
        }
        return keepDirectionInPlay(game, from, direction);
    }

    private static Location curvedAdvance(Location from, Location oppGoal, Vector carry,
                                          Game game, TeamSide team) {
        Location straight = from.clone().add(carry.clone().multiply(3.2));
        Location nearestOpp = nearestOpponentLocation(game, team, from);
        if (nearestOpp == null || horizontalDistance(from, nearestOpp) > 5.0) {
            return straight;
        }
        Vector lateral = new Vector(-carry.getZ(), 0, carry.getX());
        double side = Math.signum(lateral.dot(nearestOpp.toVector().subtract(from.toVector())));
        if (Math.abs(side) < 0.1) {
            side = 1.0;
        }
        Location arc = from.clone()
                .add(carry.clone().multiply(2.0))
                .add(lateral.clone().multiply(1.6 * side));
        arc.setY(from.getY());
        return arc;
    }

    // ==================================================================
    // On-ball: pressing / defending
    // ==================================================================

    private static void handlePress(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                    Location oppGoal, double distToBall, long matchTick) {
        Location ownGoal = goalCenter(game.getArena(), bot.getTeam());
        Location pressTarget = jockeyPoint(bot.getLocation(), ballLoc, oppGoal);
        double pressUrgency = 0.75 + bot.getAttributes().aggression * 0.25;

        if (shouldSlideTackle(game, bot, ball, ballLoc, distToBall, matchTick)) {
            bot.faceLocation(ballLoc);
            if (bot.slideTackleToward(ballLoc, matchTick)) {
                Location clearTarget = safeClearanceTarget(game, ballLoc, oppGoal, ownGoal);
                bot.faceLocation(clearTarget);
                game.botKick(bot, BallKickEvent.KickType.TACKLE, 0);
                bot.setActionCooldown(16);
                return;
            }
        }

        bot.moveToward(pressTarget, sprintDecision(bot, distToBall, 1.5, pressUrgency), game.getSettings());
        bot.faceLocation(ballLoc);
        if (distToBall <= game.getSettings().kickReach * 1.15) {
            Location clearTarget = safeClearanceTarget(game, ballLoc, oppGoal, ownGoal);
            bot.faceLocation(clearTarget);
            game.botKick(bot, BallKickEvent.KickType.TACKLE, 0);
            bot.setActionCooldown(20);
        }
    }

    private static boolean shouldSlideTackle(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                             double distToBall, long matchTick) {
        if (ball == null || ballLoc == null || bot.getLocation() == null || !bot.canSlideTackle(matchTick)) {
            return false;
        }
        if (distToBall < game.getSettings().kickReach * 0.85 || distToBall > 4.8) {
            return false;
        }
        if (!isSafeBallPath(game, bot.getLocation(), ballLoc, PATH_INSET)) {
            return false;
        }

        Vector toBall = flatDirection(bot.getLocation(), ballLoc);
        Vector botVelocity = bot.getVelocity();
        botVelocity.setY(0);
        if (toBall.lengthSquared() > 1e-6 && botVelocity.lengthSquared() > 1e-4) {
            double alignment = botVelocity.normalize().dot(toBall.normalize());
            if (alignment < -0.15) {
                return false;
            }
        }

        double ballSpeed = ball.getSpeed();
        double chance = 0.16 + bot.getAttributes().aggression * 0.22 + bot.getAttributes().anticipation * 0.12;
        if (distToBall < 3.0) {
            chance += 0.10;
        }
        if (ballSpeed > 0.45) {
            chance += 0.08;
        }
        if (countOpponentsNear(game, bot.getTeam(), ballLoc, 3.2) > 0) {
            chance += 0.08;
        }
        chance = Math.max(0.12, Math.min(0.52, chance));
        return aiRoll(bot, matchTick, 0x51A1DE) < chance;
    }

    // ==================================================================
    // Off-ball: team shape + support play
    // ==================================================================

    private static void handleOffBall(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                      Location oppGoal, TeamSide team, TeamSide possession, UUID chaserId) {
        boolean attacking = possession != team.opposite();
        PitchFrame pf = pitchFrame(game, ownGoal, oppGoal);
        int rank = supportRank(game, bot, ballLoc, chaserId);

        Location anchor;
        if (attacking && rank >= 0 && rank < 2) {
            // The closest spare players "group" near the carrier to receive.
            anchor = supportPoint(game, bot, ballLoc, ownGoal, oppGoal, pf);
        } else if (!attacking && rank == 0) {
            // The next-nearest defender covers the space behind the presser.
            anchor = coverPoint(game, ballLoc, ownGoal, pf);
        } else {
            anchor = formationAnchor(game, bot, ballLoc, ownGoal, oppGoal, pf, attacking);
        }

        anchor = spreadFromTeammates(game, bot, anchor);
        anchor = clampToField(game.getArena(), anchor);

        double distToAnchor = horizontalDistance(bot.getLocation(), anchor);
        double runUrgency = distToAnchor > 12.0 ? 0.9 : 0.45 + bot.getAttributes().workRate * 0.25;
        bot.moveToward(anchor, sprintDecision(bot, distToAnchor, 4.0, runUrgency), game.getSettings());
        bot.faceLocation(ballLoc);
    }

    /**
     * Rank of this bot among its spare (non-chaser, non-keeper) outfield teammates
     * by distance to the ball — 0 is closest. Used to decide who breaks shape to
     * support or cover. Returns -1 for the keeper / current chaser.
     */
    private static int supportRank(Game game, MatchBot bot, Location ballLoc, UUID chaserId) {
        if (isGoalkeeper(bot) || bot.getUuid().equals(chaserId) || bot.getLocation() == null) {
            return -1;
        }
        double myDist = horizontalDistance(bot.getLocation(), ballLoc);
        int rank = 0;
        for (MatchBot mate : game.getBots()) {
            if (mate.getUuid().equals(bot.getUuid()) || mate.getTeam() != bot.getTeam()
                    || isGoalkeeper(mate) || mate.getUuid().equals(chaserId) || mate.getLocation() == null) {
                continue;
            }
            double d = horizontalDistance(mate.getLocation(), ballLoc);
            if (d < myDist || (d == myDist && mate.getUuid().compareTo(bot.getUuid()) < 0)) {
                rank++;
            }
        }
        return rank;
    }

    /**
     * Choose a spot near the ball that offers a clean, forward passing lane — the
     * heart of "grouping to accept the ball". Candidate angles around the carrier
     * are scored for openness, a clear lane and forward intent.
     */
    private static Location supportPoint(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                         Location oppGoal, PitchFrame pf) {
        Vector toOpp = flatDirection(ballLoc, oppGoal);
        if (toOpp.lengthSquared() < 1e-6) {
            toOpp = pf.forward().clone();
        }
        toOpp.normalize();
        Vector lateral = new Vector(-toOpp.getZ(), 0, toOpp.getX());

        // Forward-biased fan of angles (radians), measured from straight ahead.
        double[] angles = {-1.05, -0.55, 0.0, 0.55, 1.05, -1.7, 1.7};
        double[] radii = {SUPPORT_MIN + 1.0, (SUPPORT_MIN + SUPPORT_MAX) * 0.5, SUPPORT_MAX};

        Location best = null;
        double bestScore = -Double.MAX_VALUE;
        for (double angle : angles) {
            Vector dir = toOpp.clone().multiply(Math.cos(angle))
                    .add(lateral.clone().multiply(Math.sin(angle)));
            if (dir.lengthSquared() < 1e-6) {
                continue;
            }
            dir.normalize();
            for (double r : radii) {
                Location spot = ballLoc.clone().add(dir.clone().multiply(r));
                spot.setY(ballLoc.getY());
                if (!insideFieldInset(game.getArena().getField(), spot, PATH_INSET)) {
                    continue;
                }
                if (!hasPassLane(game, bot.getTeam(), ballLoc, spot)) {
                    continue;
                }
                double openness = 1.0 / (1.0 + countOpponentsNear(game, bot.getTeam(), spot, 4.0));
                double forwardness = dir.dot(toOpp);            // 1 = straight forward
                double goalGain = (horizontalDistance(ballLoc, oppGoal)
                        - horizontalDistance(spot, oppGoal)) / 12.0;
                double score = openness * 1.6 + forwardness * 0.9 + goalGain * 0.8;
                if (score > bestScore) {
                    bestScore = score;
                    best = spot;
                }
            }
        }
        if (best != null) {
            return best;
        }
        return formationAnchor(game, bot, ballLoc, ownGoal, oppGoal, pf, true);
    }

    /** Sit goal-side of the ball to screen the goal as the second defender. */
    private static Location coverPoint(Game game, Location ballLoc, Location ownGoal, PitchFrame pf) {
        Vector toOwn = flatDirection(ballLoc, ownGoal);
        if (toOwn.lengthSquared() < 1e-6) {
            toOwn = pf.forward().clone().multiply(-1);
        }
        toOwn.normalize();
        Location cover = ballLoc.clone().add(toOwn.multiply(5.5));
        cover.setY(ballLoc.getY());
        return clampToField(game.getArena(), cover);
    }

    /**
     * Formation slot for an off-ball player. Each role has a home depth and a lane
     * across the pitch; the whole block slides up/down with the ball and shuffles
     * toward the ball side, so the team keeps a believable shape.
     */
    private static Location formationAnchor(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                            Location oppGoal, PitchFrame pf, boolean attacking) {
        int slot = outfieldSlotIndex(bot, game, bot.getTeam());
        int count = outfieldSlotCount(game, bot.getTeam());

        double progress = progressAlongPitch(ballLoc, ownGoal, oppGoal); // 0 own .. 1 opp
        double ballDepth = progress * 2.0 - 1.0;                          // -1 .. 1
        Vector rel = ballLoc.toVector().subtract(pf.center().toVector());
        rel.setY(0);
        double ballSide = clamp(rel.dot(pf.lateral()) / pf.halfWidth(), -1.0, 1.0);

        double roleDepth = roleBaseDepth(bot.getRole());
        double phase = attacking ? 0.14 : -0.22;
        double depthFrac = clamp(roleDepth + ballDepth * 0.45 + phase, -0.95, 0.92);

        double laneBase = count > 1 ? ((slot + 0.5) / count) * 2.0 - 1.0 : 0.0;
        laneBase *= roleWidth(bot.getRole());
        double laneFrac = clamp(laneBase * 0.9 + ballSide * 0.3, -0.98, 0.98);

        Location anchor = pf.center().clone()
                .add(pf.forward().clone().multiply(depthFrac * pf.halfLength() * 0.9))
                .add(pf.lateral().clone().multiply(laneFrac * pf.halfWidth() * 0.85));
        anchor.setY(ballLoc.getY());
        return clampToField(game.getArena(), anchor);
    }

    /** Home depth along the pitch: -1 = own goal, +1 = opponent goal. */
    private static double roleBaseDepth(BotRole role) {
        return switch (role) {
            case STRIKER -> 0.5;
            case WINGER -> 0.22;
            case PLAYMAKER -> -0.02;
            case MIDFIELDER -> -0.05;
            case DEFENDER -> -0.55;
            default -> 0.0;
        };
    }

    /** How wide a role naturally sits (scales the lane spread). */
    private static double roleWidth(BotRole role) {
        return switch (role) {
            case WINGER -> 1.15;
            case DEFENDER -> 0.95;
            case STRIKER -> 0.78;
            case MIDFIELDER -> 0.8;
            case PLAYMAKER -> 0.7;
            default -> 0.85;
        };
    }

    private static int outfieldSlotCount(Game game, TeamSide team) {
        int count = 0;
        for (MatchBot bot : game.getBots()) {
            if (bot.getTeam() == team && !isGoalkeeper(bot)) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static int outfieldSlotIndex(MatchBot bot, Game game, TeamSide team) {
        List<Integer> order = new ArrayList<>();
        for (MatchBot mate : game.getBots()) {
            if (mate.getTeam() == team && !isGoalkeeper(mate)) {
                order.add(mate.getSquadIndex());
            }
        }
        order.sort(Integer::compareTo);
        int idx = order.indexOf(bot.getSquadIndex());
        return Math.max(0, idx);
    }

    private static Location spreadFromTeammates(Game game, MatchBot bot, Location anchor) {
        if (anchor == null) {
            return null;
        }
        Vector push = new Vector(0, 0, 0);
        for (MatchBot mate : game.getBots()) {
            if (mate == bot || mate.getTeam() != bot.getTeam() || mate.getLocation() == null) {
                continue;
            }
            push.add(separationPush(anchor, mate.getLocation()));
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != bot.getTeam() || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            push.add(separationPush(anchor, player.getLocation()));
        }
        if (push.lengthSquared() < 1e-6) {
            return anchor;
        }
        Location spread = anchor.clone().add(push);
        spread.setY(anchor.getY());
        return clampToField(game.getArena(), spread);
    }

    /** Push anchor away from a teammate so bots stay 2–4 blocks apart. */
    private static Vector separationPush(Location anchor, Location mateLoc) {
        double dist = horizontalDistance(anchor, mateLoc);
        if (dist >= TEAMMATE_SEP_TARGET || dist <= 0.05) {
            return new Vector(0, 0, 0);
        }
        Vector away = anchor.toVector().subtract(mateLoc.toVector());
        away.setY(0);
        if (away.lengthSquared() < 1e-6) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        double strength = (TEAMMATE_SEP_TARGET - dist) * 0.65;
        if (dist < TEAMMATE_SEP_MIN) {
            strength += (TEAMMATE_SEP_MIN - dist) * 0.45;
        }
        return away.multiply(strength);
    }

    // ==================================================================
    // Shared helpers
    // ==================================================================

    /**
     * Returns the static location plus, when the actor is moving forward into
     * space, a point led ahead of them so a pass meets their run.
     */
    private static List<Location> leadCandidates(Location loc, Vector velocity) {
        List<Location> out = new ArrayList<>(2);
        if (loc == null) {
            return out;
        }
        out.add(loc);
        if (velocity == null) {
            return out;
        }
        Vector flat = velocity.clone();
        flat.setY(0);
        double speed = flat.length();
        if (speed > 0.12) {
            double leadTicks = Math.min(9.0, 3.0 + speed * 12.0);
            Vector lead = flat.normalize().multiply(Math.min(6.0, speed * leadTicks));
            Location ahead = loc.clone().add(lead);
            ahead.setY(loc.getY());
            out.add(ahead);
        }
        return out;
    }

    private static Location interceptPoint(Ball ball, Location ballLoc, Location self, boolean chaser,
                                           double anticipation) {
        if (ball == null || ballLoc == null || self == null) {
            return ballLoc;
        }
        Vector ballVel = ball.getVelocity();
        ballVel.setY(0);
        double speed = ballVel.length();
        if (speed < 0.08 || !chaser) {
            return ballLoc;
        }
        ballVel.normalize();
        double dist = horizontalDistance(self, ballLoc);
        double leadGain = 5.0 + anticipation * 4.0;
        double maxLead = 4.0 + anticipation * 3.0;
        double lead = Math.min(maxLead, Math.max(1.2, dist * 0.18) + speed * leadGain);
        Location intercept = ballLoc.clone().add(ballVel.multiply(lead));
        intercept.setY(ballLoc.getY());
        return intercept;
    }

    private static Location jockeyPoint(Location self, Location ballLoc, Location oppGoal) {
        if (self == null || ballLoc == null) {
            return ballLoc;
        }
        Vector toBall = flatDirection(self, ballLoc);
        if (toBall.lengthSquared() < 1e-6) {
            return ballLoc;
        }
        toBall.normalize();
        Vector goalDir = flatDirection(ballLoc, oppGoal);
        if (goalDir.lengthSquared() > 1e-6) {
            goalDir.normalize();
            Vector cutOff = goalDir.clone().multiply(0.55).add(toBall.clone().multiply(0.45)).normalize();
            Location spot = ballLoc.clone().subtract(cutOff.clone().multiply(1.8));
            spot.setY(ballLoc.getY());
            return spot;
        }
        return ballLoc.clone().add(toBall.clone().multiply(-1.2));
    }

    private static Location safeClearanceTarget(Game game, Location ballLoc, Location oppGoal,
                                                Location ownGoal) {
        if (ballLoc == null) {
            return oppGoal;
        }
        Vector toGoal = flatDirection(ballLoc, oppGoal);
        if (toGoal.lengthSquared() < 1e-6) {
            toGoal = new Vector(0, 0, 1);
        }
        toGoal.normalize();
        Location target = ballLoc.clone().add(toGoal.multiply(8.0));
        target.setY(ballLoc.getY());
        target = clampToField(game.getArena(), target);
        if (isSafeBallPath(game, ballLoc, target, PATH_INSET)
                && !kickThreatensOwnGoal(ballLoc, target, ownGoal)) {
            return target;
        }

        Cuboid field = game.getArena().getField();
        if (field != null) {
            Location center = field.getCenter();
            center.setY(ballLoc.getY());
            Vector inward = flatDirection(ballLoc, center);
            if (inward.lengthSquared() > 1e-6) {
                Location central = ballLoc.clone().add(inward.normalize().multiply(7.0));
                central.setY(ballLoc.getY());
                central = clampToField(game.getArena(), central);
                if (!kickThreatensOwnGoal(ballLoc, central, ownGoal)) {
                    return central;
                }
            }
        }
        return oppGoal;
    }

    private static Vector keepDirectionInPlay(Game game, Location from, Vector direction) {
        if (from == null || direction == null || direction.lengthSquared() < 1e-6) {
            return direction;
        }
        Vector candidate = direction.clone();
        candidate.setY(0);
        if (candidate.lengthSquared() < 1e-6) {
            return direction;
        }
        candidate.normalize();
        Location probe = from.clone().add(candidate.clone().multiply(4.0));
        probe.setY(from.getY());
        if (isSafeBallPath(game, from, probe, PATH_INSET)) {
            return candidate;
        }

        Cuboid field = game.getArena().getField();
        if (field == null) {
            return candidate;
        }
        Vector inward = flatDirection(from, field.getCenter());
        if (inward.lengthSquared() < 1e-6) {
            return candidate;
        }
        return candidate.multiply(0.35).add(inward.normalize().multiply(0.65)).normalize();
    }

    private static boolean isSafeBallPath(Game game, Location from, Location to, double inset) {
        if (game == null || from == null || to == null) {
            return false;
        }
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        Cuboid field = game.getArena().getField();
        if (field != null) {
            if (!insideFieldInset(field, to, inset)) {
                return false;
            }
            Vector path = flatDirection(from, to);
            double len = path.length();
            if (len > 1.0) {
                path.normalize();
                for (double d = 1.5; d < len; d += 1.5) {
                    Location probe = from.clone().add(path.clone().multiply(d));
                    if (!insideFieldInset(field, probe, inset)) {
                        return false;
                    }
                }
            }
        }
        return game.getArena().detectOutLineCrossing(from, to) == null;
    }

    private static boolean insideFieldInset(Cuboid field, Location loc, double inset) {
        if (field == null || loc == null || loc.getWorld() == null || !loc.getWorld().equals(field.getWorld())) {
            return false;
        }
        return loc.getX() >= field.getMinX() + inset
                && loc.getX() <= field.getMaxX() - inset
                && loc.getZ() >= field.getMinZ() + inset
                && loc.getZ() <= field.getMaxZ() - inset;
    }

    /** True when a kick from {@code from} toward {@code target} aims at the opponent's goal. */
    private static boolean aimsAtOpponentGoal(Location from, Location target, Location ownGoal,
                                              Location oppGoal) {
        if (from == null || target == null || ownGoal == null || oppGoal == null) {
            return false;
        }
        Vector kickDir = flatDirection(from, target);
        Vector toOpp = flatDirection(from, oppGoal);
        if (kickDir.lengthSquared() < 1e-6 || toOpp.lengthSquared() < 1e-6) {
            return false;
        }
        kickDir.normalize();
        toOpp.normalize();
        if (kickDir.dot(toOpp) < 0.3) {
            return false;
        }
        return horizontalDistance(target, oppGoal) + 1.5 < horizontalDistance(target, ownGoal);
    }

    /** True when the bot is not facing/kicking toward their own net. */
    private static boolean avoidsOwnGoal(Location from, Location target, Location ownGoal) {
        return !kickThreatensOwnGoal(from, target, ownGoal);
    }

    /** Rejects kicks that would send the ball toward the bot's own goal mouth. */
    private static boolean kickThreatensOwnGoal(Location from, Location target, Location ownGoal) {
        if (from == null || target == null || ownGoal == null) {
            return false;
        }
        Vector kickDir = flatDirection(from, target);
        Vector toOwn = flatDirection(from, ownGoal);
        if (kickDir.lengthSquared() < 1e-6 || toOwn.lengthSquared() < 1e-6) {
            return false;
        }
        kickDir.normalize();
        toOwn.normalize();
        double align = kickDir.dot(toOwn);
        if (align <= 0.58) {
            return false;
        }
        double ownDist = horizontalDistance(target, ownGoal);
        double kickLen = horizontalDistance(from, target);
        // Allow short square/back passes; block direct own-net clearances.
        return align > 0.72 || (align > 0.58 && ownDist < 9.0 && kickLen > 6.0);
    }

    private static boolean hasPassLane(Game game, TeamSide team, Location from, Location to) {
        Vector dir = flatDirection(from, to);
        double len = dir.length();
        if (len < 1.0) {
            return true;
        }
        dir.normalize();
        double laneRadius = 2.15;
        for (double d = 1.4; d < len - 0.8; d += 1.1) {
            Location probe = from.clone().add(dir.clone().multiply(d));
            if (countOpponentsNear(game, team, probe, laneRadius) > 0) {
                return false;
            }
        }
        return true;
    }

    private static int countOpponentsNear(Game game, TeamSide team, Location loc, double radius) {
        int count = 0;
        TeamSide opp = team.opposite();
        for (MatchBot bot : game.getBots()) {
            if (bot.getTeam() != opp) {
                continue;
            }
            Location at = bot.getLocation();
            if (at != null && horizontalDistance(at, loc) <= radius) {
                count++;
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != opp || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()
                    && horizontalDistance(player.getLocation(), loc) <= radius) {
                count++;
            }
        }
        return count;
    }

    private static Location nearestOpponentLocation(Game game, TeamSide team, Location from) {
        Location nearest = null;
        double best = Double.MAX_VALUE;
        TeamSide opp = team.opposite();
        for (MatchBot bot : game.getBots()) {
            if (bot.getTeam() != opp) {
                continue;
            }
            Location at = bot.getLocation();
            if (at == null) {
                continue;
            }
            double dist = horizontalDistance(from, at);
            if (dist < best) {
                best = dist;
                nearest = at;
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != opp || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            double dist = horizontalDistance(from, player.getLocation());
            if (dist < best) {
                best = dist;
                nearest = player.getLocation();
            }
        }
        return nearest;
    }

    private static double nearestOpponentDistance(Game game, TeamSide team, Location from) {
        Location nearest = nearestOpponentLocation(game, team, from);
        return nearest == null ? Double.MAX_VALUE : horizontalDistance(from, nearest);
    }

    private static double angleToGoal(Location from, Location goal) {
        Vector toGoal = flatDirection(from, goal);
        if (toGoal.lengthSquared() < 1e-6) {
            return 180.0;
        }
        toGoal.normalize();
        Vector facing = from.getDirection();
        facing.setY(0);
        if (facing.lengthSquared() < 1e-6) {
            return 0.0;
        }
        facing.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, facing.dot(toGoal)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static double progressAlongPitch(Location point, Location ownGoal, Location oppGoal) {
        Vector total = flatDirection(ownGoal, oppGoal);
        double lenSq = total.lengthSquared();
        if (lenSq < 1e-6) {
            return 0.5;
        }
        Vector rel = point.toVector().subtract(ownGoal.toVector());
        rel.setY(0);
        return Math.max(0.0, Math.min(1.0, rel.dot(total) / lenSq));
    }

    private static PitchFrame pitchFrame(Game game, Location ownGoal, Location oppGoal) {
        Vector forward = flatDirection(ownGoal, oppGoal);
        if (forward.lengthSquared() < 1e-6) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        Vector lateral = new Vector(-forward.getZ(), 0, forward.getX());
        Cuboid field = game.getArena().getField();
        Location center;
        double halfLength;
        double halfWidth;
        if (field != null) {
            center = field.getCenter();
            double sx = field.getSizeX();
            double sz = field.getSizeZ();
            halfLength = 0.5 * (Math.abs(forward.getX()) * sx + Math.abs(forward.getZ()) * sz);
            halfWidth = 0.5 * (Math.abs(lateral.getX()) * sx + Math.abs(lateral.getZ()) * sz);
        } else {
            center = ownGoal.clone().add(oppGoal.clone().subtract(ownGoal).multiply(0.5));
            halfLength = horizontalDistance(ownGoal, oppGoal) * 0.5;
            halfWidth = halfLength * 0.6;
        }
        if (ownGoal != null) {
            center.setY(ownGoal.getY());
        }
        return new PitchFrame(forward, lateral, center, Math.max(6.0, halfLength), Math.max(4.0, halfWidth));
    }

    private static Location goalCenter(Arena arena, TeamSide defendingTeam) {
        Cuboid goal = arena.getGoal(defendingTeam);
        if (goal != null) {
            return goal.getCenter();
        }
        return arena.getBallSpawn();
    }

    private static Location clampToField(Arena arena, Location loc) {
        Cuboid field = arena.getField();
        if (field == null || loc == null) {
            return loc;
        }
        double inset = 2.0;
        loc.setX(Math.max(field.getMinX() + inset, Math.min(field.getMaxX() - inset, loc.getX())));
        loc.setZ(Math.max(field.getMinZ() + inset, Math.min(field.getMaxZ() - inset, loc.getZ())));
        return loc;
    }

    private static Vector flatDirection(Location from, Location to) {
        if (from == null || to == null) {
            return new Vector();
        }
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        return v;
    }

    private static double horizontalDistance(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private record KeeperRead(Location target, boolean alert) {
    }

    private record PassOption(Location target, double score, double charge,
                              boolean strongKick, double lift, boolean receiverHuman) {
    }

    /**
     * A simple pitch coordinate frame: forward points from the team's own goal to
     * the opponent's; lateral is perpendicular. Half-length/width are the pitch
     * half-extents along those axes, so formation maths scales to any arena.
     */
    private record PitchFrame(Vector forward, Vector lateral, Location center,
                              double halfLength, double halfWidth) {
    }
}
