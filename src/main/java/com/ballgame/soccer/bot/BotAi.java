package com.ballgame.soccer.bot;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.event.BallKickEvent;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.GameState;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.mechanics.OffsideChecker;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
    /** Outfield bots stay at least this far in front of their goal line. */
    private static final double KEEPER_SEPARATION_RADIUS = 7.5;
    /** Field inset when checking kicks/passes so bots stay away from touchlines. */
    private static final double PATH_INSET = 2.0;
    /** Only switch chaser when a teammate is this many blocks closer (reduces yo-yo). */
    private static final double CHASER_SWITCH_MARGIN = 4.0;
    /** How far in front of the goal line the keeper may advance (blocks). */
    private static final double KEEPER_MAX_ADVANCE = 3.0;
    /** Loose balls within this depth from the goal line may trigger a short rush. */
    private static final double KEEPER_CLAIM_DEPTH = 2.8;
    /** Horizontal distance at which a nearby ball triggers a sweep. */
    private static final double KEEPER_NEARBY_RADIUS = 5.0;

    private enum PassStyle { GROUND, STRONG, LOFT, CROSS }
    /** Ticks ahead to predict the ball for reflex saves. */
    private static final int KEEPER_PREDICT_TICKS = 5;
    /** Modest reach boost on incoming shots. */
    private static final double KEEPER_ALERT_REACH_BONUS = 1.08;
    /** Base save rate for incoming shots (Medium). */
    private static final double KEEPER_SAVE_RATE_MEDIUM = 0.68;
    /** With close control the ball is "at his feet" inside this distance. */
    private static final double CONTROL_REACH = 1.6;
    /** A jockeying defender holds this far goal-side of the ball. */
    private static final double JOCKEY_DISTANCE = 1.75;
    /** Markers stand this far goal-side of their man (clear of the holding call). */
    private static final double MARK_DISTANCE = 1.9;

    /** Per-team ball chaser resolution result. */
    public record BallChaserState(UUID botId, boolean humanChasing) {
    }

    private BotAi() {
    }

    static void think(Game game, MatchBot bot, long matchTick) {
        GameState state = game.getState();
        if (state == GameState.KICKOFF) {
            holdKickoffShape(game, bot, matchTick);
            return;
        }
        if (!state.isLive()) {
            return;
        }
        SoccerSettings settings = game.getSettings();
        if (game.isCelebrating()) {
            if (settings.botCelebrations) {
                celebrate(game, bot);
            } else {
                bot.holdPosition();
            }
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
        bot.setHeadPitchBias(0f);
        if (bot.isDown()) {
            bot.lookAt(ballLoc);
            return;
        }
        if (bot.isDiving()) {
            if (isGoalkeeper(bot)) {
                attemptGoalkeeperCatch(game, bot, ball, ballLoc, true, matchTick);
            }
            return;
        }

        TeamSide team = bot.getTeam();
        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());

        if (game.isBallHeldByKeeper()) {
            bot.setCrouched(false);
            handleKeeperHoldsBall(game, bot, ballLoc, ownGoal, oppGoal, matchTick);
            return;
        }
        if (game.isKickoffPending() && handleKickoffRestart(game, bot, ballLoc, matchTick)) {
            return;
        }

        // Players react to what they saw a moment ago, not to where the ball is right now.
        Location seenBall = ballLoc;
        Vector seenVelocity = ball.getVelocity();
        if (settings.botReactionDelay) {
            int delay = bot.perceptionDelayTicks();
            seenBall = game.perceivedBallLocation(delay);
            seenVelocity = game.perceivedBallVelocity(delay);
        }
        double distToBall = horizontalDistance(self, ballLoc);
        TeamSide possession = ball.getLastTouchTeam();

        bot.lookAt(seenBall);
        scanPitch(game, bot, matchTick, possession == team ? 1.0 : 0.6);
        if (distToBall < 14.0) {
            bot.setTouchDirection(dribbleDirection(self, oppGoal, ownGoal, game, team));
        }

        // Goalkeeper tracks the ball every tick — saves must not wait on action cooldown.
        if (isGoalkeeper(bot)) {
            handleGoalkeeper(game, bot, ball, ballLoc, ownGoal, oppGoal, matchTick);
            return;
        }

        boolean canKick = bot.canAct();
        UUID chaserId = game.getBallChaserBotId(team);
        boolean humanChases = game.isHumanChasingBall(team);
        boolean isChaser = !humanChases && bot.getUuid().equals(chaserId);

        if (!isChaser) {
            bot.squareUpTo(null, matchTick, 0);
            bot.tickJockey(false);
            handleOffBall(game, bot, seenBall, ownGoal, oppGoal, team, possession, chaserId, matchTick);
            return;
        }
        if (bot.isStaggered(matchTick)) {
            // Lost the duel: off balance for a couple of heavy steps.
            bot.squareUpTo(null, matchTick, 0);
            bot.moveToward(seenBall, false, 0.5);
            return;
        }

        boolean opponentsOnBall = possession != null && possession != team;
        double engage = opponentsOnBall && settings.botTackleDuels ? 6.5 : settings.kickReach * 0.9;
        if (distToBall > engage) {
            bot.squareUpTo(null, matchTick, 0);
            bot.tickJockey(false);
            Location chaseTarget = interceptPoint(seenBall, seenVelocity, self, true,
                    bot.getAttributes().anticipation);
            double urgency = 0.85 + 0.15 * dangerLevel(game, ballLoc, ownGoal, oppGoal);
            bot.moveToward(chaseTarget, sprintDecision(bot, distToBall, 2.5, urgency), settings);
            return;
        }
        if (!opponentsOnBall) {
            bot.squareUpTo(null, matchTick, 0);
            bot.tickJockey(false);
            handleAttack(game, bot, ball, ballLoc, oppGoal, ownGoal, distToBall, matchTick, canKick);
        } else {
            handlePress(game, bot, ball, ballLoc, oppGoal, distToBall, matchTick, canKick);
        }
    }

    // ==================================================================
    // Restarts, stoppages and celebrations
    // ==================================================================

    /** Before the whistle: on the kickoff spot, eyes on the ball, the odd look around. */
    private static void holdKickoffShape(Game game, MatchBot bot, long matchTick) {
        Location self = bot.getLocation();
        if (self == null) {
            return;
        }
        Location ballLoc = game.getBallLocation();
        Location spot = game.kickoffSpotFor(bot);
        if (spot != null && horizontalDistance(self, spot) > 0.45) {
            bot.moveToward(spot, false, 0.55);
        } else {
            bot.holdPosition();
            if (ballLoc != null) {
                bot.faceLocation(ballLoc);
            }
        }
        if (ballLoc != null) {
            bot.lookAt(ballLoc);
        }
        scanPitch(game, bot, matchTick, 0.5);
    }

    /**
     * Whistle has gone but nobody has touched it: the taker rolls it to a
     * team-mate, everybody else stays in position until the ball is in play.
     */
    private static boolean handleKickoffRestart(Game game, MatchBot bot, Location ballLoc, long matchTick) {
        UUID takerId = game.getKickoffTakerId();
        Location self = bot.getLocation();
        if (takerId == null || self == null) {
            return false;
        }
        bot.lookAt(ballLoc);
        if (takerId.equals(bot.getUuid())) {
            int waited = 100 - game.getKickoffPendingTicks();
            int pause = 10 + (int) (aiRoll(bot, 0L, 0x41C0) * 10.0);
            Location target = kickoffPassTarget(game, bot, ballLoc);
            if (target == null) {
                return false;
            }
            if (waited < pause) {
                bot.holdPosition();
                bot.faceLocation(ballLoc);
                if (waited == pause / 2) {
                    bot.glance(target, 6, matchTick);
                }
                return true;
            }
            if (horizontalDistance(self, ballLoc) > 1.3) {
                bot.moveToward(ballLoc, false, 0.6);
                return true;
            }
            bot.faceLocation(target);
            game.botPassAt(bot, target, clamp(horizontalDistance(ballLoc, target) / 21.0, 0.26, 0.62));
            bot.setActionCooldown(6);
            return true;
        }
        Location spot = game.kickoffSpotFor(bot);
        if (spot != null && horizontalDistance(self, spot) > 0.6) {
            bot.moveToward(spot, false, 0.5);
        } else {
            bot.holdPosition();
            bot.faceLocation(ballLoc);
        }
        return true;
    }

    /** Short pass back or square to the nearest team-mate in the kicking side's half. */
    private static Location kickoffPassTarget(Game game, MatchBot taker, Location ballLoc) {
        Location ownGoal = goalCenter(game.getArena(), taker.getTeam());
        Location oppGoal = goalCenter(game.getArena(), taker.getTeam().opposite());
        Vector forward = flatDirection(ownGoal, oppGoal);
        if (forward.lengthSquared() < 1e-6) {
            return null;
        }
        forward.normalize();
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (Location mate : teammateLocations(game, taker, false)) {
            Vector rel = flatDirection(ballLoc, mate);
            double dist = rel.length();
            if (dist < 3.5 || dist > 20.0 || rel.dot(forward) > 0.5) {
                continue;
            }
            if (dist < bestScore) {
                bestScore = dist;
                best = mate;
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            Player player = sp.getBukkitPlayer();
            if (sp.isBot() || sp.getTeam() != taker.getTeam() || player == null || !player.isOnline()) {
                continue;
            }
            Vector rel = flatDirection(ballLoc, player.getLocation());
            double dist = rel.length();
            if (dist < 3.0 || dist > 22.0 || rel.dot(forward) > 0.5) {
                continue;
            }
            // Humans want the ball: favour them over a slightly closer bot.
            if (dist - 6.0 < bestScore) {
                bestScore = dist - 6.0;
                best = player.getLocation();
            }
        }
        return best != null ? best.clone() : null;
    }

    /** Positions of outfield team-mate bots (optionally including keepers), as copies. */
    private static List<Location> teammateLocations(Game game, MatchBot bot, boolean includeKeeper) {
        List<Location> out = new ArrayList<>();
        for (MatchBot mate : game.getBots()) {
            if (mate == bot || mate.getTeam() != bot.getTeam() || mate.getLocation() == null
                    || (!includeKeeper && isGoalkeeper(mate))) {
                continue;
            }
            out.add(mate.getLocation().clone());
        }
        return out;
    }

    /** Every opponent on the pitch (bots and humans), as copies. */
    private static List<Location> opponentLocations(Game game, TeamSide team) {
        List<Location> out = new ArrayList<>();
        TeamSide opp = team.opposite();
        for (MatchBot bot : game.getBots()) {
            if (bot.getTeam() == opp && bot.getLocation() != null) {
                out.add(bot.getLocation().clone());
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.isBot() || sp.getTeam() != opp) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                out.add(player.getLocation());
            }
        }
        return out;
    }

    /**
     * Shoulder checks: every second or two a player takes his eyes off the ball
     * for a moment to see who is around him.
     */
    private static void scanPitch(Game game, MatchBot bot, long matchTick, double rate) {
        if (bot.isGlancing(matchTick) || matchTick < bot.nextScanTick()) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int interval = (int) Math.round((22 + rnd.nextInt(30)) / Math.max(0.25, rate));
        bot.scheduleNextScan(matchTick + interval);
        Location self = bot.getLocation();
        List<Location> options = new ArrayList<>();
        for (MatchBot other : game.getBots()) {
            Location at = other.getLocation();
            if (other != bot && at != null) {
                double d = horizontalDistance(self, at);
                if (d > 3.0 && d < 30.0) {
                    options.add(other.getEyeLocation());
                }
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            Player player = sp.getBukkitPlayer();
            if (!sp.isBot() && player != null && player.isOnline()) {
                double d = horizontalDistance(self, player.getLocation());
                if (d > 3.0 && d < 30.0) {
                    options.add(player.getEyeLocation());
                }
            }
        }
        if (!options.isEmpty()) {
            bot.glance(options.get(rnd.nextInt(options.size())), 5 + rnd.nextInt(6), matchTick);
        }
    }

    /**
     * After a goal: the scorer wheels away to the corner flag, team-mates chase
     * him down, the side that conceded trudges back with heads down.
     */
    private static void celebrate(Game game, MatchBot bot) {
        Location self = bot.getLocation();
        TeamSide scoring = game.getCelebrationTeam();
        if (self == null || scoring == null) {
            bot.holdPosition();
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        bot.squareUpTo(null, 0L, 0);
        if (bot.getTeam() != scoring) {
            bot.setHeadPitchBias(isGoalkeeper(bot) ? 34f : 24f);
            Location home = isGoalkeeper(bot) ? keeperSpawnSpot(game, bot.getTeam()) : game.kickoffSpotFor(bot);
            if (home != null && horizontalDistance(self, home) > 1.2) {
                bot.setCrouched(false);
                bot.moveToward(home, false, 0.5);
                bot.lookAt(home);
            } else {
                bot.holdPosition();
                bot.setCrouched(isGoalkeeper(bot));
            }
            return;
        }
        bot.setHeadPitchBias(-8f);
        if (isGoalkeeper(bot)) {
            bot.holdPosition();
            if (rnd.nextDouble() < 0.05) {
                bot.swingArm();
            }
            if (rnd.nextDouble() < 0.025) {
                bot.hop();
            }
            return;
        }
        UUID scorerId = game.getCelebrationScorerId();
        if (bot.getUuid().equals(scorerId)) {
            Location corner = celebrationCorner(game, bot);
            if (horizontalDistance(self, corner) > 1.8) {
                bot.moveToward(corner, bot.canSprint(), 1.0);
                bot.lookAt(corner.clone().add(0, 3.0, 0));
                if (rnd.nextDouble() < 0.12) {
                    bot.swingArm();
                }
            } else {
                bot.holdPosition();
                Cuboid field = game.getArena().getField();
                if (field != null) {
                    bot.faceLocation(field.getCenter());
                }
                if (rnd.nextDouble() < 0.16) {
                    bot.swingArm();
                }
                if (rnd.nextDouble() < 0.07) {
                    bot.hop();
                }
            }
            return;
        }
        Location scorerAt = scorerId != null ? game.participantLocation(scorerId) : null;
        if (scorerAt != null && scorerAt.getWorld() == self.getWorld()) {
            Location scorer = scorerAt.clone();
            double angle = bot.getSquadIndex() * 2.399963;
            Location around = scorer.clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            double dist = horizontalDistance(self, around);
            if (dist > 1.0) {
                bot.moveToward(around, dist > 6.0 && bot.canSprint(), 1.0);
            } else {
                bot.holdPosition();
                bot.faceLocation(scorer);
                if (rnd.nextDouble() < 0.09) {
                    bot.hop();
                }
                if (rnd.nextDouble() < 0.1) {
                    bot.swingArm();
                }
            }
            bot.lookAt(scorer.add(0, 1.5, 0));
        } else {
            bot.holdPosition();
            if (rnd.nextDouble() < 0.06) {
                bot.swingArm();
            }
            if (rnd.nextDouble() < 0.03) {
                bot.hop();
            }
        }
    }

    /** Corner flag at the attacking end on the scorer's side of the pitch. */
    private static Location celebrationCorner(Game game, MatchBot bot) {
        Location ownGoal = goalCenter(game.getArena(), bot.getTeam());
        Location oppGoal = goalCenter(game.getArena(), bot.getTeam().opposite());
        PitchFrame pf = pitchFrame(game, ownGoal, oppGoal);
        Location self = bot.getLocation();
        Vector rel = self.toVector().subtract(pf.center().toVector());
        rel.setY(0);
        double side = rel.dot(pf.lateral()) >= 0.0 ? 1.0 : -1.0;
        Location corner = pf.center().clone()
                .add(pf.forward().clone().multiply(pf.halfLength() - 3.0))
                .add(pf.lateral().clone().multiply(side * (pf.halfWidth() - 2.5)));
        corner.setY(self.getY());
        return clampToField(game.getArena(), corner);
    }

    /** The keeper has it in his hands: team-mates spread to receive, opponents drop off. */
    private static void handleKeeperHoldsBall(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                              Location oppGoal, long matchTick) {
        UUID holderId = game.getKeeperHolderId();
        bot.squareUpTo(null, matchTick, 0);
        bot.tickJockey(false);
        bot.lookAt(ballLoc);
        if (bot.getUuid().equals(holderId)) {
            bot.holdPosition();
            Location target = keeperDistributionTarget(game, bot);
            if (target != null) {
                bot.faceLocation(target);
                bot.glance(target, 8, matchTick);
            }
            return;
        }
        MatchBot holder = game.getBot(holderId);
        boolean ourBall = holder != null && holder.getTeam() == bot.getTeam();
        Location self = bot.getLocation();
        if (isGoalkeeper(bot)) {
            Location post = keeperSpawnSpot(game, bot.getTeam());
            if (post != null && horizontalDistance(self, post) > 0.6) {
                bot.moveToward(post, false, 0.7);
            } else {
                bot.holdPosition();
            }
            return;
        }
        PitchFrame pf = pitchFrame(game, ownGoal, oppGoal);
        Location anchor = formationAnchor(game, bot, ballLoc, ownGoal, oppGoal, pf, ourBall);
        anchor = clampAwayFromOwnGoal(game, bot, anchor, ownGoal, oppGoal);
        if (!ourBall) {
            // Give the keeper his space while he has it in his hands.
            Vector away = flatDirection(ballLoc, anchor);
            double dist = away.length();
            if (dist < 10.0) {
                if (dist < 1e-3) {
                    away = pf.forward().clone().multiply(-1.0);
                    dist = 1.0;
                }
                anchor = ballLoc.clone().add(away.multiply(10.0 / dist));
            }
        }
        anchor = spreadFromTeammates(game, bot, anchor);
        anchor = clampToField(game.getArena(), anchor);
        double dist = horizontalDistance(self, anchor);
        bot.moveToward(anchor, sprintDecision(bot, dist, 8.0, 0.45), dist < 3.0 ? 0.7 : 1.0);
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
            if (bot.getRole() == BotRole.DEFENDER && ballProgress < 0.40) {
                roleWeight *= 0.06;
            } else if (bot.getRole() == BotRole.DEFENDER && ballProgress > 0.52) {
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

        boolean rush = shouldKeeperRush(game, bot, ball, ballLoc, ownGoal, oppGoal);
        boolean alert = read.alert() || rush;

        bot.faceLocation(ballLoc);
        bot.lookAt(ballLoc);
        // Shuffle across the goal square to the ball instead of turning to run.
        bot.squareUpTo(rush ? null : ballLoc, matchTick, 3);
        bot.setCrouched(!rush && keeperSetPosition(game, bot, ball, ballLoc, ownGoal));
        if (!rush && tryKeeperDive(game, bot, ball, ballLoc, ownGoal, oppGoal, matchTick)) {
            attemptGoalkeeperCatch(game, bot, ball, ballLoc, true, matchTick);
            return;
        }

        Location target = rush
                ? interceptPoint(ball, ballLoc, self, true, bot.getAttributes().anticipation)
                : read.target();
        target = clampKeeperTarget(game, bot.getTeam(), target, ownGoal, oppGoal);

        double distToTarget = horizontalDistance(self, target);
        if (rush) {
            bot.moveToward(target, sprintDecision(bot, distToTarget, 2.2, 0.55), game.getSettings());
        } else if (distToTarget > 0.5) {
            boolean sprint = alert && sprintDecision(bot, distToTarget, 2.8, 0.5);
            bot.moveToward(target, sprint, game.getSettings());
        } else {
            bot.holdPosition();
        }

        attemptGoalkeeperCatch(game, bot, ball, ballLoc, alert, matchTick);
    }

    /** An opponent is shaping to shoot within range: knees bent, weight on the toes. */
    private static boolean keeperSetPosition(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                             Location ownGoal) {
        TeamSide last = ball.getLastTouchTeam();
        if (last == null || last == bot.getTeam() || ball.getSpeed() > 0.5) {
            return false;
        }
        double dist = horizontalDistance(ballLoc, ownGoal);
        if (dist > 24.0 || dist < 3.0) {
            return false;
        }
        return nearestOpponentDistance(game, bot.getTeam(), ballLoc) < 1.8;
    }

    /**
     * A shot heading for the goal mouth but out of standing reach: the keeper
     * reads it (or gets wrong-footed) and throws himself across the goal.
     */
    private static boolean tryKeeperDive(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                         Location ownGoal, Location oppGoal, long matchTick) {
        if (ball == null || bot.isDiving() || bot.isDown()) {
            return false;
        }
        Vector vel = ball.getVelocity();
        vel.setY(0);
        if (vel.length() < 0.5) {
            return false;
        }
        Location self = bot.getLocation();
        Vector fieldDir = flatDirection(ownGoal, oppGoal);
        if (self == null || fieldDir.lengthSquared() < 1e-6) {
            return false;
        }
        fieldDir.normalize();
        Vector lateral = new Vector(-fieldDir.getZ(), 0, fieldDir.getX());
        Vector rel = ballLoc.toVector().subtract(self.toVector());
        rel.setY(0);
        double depth = rel.dot(fieldDir);
        double closing = -vel.dot(fieldDir);
        if (depth < 0.3 || closing < 0.3) {
            return false;
        }
        double ticks = depth / closing;
        if (ticks > 9.0) {
            return false;
        }
        double cross = rel.dot(lateral) + vel.dot(lateral) * ticks;
        double side = Math.abs(cross);
        if (side < 1.3 || side > 4.2) {
            return false;
        }
        Location line = goalLineSpot(game.getArena(), bot.getTeam());
        if (line != null) {
            Vector fromLine = ballLoc.toVector().subtract(line.toVector());
            fromLine.setY(0);
            double toLine = fromLine.dot(fieldDir) / closing;
            double sideAtLine = fromLine.dot(lateral) + vel.dot(lateral) * toLine;
            double halfWidth = goalHalfWidth(game.getArena().getGoal(bot.getTeam()), line, oppGoal);
            if (Math.abs(sideAtLine) > halfWidth + 0.6) {
                return false;
            }
        }
        double read = switch (game.getBotDifficulty(bot.getTeam())) {
            case EASY -> 0.55;
            case MEDIUM -> 0.8;
            case HARD -> 0.92;
        };
        if (aiRoll(bot, matchTick, 0xD17E) > read) {
            return false;
        }
        Location target = self.clone()
                .add(lateral.multiply(Math.signum(cross) * Math.min(3.0, side)))
                .add(fieldDir.multiply(0.25));
        bot.squareUpTo(null, matchTick, 0);
        bot.setCrouched(false);
        bot.diveToward(target);
        return true;
    }

    public static double keeperDepthFromGoalLine(Game game, TeamSide team, Location loc) {
        if (game == null || team == null || loc == null) {
            return 0.0;
        }
        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());
        return depthFromGoalLine(game, team, loc, ownGoal, oppGoal);
    }

    /**
     * The keeper leaves his line to claim the ball when it is loose in (or rolling
     * into) his box and he can reach it before any opponent — exactly when a real
     * keeper comes out to smother a through-ball or beat a striker to it.
     */
    private static boolean shouldKeeperRush(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                            Location ownGoal, Location oppGoal) {
        Location self = bot.getLocation();
        if (self == null || ballLoc == null) {
            return false;
        }
        double ballDepth = depthFromGoalLine(game, bot.getTeam(), ballLoc, ownGoal, oppGoal);
        if (ballDepth > KEEPER_CLAIM_DEPTH || ballDepth < -0.8) {
            return false;
        }
        double keeperToBall = horizontalDistance(self, ballLoc);
        if (keeperToBall > KEEPER_NEARBY_RADIUS) {
            return false;
        }
        double ballSpeed = ball != null ? ball.getSpeed() : 0.0;
        if (keeperToBall <= KEEPER_NEARBY_RADIUS && ballSpeed < 0.85) {
            return true;
        }
        double oppToBall = nearestOpponentDistance(game, bot.getTeam(), ballLoc);
        return keeperToBall <= oppToBall + 0.5 && ballDepth > 0.4;
    }

    /** Signed distance in front of the goal line (0 = on line, positive = into the pitch). */
    private static double depthFromGoalLine(Game game, TeamSide team, Location loc,
                                            Location ownGoal, Location oppGoal) {
        if (loc == null) {
            return Double.MAX_VALUE;
        }
        Location line = goalLineSpot(game.getArena(), team);
        if (line == null) {
            line = keeperPost(game, team, null, ownGoal, oppGoal);
        }
        if (line == null) {
            return horizontalDistance(loc, ownGoal);
        }
        Vector fieldDir = flatDirection(line, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            return 0.0;
        }
        fieldDir.normalize();
        Vector rel = loc.toVector().subtract(line.toVector());
        rel.setY(0);
        return rel.dot(fieldDir);
    }

    /** Keeps a human or bot keeper inside the box in front of the goal mouth. */
    public static Location clampKeeperPosition(Game game, TeamSide team, Location loc) {
        if (game == null || team == null || loc == null) {
            return loc;
        }
        Location ownGoal = goalCenter(game.getArena(), team);
        Location oppGoal = goalCenter(game.getArena(), team.opposite());
        return clampKeeperTarget(game, team, loc, ownGoal, oppGoal);
    }

    /** Hard cap: the keeper never leaves a 3-block-deep box in front of his goal mouth. */
    private static Location clampKeeperTarget(Game game, TeamSide team, Location target,
                                              Location ownGoal, Location oppGoal) {
        if (target == null) {
            return null;
        }
        Location line = goalLineSpot(game.getArena(), team);
        if (line == null) {
            line = keeperPost(game, team, null, ownGoal, oppGoal);
        }
        if (line == null) {
            return target;
        }

        Vector fieldDir = flatDirection(line, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            fieldDir = new Vector(0, 0, 1);
        }
        fieldDir.normalize();
        Vector lateral = new Vector(-fieldDir.getZ(), 0, fieldDir.getX());

        Vector rel = target.toVector().subtract(line.toVector());
        rel.setY(0);
        double depth = Math.max(0.0, Math.min(KEEPER_MAX_ADVANCE, rel.dot(fieldDir)));
        double side = rel.dot(lateral);

        Cuboid goal = game.getArena().getGoal(team);
        double halfWidth = goalHalfWidth(goal, line, oppGoal);
        double maxSide = Math.max(0.5, halfWidth - 0.25);
        side = Math.max(-maxSide, Math.min(maxSide, side));

        Location clamped = line.clone()
                .add(fieldDir.clone().multiply(depth))
                .add(lateral.clone().multiply(side));
        clamped.setY(line.getY());
        return clampNearGoalMouth(goal, clamped, fieldDir);
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
        Cuboid goal = game.getArena().getGoal(team);

        if (ballLoc != null) {
            Vector relativeBall = ballLoc.toVector().subtract(post.toVector());
            relativeBall.setY(0);
            double shift = relativeBall.dot(lateral);
            double halfWidth = goalHalfWidth(goal, post, oppGoal);
            double maxSide = Math.max(0.5, halfWidth - 0.25);
            post.add(lateral.clone().multiply(Math.max(-maxSide, Math.min(maxSide, shift))));
            post.setY(Math.max(post.getY(), ballLoc.getY()));
        }
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
        Location center = goalLineSpot(game.getArena(), team);
        if (center == null) {
            center = keeperPost(game, team, null, ownGoal, oppGoal);
        }
        if (ball == null || ballLoc == null || center == null) {
            return new KeeperRead(center, false);
        }

        Cuboid goal = game.getArena().getGoal(team);
        double halfWidth = goalHalfWidth(goal, center, oppGoal);
        Vector fieldDir = flatDirection(center, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            fieldDir = new Vector(0, 0, 1);
        }
        fieldDir.normalize();
        Vector lateral = new Vector(-fieldDir.getZ(), 0, fieldDir.getX());

        Vector rel = ballLoc.toVector().subtract(center.toVector());
        rel.setY(0);
        double depth = rel.dot(fieldDir);
        double side = rel.dot(lateral);

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

        boolean alert = centralDanger || shotTowardGoal;
        double maxSide = Math.max(0.35, halfWidth - 0.35);
        double lateralWeight = alert ? 0.82 : (depth > 14.0 ? 0.15 : 0.45);
        double clampedSide = Math.max(-maxSide, Math.min(maxSide, targetSide * lateralWeight));

        double forward = 0.4;
        if (alert && depth > 0 && depth < 20.0) {
            forward = Math.min(KEEPER_MAX_ADVANCE, 0.4 + depth * 0.10);
        } else if (depth > 14.0) {
            forward = 0.4;
            clampedSide *= 0.3;
        }

        Location target = center.clone()
                .add(fieldDir.clone().multiply(forward))
                .add(lateral.clone().multiply(clampedSide));
        target.setY(center.getY());
        return new KeeperRead(clampKeeperTarget(game, team, target, ownGoal, oppGoal), alert);
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
        double raw = Math.abs(fieldDir.getX()) >= Math.abs(fieldDir.getZ())
                ? Math.max(1.2, goal.getSizeZ() * 0.5)
                : Math.max(1.2, goal.getSizeX() * 0.5);
        // Cap only absurd goal regions (whole end-line); normal mouths stay full width.
        return Math.min(6.0, raw);
    }

    private static void attemptGoalkeeperCatch(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                               boolean alert, long matchTick) {
        if (ballLoc == null || bot.getLocation() == null) {
            return;
        }
        Location keeperLoc = bot.getLocation();
        Location ownGoal = goalCenter(game.getArena(), bot.getTeam());
        Location oppGoal = goalCenter(game.getArena(), bot.getTeam().opposite());

        double dist = horizontalDistance(keeperLoc, ballLoc);
        double vert = Math.abs(keeperLoc.getY() - ballLoc.getY());
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
        if (!alert && speed < 0.45 && dist > reach * 0.8) {
            return;
        }

        double lateralPenalty = shotLateralDifficulty(keeperLoc, ballLoc, ownGoal, oppGoal);
        if (lateralPenalty > 0.82 && dist > reach * 0.62) {
            return;
        }

        double quality = 0.82 + bot.getAttributes().anticipation * 0.10 + bot.getAttributes().agility * 0.05;
        quality -= Math.max(0.0, speed - 0.9) * 0.12;
        quality -= Math.max(0.0, dist / reach - 0.7) * 0.18;
        quality -= lateralPenalty * 0.12;
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
            bot.setActionCooldown(5);
            return;
        }
        // Full stretch at a hard shot: palm it away rather than try to hold it.
        boolean stretched = bot.isDiving() || dist > reach * 0.62 || lateralPenalty > 0.25;
        if (game.getSettings().botKeeperHoldsBall && speed > 1.0 && stretched && bot.canAct()
                && aiRoll(bot, matchTick, 0xA77) < 0.55) {
            game.botGoalkeeperParry(bot);
        } else {
            game.botGoalkeeperSave(bot);
        }
        bot.setActionCooldown(8);
    }

    /** 0 = central save; higher = shot dragged wide, harder to reach. */
    private static double shotLateralDifficulty(Location keeperLoc, Location ballLoc,
                                                Location ownGoal, Location oppGoal) {
        if (keeperLoc == null || ballLoc == null || ownGoal == null || oppGoal == null) {
            return 0.0;
        }
        Vector fieldDir = flatDirection(ownGoal, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            return 0.0;
        }
        fieldDir.normalize();
        Vector lateral = new Vector(-fieldDir.getZ(), 0, fieldDir.getX());
        Vector rel = ballLoc.toVector().subtract(keeperLoc.toVector());
        rel.setY(0);
        double side = Math.abs(rel.dot(lateral));
        return Math.max(0.0, side - 1.0) * 0.18;
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

    /** Free kick taken by a bot: the best open team-mate, or null to hit it long. */
    public static Location restartPassTarget(Game game, MatchBot taker, Location ballLoc) {
        if (game == null || taker == null || ballLoc == null) {
            return null;
        }
        Location ownGoal = goalCenter(game.getArena(), taker.getTeam());
        Location oppGoal = goalCenter(game.getArena(), taker.getTeam().opposite());
        PassOption pass = findBestPass(game, taker, ballLoc, oppGoal, ownGoal);
        if (pass == null || !isSafeBallPath(game, ballLoc, pass.target(), PATH_INSET)) {
            return null;
        }
        return pass.target();
    }

    /** Upfield punch when the keeper smothers a loose ball off his line. */
    public static Location keeperClearanceTarget(Game game, MatchBot keeper) {
        if (game == null || keeper == null) {
            return null;
        }
        TeamSide team = keeper.getTeam();
        Location from = game.getBallLocation();
        if (from == null) {
            from = keeper.getLocation();
        }
        if (from == null) {
            return null;
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
        return new PassOption(target, score, 0.0, false, 0.0, isHuman, PassStyle.GROUND);
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

    /**
     * The ball is ours and this player is on it. With close control he first
     * gets it under control, then picks shot, pass or carry the way a real
     * player does: settle, look up, and only then play it — unless the pressure
     * forces a quick release.
     */
    private static void handleAttack(Game game, MatchBot bot, Ball ball, Location ballLoc, Location oppGoal,
                                     Location ownGoal, double distToBall, long matchTick, boolean canKick) {
        SoccerSettings settings = game.getSettings();
        if (!settings.botCloseControl) {
            legacyAttack(game, bot, ballLoc, oppGoal, ownGoal, distToBall, matchTick, canKick);
            return;
        }
        Location self = bot.getLocation();
        bot.lookAt(ballLoc);
        if (distToBall > CONTROL_REACH) {
            // Meet a rolling ball, arriving just behind it so the next touch goes forward.
            Location meet = interceptPoint(ballLoc, ball.getVelocity(), self, true,
                    bot.getAttributes().anticipation);
            Vector upfield = flatDirection(meet, oppGoal);
            if (distToBall < 4.0 && upfield.lengthSquared() > 1e-6) {
                meet = meet.clone().subtract(upfield.normalize().multiply(0.45));
            }
            bot.moveToward(meet, sprintDecision(bot, distToBall, 2.5, 0.75), settings);
            return;
        }

        if (!bot.getUuid().equals(ball.getLastTouchId())) {
            // Slow ball arriving at his feet: take it in stride.
            Vector intent = bot.getTouchDirection();
            if (intent == null || intent.lengthSquared() < 1e-6) {
                intent = flatDirection(self, oppGoal);
            }
            if (canKick && game.botCarry(bot, intent, Math.max(0.1, bot.getSpeed()), 6, 0.55)) {
                bot.markControl(matchTick);
            } else {
                bot.moveToward(ballLoc, false, 0.7);
            }
            return;
        }

        double goalDist = horizontalDistance(self, oppGoal);
        double ownDist = horizontalDistance(self, ownGoal);
        int pressure = countOpponentsNear(game, bot.getTeam(), ballLoc, 4.5);
        int tight = countOpponentsNear(game, bot.getTeam(), ballLoc, 2.4);
        long held = matchTick - bot.getControlStartTick();
        boolean settled = held >= settleTicks(game, bot, tight);

        if (canKick) {
            boolean snapShot = goalDist < 10.0 && held >= 2 && aiRoll(bot, matchTick, 0xF1A5) < 0.5;
            if ((settled || snapShot)
                    && tryShot(game, bot, ballLoc, oppGoal, ownGoal, goalDist, ownDist, pressure, matchTick)) {
                return;
            }
            if (settled || (tight > 0 && held >= 2)) {
                PassOption pass = findBestPass(game, bot, ballLoc, oppGoal, ownGoal);
                if (pass != null
                        && wantsToPass(game, bot, pass, ballLoc, oppGoal, goalDist, ownDist, tight, held)
                        && isSafeBallPath(game, ballLoc, pass.target(), PATH_INSET)
                        && !kickThreatensOwnGoal(ballLoc, pass.target(), ownGoal)) {
                    bot.glance(pass.target(), 3, matchTick);
                    if (readyToStrike(bot, ballLoc, pass.target())) {
                        executePass(game, bot, pass, ballLoc, distToBall);
                    } else {
                        shapeToKick(bot, ballLoc, pass.target());
                    }
                    return;
                }
            }
        }
        carryBall(game, bot, ball, ballLoc, oppGoal, ownGoal, pressure, tight, matchTick);
    }

    /** Ticks a player needs to get the ball under control before he can look up and play it. */
    private static int settleTicks(Game game, MatchBot bot, int tightPressure) {
        int base = switch (game.getBotDifficulty(bot.getTeam())) {
            case EASY -> 10;
            case MEDIUM -> 6;
            case HARD -> 4;
        };
        base += (int) Math.round((0.72 - bot.getAttributes().composure) * 8.0);
        if (tightPressure > 0) {
            base -= 2;
        }
        return Math.max(2, base);
    }

    /**
     * Pass or keep it? Defenders and midfielders move it on quickly, wide men
     * and strikers run with it when there is grass in front of them.
     */
    private static boolean wantsToPass(Game game, MatchBot bot, PassOption pass, Location ballLoc,
                                       Location oppGoal, double goalDist, double ownDist, int tight, long held) {
        double gain = goalDist - horizontalDistance(pass.target(), oppGoal);
        int holdLimit = switch (bot.getRole()) {
            case DEFENDER -> 26;
            case WINGER -> 60;
            case STRIKER -> 50;
            default -> 34;
        };
        boolean space = spaceAhead(game, bot.getTeam(), ballLoc, oppGoal);
        boolean want = tight > 0
                || (pass.receiverHuman() && held >= 6)
                || gain > 6.0
                || (pass.style() != PassStyle.GROUND && gain > 2.0)
                || (!space && pass.score() > 0.9)
                || (ownDist < goalDist && !space)
                || held >= holdLimit;
        if (game.getBotDifficulty(bot.getTeam()) == BotDifficulty.EASY) {
            want = want && pass.score() > 0.38;
        }
        return want;
    }

    /** No opponent in a forward cone in front of the ball: room to run into. */
    private static boolean spaceAhead(Game game, TeamSide team, Location ballLoc, Location oppGoal) {
        Vector forward = flatDirection(ballLoc, oppGoal);
        if (forward.lengthSquared() < 1e-6) {
            return false;
        }
        forward.normalize();
        for (Location opp : opponentLocations(game, team)) {
            Vector rel = flatDirection(ballLoc, opp);
            double dist = rel.length();
            if (dist < 6.5 && dist > 1e-3 && rel.dot(forward) / dist > 0.5) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dribbling with the ball: a touch every few strides that keeps it just ahead
     * of the feet. Under pressure the touches are short and frequent; in open
     * grass the ball is pushed further and the player sprints onto it. A defender
     * square in front draws a sharp cut away from him.
     */
    private static void carryBall(Game game, MatchBot bot, Ball ball, Location ballLoc, Location oppGoal,
                                  Location ownGoal, int pressure, int tight, long matchTick) {
        SoccerSettings settings = game.getSettings();
        Location self = bot.getLocation();
        Vector dir = bot.activeSkillMove(matchTick);
        if (dir == null) {
            dir = dribbleDirection(self, oppGoal, ownGoal, game, bot.getTeam());
            Location opp = nearestOpponentLocation(game, bot.getTeam(), ballLoc);
            if (opp != null && horizontalDistance(ballLoc, opp) < 3.2) {
                Vector toOpp = flatDirection(self, opp);
                double chance = 0.35 + bot.getAttributes().agility * 0.35;
                if (toOpp.lengthSquared() > 1e-6 && toOpp.normalize().dot(dir) > 0.55
                        && aiRoll(bot, matchTick, 0x5C11) < chance) {
                    Vector lateral = new Vector(-dir.getZ(), 0, dir.getX());
                    double side = lateral.dot(toOpp) > 0 ? -1.0 : 1.0;
                    Vector cut = dir.clone().multiply(0.35).add(lateral.multiply(side)).normalize();
                    dir = keepDirectionInPlay(game, self, cut);
                    bot.startSkillMove(dir, matchTick, 8);
                }
            }
        }
        bot.setTouchDirection(dir);

        boolean close = tight > 0;
        boolean open = pressure == 0;
        boolean sprint = open && sprintDecision(bot, horizontalDistance(self, oppGoal), 10.0, 0.55);
        double pace = close ? 0.62 : (open ? 1.0 : 0.82);
        int touchTicks = close ? 5 : (sprint ? 11 : 8);
        double lead = close ? 0.55 : (sprint ? 1.25 : 0.9);
        double nominal = (sprint ? BotMovement.configuredSprintSpeed(settings)
                : BotMovement.configuredWalkSpeed(settings)) * pace * 0.9;
        double runSpeed = Math.max(0.08, Math.max(bot.getSpeed(), nominal));

        Vector rel = flatDirection(self, ballLoc);
        double ahead = rel.dot(dir);
        double across = Math.abs(rel.getX() * -dir.getZ() + rel.getZ() * dir.getX());
        long since = matchTick - bot.getLastCarryTick();
        boolean due = since >= touchTicks || ahead < 0.35 || across > 0.85 || ball.getSpeed() < 0.05;
        if (due && bot.canAct()) {
            game.botCarry(bot, dir, runSpeed, touchTicks, lead);
        }
        Location runTo = ballLoc.clone().add(dir.clone().multiply(1.6));
        runTo.setY(self.getY());
        bot.moveToward(runTo, sprint, pace);
    }

    /** True when the ball sits where a kick toward {@code target} can be struck cleanly. */
    private static boolean readyToStrike(MatchBot bot, Location ballLoc, Location target) {
        Location self = bot.getLocation();
        if (self == null || ballLoc == null || target == null) {
            return true;
        }
        Vector toBall = flatDirection(self, ballLoc);
        if (toBall.length() < 1.15) {
            return true;
        }
        Vector toTarget = flatDirection(self, target);
        if (toTarget.lengthSquared() < 1e-6) {
            return true;
        }
        return toBall.normalize().dot(toTarget.normalize()) > 0.3;
    }

    /** Step round the ball to open the body up for a kick toward {@code target}. */
    private static void shapeToKick(MatchBot bot, Location ballLoc, Location target) {
        Vector dir = flatDirection(ballLoc, target);
        if (dir.lengthSquared() < 1e-6) {
            return;
        }
        Location spot = ballLoc.clone().subtract(dir.normalize().multiply(0.8));
        spot.setY(bot.getLocation().getY());
        bot.moveToward(spot, false, 0.7);
    }

    /**
     * Shoot when it is on: in range, with a real angle at goal and no defender
     * right in the line. Long shots depend on the player's appetite for them.
     */
    private static boolean tryShot(Game game, MatchBot bot, Location ballLoc, Location oppGoal, Location ownGoal,
                                   double goalDist, double ownDist, int pressure, long matchTick) {
        Location self = bot.getLocation();
        if (ownDist <= goalDist || !avoidsOwnGoal(self, oppGoal, ownGoal)) {
            return false;
        }
        BotDifficulty difficulty = game.getBotDifficulty(bot.getTeam());
        double range = switch (difficulty) {
            case EASY -> 14.0;
            case MEDIUM -> 22.0;
            case HARD -> 30.0;
        };
        if (bot.getRole() == BotRole.DEFENDER) {
            range = Math.min(range, 16.0);
        }
        if (goalDist > range) {
            return false;
        }
        double minOpening = switch (difficulty) {
            case EASY -> 14.0;
            case MEDIUM -> 9.0;
            case HARD -> 7.0;
        };
        if (goalOpeningDegrees(game, bot.getTeam().opposite(), ballLoc) < minOpening) {
            return false;
        }
        if (goalDist > 16.0) {
            if (pressure >= 2) {
                return false;
            }
            double appetite = switch (bot.getRole()) {
                case STRIKER -> 0.6;
                case WINGER, PLAYMAKER -> 0.45;
                case DEFENDER -> 0.12;
                default -> 0.32;
            };
            if (aiRoll(bot, matchTick, 0x5407) > appetite) {
                return false;
            }
        }
        Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
        if (aim == null || !aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)
                || !isSafeBallPath(game, ballLoc, aim, PATH_INSET * 0.75)) {
            return false;
        }
        if (goalDist > 7.0 && shotBlocked(game, bot.getTeam(), ballLoc, aim)) {
            return false;
        }
        if (!readyToStrike(bot, ballLoc, aim)) {
            shapeToKick(bot, ballLoc, aim);
            return true;
        }
        bot.faceLocation(aim);
        shootAtGoal(game, bot, oppGoal, ownGoal, goalDist, aim);
        bot.setActionCooldown(12);
        return true;
    }

    /** Angle (degrees) the goal mouth subtends from {@code from}: tiny from the byline. */
    private static double goalOpeningDegrees(Game game, TeamSide defending, Location from) {
        Cuboid goal = game.getArena().getGoal(defending);
        if (goal == null || from == null) {
            return 30.0;
        }
        boolean useX = goal.getSizeX() >= goal.getSizeZ();
        double cx = (goal.getMinX() + goal.getMaxX()) * 0.5;
        double cz = (goal.getMinZ() + goal.getMaxZ()) * 0.5;
        Vector a = useX ? new Vector(goal.getMinX() - from.getX(), 0, cz - from.getZ())
                : new Vector(cx - from.getX(), 0, goal.getMinZ() - from.getZ());
        Vector b = useX ? new Vector(goal.getMaxX() - from.getX(), 0, cz - from.getZ())
                : new Vector(cx - from.getX(), 0, goal.getMaxZ() - from.getZ());
        if (a.lengthSquared() < 1e-6 || b.lengthSquared() < 1e-6) {
            return 90.0;
        }
        double dot = clamp(a.normalize().dot(b.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    /** An outfield opponent standing right in the line of the shot. */
    private static boolean shotBlocked(Game game, TeamSide team, Location ballLoc, Location aim) {
        Vector line = flatDirection(ballLoc, aim);
        double len = line.length();
        if (len < 1e-3) {
            return false;
        }
        line.multiply(1.0 / len);
        for (MatchBot opp : game.getBots()) {
            if (opp.getTeam() == team || isGoalkeeper(opp) || opp.getLocation() == null) {
                continue;
            }
            if (inShotLane(ballLoc, line, len, opp.getLocation())) {
                return true;
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            Player player = sp.getBukkitPlayer();
            if (!sp.isBot() && sp.getTeam() == team.opposite() && player != null && player.isOnline()
                    && inShotLane(ballLoc, line, len, player.getLocation())) {
                return true;
            }
        }
        return false;
    }

    private static boolean inShotLane(Location ballLoc, Vector unitLine, double len, Location body) {
        Vector rel = flatDirection(ballLoc, body);
        double along = rel.dot(unitLine);
        if (along < 1.5 || along > Math.min(9.0, len - 1.0)) {
            return false;
        }
        double off = Math.abs(rel.getX() * -unitLine.getZ() + rel.getZ() * unitLine.getX());
        return off < 0.9;
    }

    /** Where a first-time finish should go, or null when there is no sensible shot. */
    public static Location finishingAim(Game game, MatchBot bot, Location ballLoc) {
        if (game == null || bot == null || ballLoc == null || bot.getLocation() == null) {
            return null;
        }
        Location ownGoal = goalCenter(game.getArena(), bot.getTeam());
        Location oppGoal = goalCenter(game.getArena(), bot.getTeam().opposite());
        if (goalOpeningDegrees(game, bot.getTeam().opposite(), ballLoc) < 9.0) {
            return null;
        }
        Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
        if (aim == null || !aimsAtOpponentGoal(ballLoc, aim, ownGoal, oppGoal)) {
            return null;
        }
        return aim;
    }

    private static void legacyAttack(Game game, MatchBot bot, Location ballLoc, Location oppGoal,
                                     Location ownGoal, double distToBall, long matchTick,
                                     boolean canKick) {
        bot.faceLocation(oppGoal);
        Location self = bot.getLocation();
        double goalDist = horizontalDistance(self, oppGoal);
        double ownDist = horizontalDistance(self, ownGoal);
        double goalAngle = angleToGoal(self, oppGoal);
        int pressure = countOpponentsNear(game, bot.getTeam(), ballLoc, 4.5);
        BotDifficulty difficulty = game.getBotDifficulty(bot.getTeam());
        double kickReach = game.getSettings().kickReach;
        boolean hasBall = distToBall <= kickReach * 1.05;
        boolean underPressure = pressure >= 1;

        if (!canKick && hasBall) {
            Vector carry = dribbleDirection(self, oppGoal, ownGoal, game, bot.getTeam());
            bot.moveToward(curvedAdvance(self, oppGoal, carry, game, bot.getTeam()),
                    sprintDecision(bot, goalDist, 10.0, underPressure ? 0.7 : 0.5), game.getSettings());
            return;
        }
        if (!canKick && !hasBall) {
            bot.moveToward(ballLoc, sprintDecision(bot, distToBall, 4.0, 0.75), game.getSettings());
            return;
        }

        PassOption pass = findBestPass(game, bot, ballLoc, oppGoal, ownGoal);
        if (pass != null) {
            double targetGoalDist = horizontalDistance(pass.target(), oppGoal);
            boolean throughBall = targetGoalDist < goalDist - 0.8;
            boolean teammateAhead = targetGoalDist < goalDist - 0.3;
            boolean passToHuman = pass.receiverHuman();
            boolean playmaker = bot.getRole().passBias() >= 0.45;
            boolean inOwnHalf = ownDist < goalDist;
            boolean shouldPass = throughBall || teammateAhead || underPressure || passToHuman
                    || pass.score() > 0.45
                    || (hasBall && pass.score() > 0.12)
                    || (playmaker && pass.score() > 0.28)
                    || (inOwnHalf && pass.score() > 0.18);
            if (difficulty == BotDifficulty.EASY) {
                shouldPass = shouldPass && pass.score() > 0.38;
            } else if (difficulty == BotDifficulty.HARD) {
                shouldPass = shouldPass || pass.score() > 0.15 || (throughBall && pass.score() > 0.08);
            }

            if (shouldPass && isSafeBallPath(game, ballLoc, pass.target(), PATH_INSET)
                    && !kickThreatensOwnGoal(ballLoc, pass.target(), ownGoal)) {
                executePass(game, bot, pass, ballLoc, distToBall);
                return;
            }
        }

        if (hasBall && underPressure && pass == null) {
            Vector carry = dribbleDirection(self, oppGoal, ownGoal, game, bot.getTeam());
            bot.faceDirection(carry);
            game.botDribble(bot);
            Location escape = curvedAdvance(self, oppGoal, carry, game, bot.getTeam());
            bot.moveToward(escape, sprintDecision(bot, pressure, 2.0, 0.8), game.getSettings());
            bot.setActionCooldown(1);
            return;
        }

        if (difficulty == BotDifficulty.HARD
                && distToBall <= kickReach * 1.15
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

        double directShotRange = switch (difficulty) {
            case EASY -> 14.0;
            case MEDIUM -> 22.0;
            case HARD -> 31.0;
        };
        if (hasBall && goalDist < directShotRange
                && ownDist > goalDist && avoidsOwnGoal(self, oppGoal, ownGoal)) {
            Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
            if (aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)
                    && isSafeBallPath(game, ballLoc, aim, PATH_INSET * 0.85)) {
                shootAtGoal(game, bot, oppGoal, ownGoal, goalDist, aim);
                bot.setActionCooldown(14);
                return;
            }
        }

        if (shouldShoot(game, bot, goalDist, goalAngle, pressure, ownDist, matchTick)
                && avoidsOwnGoal(self, oppGoal, ownGoal)) {
            if (!hasBall) {
                bot.moveToward(ballLoc, sprintDecision(bot, distToBall, 3.0, 0.7), game.getSettings());
            }
            bot.faceLocation(oppGoal);
            if (hasBall) {
                Location aim = shotAimPoint(game, bot, ballLoc, oppGoal);
                if (aimsAtOpponentGoal(self, aim, ownGoal, oppGoal)
                        && isSafeBallPath(game, ballLoc, aim, PATH_INSET * 0.85)) {
                    shootAtGoal(game, bot, oppGoal, ownGoal, goalDist, aim);
                    bot.setActionCooldown(14);
                }
            }
            return;
        }

        if (hasBall) {
            Vector carry = dribbleDirection(self, oppGoal, ownGoal, game, bot.getTeam());
            bot.faceDirection(carry);
            game.botDribble(bot);
            bot.setActionCooldown(1);
            Location advance = curvedAdvance(self, oppGoal, carry, game, bot.getTeam());
            double space = pressure == 0 ? 0.85 : 0.55;
            bot.moveToward(advance, sprintDecision(bot, goalDist, 12.0, space), game.getSettings());
            return;
        }

        bot.moveToward(ballLoc, sprintDecision(bot, distToBall, 4.0, 0.75), game.getSettings());
    }

    private static void executePass(Game game, MatchBot bot, PassOption pass,
                                    Location ballLoc, double distToBall) {
        bot.faceLocation(pass.target());
        if (distToBall <= game.getSettings().kickReach * 1.05) {
            switch (pass.style()) {
                case LOFT -> game.botLoftPassAt(bot, pass.target());
                case CROSS -> game.botCrossPassAt(bot, pass.target());
                case STRONG -> game.botStrongPassAt(bot, pass.target(), pass.charge(), pass.lift());
                default -> game.botPassAt(bot, pass.target(), pass.charge());
            }
            bot.setActionCooldown(5);
        } else {
            bot.moveToward(ballLoc, sprintDecision(bot, distToBall, 3.0, 0.65), game.getSettings());
        }
    }

    private static PassOption findBestPass(Game game, MatchBot bot, Location ballLoc,
                                           Location oppGoal, Location ownGoal) {
        PassOption best = null;
        double bestScore = 0.08;
        UUID chaserId = game.getBallChaserBotId(bot.getTeam());

        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != bot.getTeam() || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location loc = player.getLocation();
            if (receiverOffside(game, bot, loc, ballLoc)) {
                continue;
            }
            List<Location> candidates = leadCandidates(loc, player.getVelocity());
            for (int i = 0; i < candidates.size(); i++) {
                Location candidate = candidates.get(i);
                boolean intoSpace = i > 0;
                PassOption option = scorePassTarget(game, bot, candidate, BotRole.STRIKER,
                        ballLoc, oppGoal, ownGoal, true, intoSpace);
                if (option != null && option.score() > bestScore) {
                    bestScore = option.score();
                    best = option;
                }
            }
        }

        for (MatchBot teammate : game.getBots()) {
            if (teammate.getUuid().equals(bot.getUuid()) || teammate.getUuid().equals(chaserId)
                    || isGoalkeeper(teammate)) {
                continue;
            }
            Location loc = teammate.getLocation();
            if (loc == null || receiverOffside(game, bot, loc, ballLoc)) {
                continue;
            }
            List<Location> candidates = leadCandidates(loc, teammate.getVelocity());
            for (int i = 0; i < candidates.size(); i++) {
                Location candidate = candidates.get(i);
                boolean intoSpace = i > 0;
                PassOption option = scorePassTarget(game, bot, candidate, teammate.getRole(),
                        ballLoc, oppGoal, ownGoal, false, intoSpace);
                if (option != null && option.score() > bestScore) {
                    bestScore = option.score();
                    best = option;
                }
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
        double spaceBonus = intoSpace && forwardProgress > 0 ? 0.85 : 0.0;
        double score = goalProgress * 1.8 + openness * 1.4 + roleFit * 0.45 + humanBonus
                + forwardBonus + lineBreakBonus + spaceBonus
                - distFromBall * 0.012 + passer.getRole().passBias() * 0.4;
        boolean lineBreakingPass = lineBreakBonus > 0.0 && distFromBall > 9.0;
        boolean passIntoRun = intoSpace && forwardProgress > 0.05 && distFromBall > 8.0;

        boolean crossCandidate = (passer.getRole() == BotRole.WINGER || passer.getRole() == BotRole.DEFENDER)
                && (targetRole == BotRole.STRIKER || targetRole == BotRole.WINGER)
                && distFromBall >= 12.0 && distFromBall <= 24.0
                && targetGoalDist < 18.0 && forwardProgress > 0.05;
        boolean loftCandidate = !crossCandidate && distFromBall > 13.0
                && (lineBreakingPass || countOpponentsNear(game, passer.getTeam(), ballLoc, 5.0) >= 1)
                && hasPassLane(game, passer.getTeam(), ballLoc, targetLoc);

        PassStyle style;
        if (crossCandidate && hasPassLane(game, passer.getTeam(), ballLoc, targetLoc)) {
            style = PassStyle.CROSS;
            score += 0.35;
        } else if (loftCandidate) {
            style = PassStyle.LOFT;
            score += 0.25;
        } else if (distFromBall > 16.0 || lineBreakingPass || passIntoRun) {
            style = PassStyle.STRONG;
        } else {
            style = PassStyle.GROUND;
        }

        boolean strongKick = style == PassStyle.STRONG || style == PassStyle.LOFT || style == PassStyle.CROSS;
        double distanceFactor = clamp((distFromBall - 8.0) / 22.0, 0.0, 1.0);
        double situationalBoost = 0.0;
        if (lineBreakingPass) {
            situationalBoost += 0.07;
        }
        if (passIntoRun) {
            situationalBoost += 0.10;
        }
        double charge = strongKick
                ? clamp(0.18 + distanceFactor * 0.24 + situationalBoost, 0.18, 0.52)
                : Math.min(0.72, distFromBall / 21.0);
        if (charge < 0.26) {
            charge = 0.26;
        }
        double lift = style == PassStyle.STRONG
                ? clamp(0.025 + distanceFactor * 0.06 + (lineBreakingPass ? 0.015 : 0.0), 0.02, 0.10)
                : 0.0;
        return new PassOption(targetLoc, score, charge, strongKick, lift, isHuman, style);
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
        if (game.getSettings().botKickErrors) {
            return placedShotAim(game, bot, ballLoc, aim);
        }
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

    /**
     * Pick a corner: away from where the keeper stands, otherwise across him to
     * the far post. Better finishers aim tighter to the post (and miss more
     * often when they get it wrong, through the kick error model).
     */
    private static Location placedShotAim(Game game, MatchBot bot, Location ballLoc, Location aim) {
        Cuboid goal = game.getArena().getGoal(bot.getTeam().opposite());
        if (goal == null || aim == null || ballLoc == null) {
            return aim;
        }
        boolean useX = goal.getSizeX() >= goal.getSizeZ();
        double mid = useX ? (goal.getMinX() + goal.getMaxX()) * 0.5 : (goal.getMinZ() + goal.getMaxZ()) * 0.5;
        double half = Math.max(0.6, (useX ? goal.getSizeX() : goal.getSizeZ()) * 0.5);
        double shooterOffset = (useX ? ballLoc.getX() : ballLoc.getZ()) - mid;

        double side = shooterOffset > 0.3 ? -1.0 : (shooterOffset < -0.3 ? 1.0 : 0.0);
        for (MatchBot keeper : game.getBots()) {
            if (keeper.getTeam() == bot.getTeam() || !isGoalkeeper(keeper) || keeper.getLocation() == null) {
                continue;
            }
            double keeperOffset = (useX ? keeper.getLocation().getX() : keeper.getLocation().getZ()) - mid;
            if (Math.abs(keeperOffset) > 0.25) {
                side = keeperOffset > 0 ? -1.0 : 1.0;
            }
        }
        if (side == 0.0) {
            side = aiRoll(bot, game.getMatchTick(), 0xC001) < 0.5 ? -1.0 : 1.0;
        }
        double tightness = switch (game.getBotDifficulty(bot.getTeam())) {
            case EASY -> 0.45;
            case MEDIUM -> 0.6;
            case HARD -> 0.72;
        };
        double target = mid + side * half * tightness;
        if (useX) {
            aim.setX(target);
        } else {
            aim.setZ(target);
        }
        double low = goal.getMinY() + 0.3;
        double high = Math.max(low, Math.min(goal.getMaxY() - 0.6, goal.getMinY() + 1.6));
        boolean goHigh = game.getBotDifficulty(bot.getTeam()) == BotDifficulty.HARD
                && aiRoll(bot, game.getMatchTick(), 0xC0FF) < 0.3;
        aim.setY(goHigh ? high : low + 0.3);
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
        if (nearestOpp != null && horizontalDistance(from, nearestOpp) < 4.5) {
            double side = Math.signum(toGoal.getX() * (nearestOpp.getX() - from.getX())
                    + toGoal.getZ() * (nearestOpp.getZ() - from.getZ()));
            if (Math.abs(side) < 0.1) {
                side = 1.0;
            }
            direction = toGoal.clone().multiply(0.45).add(lateral.clone().multiply(0.95 * -side)).normalize();
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

    /**
     * Defending the man on the ball: get goal-side, close him down, then jockey —
     * side-on, matching his steps a couple of blocks off — and only commit to the
     * tackle when he lets it run, stands still too long or gets near goal. A man
     * who has got past is chased down, with a slide as the last resort.
     */
    private static void handlePress(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                    Location oppGoal, double distToBall, long matchTick,
                                    boolean canKick) {
        SoccerSettings settings = game.getSettings();
        if (!settings.botTackleDuels) {
            legacyPress(game, bot, ball, ballLoc, oppGoal, distToBall, matchTick, canKick);
            return;
        }
        Location self = bot.getLocation();
        Location ownGoal = goalCenter(game.getArena(), bot.getTeam());
        UUID carrierId = ball.getLastTouchId();
        Location carrierAt = carrierId != null ? game.participantLocation(carrierId) : null;
        Location carrier = carrierAt != null && carrierAt.getWorld() == ballLoc.getWorld() ? carrierAt.clone() : null;
        boolean carried = carrier != null && horizontalDistance(carrier, ballLoc) < 1.9 && ball.getSpeed() < 0.6;

        if (!carried) {
            // Loose or travelling: go and win it — contact takes it off the floor.
            bot.squareUpTo(null, matchTick, 0);
            bot.tickJockey(false);
            Location meet = interceptPoint(ballLoc, ball.getVelocity(), self, true,
                    bot.getAttributes().anticipation);
            bot.moveToward(meet, sprintDecision(bot, distToBall, 1.5, 0.9), settings);
            return;
        }

        Vector toGoal = flatDirection(ballLoc, ownGoal);
        if (toGoal.lengthSquared() < 1e-6) {
            toGoal = flatDirection(oppGoal, ownGoal);
        }
        toGoal.normalize();
        Location jockeySpot = ballLoc.clone().add(toGoal.clone().multiply(JOCKEY_DISTANCE));
        jockeySpot.setY(self.getY());
        double toSpot = horizontalDistance(self, jockeySpot);
        boolean goalSide = horizontalDistance(self, ownGoal) < horizontalDistance(carrier, ownGoal) - 0.4;
        MatchBot carrierBot = game.getBot(carrierId);
        boolean carrierStaggered = carrierBot != null && carrierBot.isStaggered(matchTick);

        if (!goalSide) {
            bot.squareUpTo(null, matchTick, 0);
            bot.tickJockey(false);
            if (canKick && distToBall < 4.8 && shouldSlideTackle(game, bot, ball, ballLoc, distToBall, matchTick)) {
                bot.faceLocation(ballLoc);
                if (bot.slideTackleToward(ballLoc, matchTick)) {
                    game.onBotSlideTackle(bot);
                    Location clearTarget = safeClearanceTarget(game, ballLoc, oppGoal, ownGoal);
                    bot.faceLocation(clearTarget);
                    game.botKick(bot, BallKickEvent.KickType.TACKLE, 0);
                    bot.setActionCooldown(16);
                    return;
                }
            }
            if (canKick && distToBall <= 1.9 && bot.canDuel(matchTick)
                    && aiRoll(bot, matchTick, 0x7B1D) < 0.3 + bot.getAttributes().aggression * 0.3) {
                game.botStandingTackle(bot, flatDirection(ballLoc, safeClearanceTarget(game, ballLoc, oppGoal, ownGoal)));
                bot.setActionCooldown(4);
                return;
            }
            // Recovery run: back between the man and the goal.
            bot.moveToward(jockeySpot, sprintDecision(bot, toSpot, 1.2, 0.95), settings);
            return;
        }

        if (distToBall > 3.4) {
            // Close him down, easing up on arrival so he can't just knock it past.
            bot.squareUpTo(null, matchTick, 0);
            bot.tickJockey(false);
            double urgency = 0.75 + bot.getAttributes().aggression * 0.2;
            bot.moveToward(jockeySpot, sprintDecision(bot, toSpot, 3.0, urgency), toSpot < 3.5 ? 0.8 : 1.0);
            return;
        }

        bot.tickJockey(true);
        bot.squareUpTo(ballLoc, matchTick, 3);
        bot.moveToward(jockeySpot, toSpot > 1.8, toSpot > 1.2 ? 0.95 : 0.62);

        double looseness = horizontalDistance(carrier, ballLoc);
        double danger = clamp(1.0 - horizontalDistance(ballLoc, ownGoal) / 24.0, 0.0, 1.0);
        int patience = (int) Math.round(10 + (1.0 - bot.getAttributes().aggression) * 30 - danger * 12);
        boolean standingStill = carrierBot != null && carrierBot.getSpeed() < 0.05
                && bot.jockeyTicks() > patience / 2;
        boolean moment = looseness > 1.05 || carrierStaggered || standingStill
                || bot.jockeyTicks() > Math.max(4, patience);
        if (canKick && moment && distToBall <= 2.0 && bot.canDuel(matchTick)) {
            Vector poke = flatDirection(ballLoc, safeClearanceTarget(game, ballLoc, oppGoal, ownGoal));
            game.botStandingTackle(bot, poke);
            bot.tickJockey(false);
            bot.setActionCooldown(4);
        }
    }

    private static void legacyPress(Game game, MatchBot bot, Ball ball, Location ballLoc,
                                    Location oppGoal, double distToBall, long matchTick,
                                    boolean canKick) {
        Location ownGoal = goalCenter(game.getArena(), bot.getTeam());
        Location pressTarget = jockeyPoint(bot.getLocation(), ballLoc, oppGoal);
        double pressUrgency = 0.75 + bot.getAttributes().aggression * 0.25;

        if (canKick && shouldSlideTackle(game, bot, ball, ballLoc, distToBall, matchTick)) {
            bot.faceLocation(ballLoc);
            if (bot.slideTackleToward(ballLoc, matchTick)) {
                game.onBotSlideTackle(bot);
                Location clearTarget = safeClearanceTarget(game, ballLoc, oppGoal, ownGoal);
                bot.faceLocation(clearTarget);
                game.botKick(bot, BallKickEvent.KickType.TACKLE, 0);
                bot.setActionCooldown(16);
                return;
            }
        }

        bot.moveToward(pressTarget, sprintDecision(bot, distToBall, 1.5, pressUrgency), game.getSettings());
        bot.faceLocation(ballLoc);
        if (canKick && distToBall <= game.getSettings().kickReach * 0.85
                && aiRoll(bot, matchTick, 0x7A3C) < 0.38) {
            Location clearTarget = safeClearanceTarget(game, ballLoc, oppGoal, ownGoal);
            bot.faceLocation(clearTarget);
            game.botKick(bot, BallKickEvent.KickType.TACKLE, 0);
            bot.setActionCooldown(18);
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
                                      Location oppGoal, TeamSide team, TeamSide possession, UUID chaserId,
                                      long matchTick) {
        boolean attacking = possession != team.opposite();
        PitchFrame pf = pitchFrame(game, ownGoal, oppGoal);
        int rank = supportRank(game, bot, ballLoc, chaserId);
        boolean runner = bot.getRole() == BotRole.STRIKER || bot.getRole() == BotRole.WINGER;
        boolean runningInBehind = false;

        Location anchor = null;
        if (attacking && runner && bot.isMakingRun(matchTick)) {
            // Timed run in behind: go now, the pass is coming.
            anchor = runInBehindPoint(game, bot, ballLoc, ownGoal, oppGoal, pf);
            runningInBehind = true;
        } else if (attacking && rank >= 0 && rank < 3) {
            // Closest spare players break shape to offer passing angles.
            anchor = supportPoint(game, bot, ballLoc, ownGoal, oppGoal, pf);
        } else if (attacking && runner) {
            anchor = runInBehindPoint(game, bot, ballLoc, ownGoal, oppGoal, pf);
            maybeTimeRun(game, bot, ballLoc, matchTick);
        } else if (!attacking && rank == 0 && bot.getRole() == BotRole.DEFENDER) {
            anchor = coverPoint(game, bot, ballLoc, ownGoal, oppGoal, pf);
        } else if (!attacking && game.getSettings().botMarking) {
            anchor = markingPoint(game, bot, ballLoc, ownGoal, oppGoal, chaserId, pf);
        }
        if (anchor == null) {
            anchor = formationAnchor(game, bot, ballLoc, ownGoal, oppGoal, pf, attacking);
        }

        anchor = clampAwayFromOwnGoal(game, bot, anchor, ownGoal, oppGoal);
        anchor = spreadFromTeammates(game, bot, anchor);
        if (attacking) {
            // Get free of markers; defenders stay tight to their man instead.
            anchor = spreadFromOpponents(game, bot, anchor);
            if (!runningInBehind) {
                anchor = holdOnside(game, bot, anchor, ballLoc);
            }
        }
        anchor = clampToField(game.getArena(), anchor);

        double distToAnchor = horizontalDistance(bot.getLocation(), anchor);
        double runUrgency = runningInBehind ? 1.0
                : distToAnchor > 10.0 ? 0.95 : 0.65 + bot.getAttributes().workRate * 0.25;
        bot.moveToward(anchor, sprintDecision(bot, distToAnchor, 2.0, runUrgency), game.getSettings());
        bot.faceLocation(ballLoc);
    }

    /**
     * Strikers wait on the shoulder of the last defender and go the moment a
     * team-mate has the ball under control and his head up.
     */
    private static void maybeTimeRun(Game game, MatchBot bot, Location ballLoc, long matchTick) {
        Ball ball = game.getBall();
        if (ball == null || !bot.canStartRun(matchTick) || ball.getLastTouchTeam() != bot.getTeam()) {
            return;
        }
        UUID carrierId = ball.getLastTouchId();
        if (carrierId == null || carrierId.equals(bot.getUuid())) {
            return;
        }
        Location carrier = game.participantLocation(carrierId);
        if (carrier == null || horizontalDistance(carrier, ballLoc) > 1.8) {
            return;
        }
        double dist = horizontalDistance(bot.getLocation(), ballLoc);
        if (dist < 6.0 || dist > 30.0) {
            return;
        }
        double chance = 0.12 + bot.getAttributes().anticipation * 0.18 + bot.getAttributes().workRate * 0.05;
        if (aiRoll(bot, matchTick, 0x2A11) < chance) {
            bot.startRun(matchTick, 24 + (int) Math.round(bot.getAttributes().anticipation * 10.0));
        }
    }

    /** How far up the pitch an attacker of {@code attacking} may stand without being offside. */
    private static double offsideLineProgress(Game game, OffsideChecker.PitchAxis axis, TeamSide attacking,
                                              Location ballLoc) {
        List<Double> defenders = new ArrayList<>();
        for (Location loc : opponentLocations(game, attacking)) {
            defenders.add(axis.attackProgress(attacking, loc));
        }
        double line = OffsideChecker.secondLastOpponentProgress(defenders);
        double ball = axis.attackProgress(attacking, ballLoc);
        Cuboid field = game.getArena().getField();
        double halfway = field != null ? axis.attackProgress(attacking, field.getCenter()) : ball;
        return Math.max(line, Math.max(ball, halfway));
    }

    private static boolean offsideAware(Game game) {
        SoccerSettings settings = game.getSettings();
        return settings.offsideEnabled && settings.botOffsideAware && game.getPitchAxis() != null;
    }

    /** Pull an attacking position back onside, onto the shoulder of the last defender. */
    private static Location holdOnside(Game game, MatchBot bot, Location anchor, Location ballLoc) {
        if (anchor == null || !offsideAware(game)) {
            return anchor;
        }
        OffsideChecker.PitchAxis axis = game.getPitchAxis();
        double line = offsideLineProgress(game, axis, bot.getTeam(), ballLoc);
        if (Double.isInfinite(line)) {
            return anchor;
        }
        double margin = 0.6 + (1.0 - bot.getAttributes().anticipation) * 0.8;
        double progress = axis.attackProgress(bot.getTeam(), anchor);
        if (Double.isNaN(progress) || progress <= line - margin) {
            return anchor;
        }
        Vector back = axis.attackDirection(bot.getTeam()).clone().multiply(progress - (line - margin));
        return anchor.clone().subtract(back);
    }

    /** A pass to this team-mate right now would be flagged (the passer reads the line). */
    private static boolean receiverOffside(Game game, MatchBot passer, Location receiver, Location ballLoc) {
        if (receiver == null || !offsideAware(game)) {
            return false;
        }
        OffsideChecker.PitchAxis axis = game.getPitchAxis();
        double line = offsideLineProgress(game, axis, passer.getTeam(), ballLoc);
        if (axis.attackProgress(passer.getTeam(), receiver) <= line) {
            return false;
        }
        // Weaker sides sometimes miss the run and play it anyway.
        return game.getBotDifficulty(passer.getTeam()) != BotDifficulty.EASY
                || aiRoll(passer, game.getMatchTick(), 0x0FF5) > 0.35;
    }

    /**
     * Man-marking when the other side has the ball: spare players pick up the
     * most dangerous free attackers (greedy pairing, so nobody is marked twice
     * and nobody gets dragged across the pitch) and stand goal-side of them,
     * shaded toward the ball to cut out the pass.
     */
    private static Location markingPoint(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                         Location oppGoal, UUID chaserId, PitchFrame pf) {
        TeamSide team = bot.getTeam();
        Ball ball = game.getBall();
        UUID carrierId = ball != null ? ball.getLastTouchId() : null;

        List<Location> threats = new ArrayList<>();
        for (MatchBot opp : game.getBots()) {
            Location at = opp.getLocation();
            if (opp.getTeam() == team || isGoalkeeper(opp) || at == null || opp.getUuid().equals(carrierId)) {
                continue;
            }
            if (horizontalDistance(at, ownGoal) < 36.0) {
                threats.add(at.clone());
            }
        }
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            Player player = sp.getBukkitPlayer();
            if (sp.isBot() || sp.getTeam() != team.opposite() || player == null || !player.isOnline()
                    || sp.getUuid().equals(carrierId)) {
                continue;
            }
            if (horizontalDistance(player.getLocation(), ownGoal) < 36.0) {
                threats.add(player.getLocation());
            }
        }
        if (threats.isEmpty()) {
            return null;
        }
        threats.sort(Comparator.comparingDouble(loc -> horizontalDistance(loc, ownGoal)));

        List<MatchBot> markers = new ArrayList<>();
        List<Location> homes = new ArrayList<>();
        for (MatchBot mate : game.getBots()) {
            if (mate.getTeam() != team || isGoalkeeper(mate) || mate.getUuid().equals(chaserId)
                    || mate.getLocation() == null) {
                continue;
            }
            markers.add(mate);
            homes.add(formationAnchor(game, mate, ballLoc, ownGoal, oppGoal, pf, false));
        }
        boolean[] taken = new boolean[markers.size()];
        for (Location threat : threats) {
            int bestIdx = -1;
            double best = 14.0;
            for (int i = 0; i < markers.size(); i++) {
                if (taken[i]) {
                    continue;
                }
                double cost = horizontalDistance(homes.get(i), threat) * markWeight(markers.get(i).getRole());
                if (cost < best) {
                    best = cost;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                continue;
            }
            taken[bestIdx] = true;
            if (markers.get(bestIdx) == bot) {
                Vector goalSide = flatDirection(threat, ownGoal);
                if (goalSide.lengthSquared() < 1e-6) {
                    return null;
                }
                Location spot = threat.clone().add(goalSide.normalize().multiply(MARK_DISTANCE));
                Vector ballSide = flatDirection(threat, ballLoc);
                if (ballSide.lengthSquared() > 1e-6) {
                    spot.add(ballSide.normalize().multiply(0.7));
                }
                spot.setY(ballLoc.getY());
                return spot;
            }
        }
        return null;
    }

    /** Lower = more willing to man-mark (defenders track runners, strikers rarely do). */
    private static double markWeight(BotRole role) {
        return switch (role) {
            case DEFENDER -> 0.8;
            case MIDFIELDER, PLAYMAKER -> 1.0;
            case WINGER -> 1.3;
            case STRIKER -> 1.8;
            default -> 1.1;
        };
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

    /** Strikers and wingers make diagonal runs in behind the defensive line. */
    private static Location runInBehindPoint(Game game, MatchBot bot, Location ballLoc,
                                             Location ownGoal, Location oppGoal, PitchFrame pf) {
        Vector toOpp = flatDirection(ballLoc, oppGoal);
        if (toOpp.lengthSquared() < 1e-6) {
            toOpp = pf.forward().clone();
        }
        toOpp.normalize();
        Vector lateral = new Vector(-toOpp.getZ(), 0, toOpp.getX());

        BotFormation formation = game.getFormation(bot.getTeam());
        double laneFrac = formation != null ? formation.slot(bot.getFormationSlot()).lane() : 0.0;
        double side = Math.signum(laneFrac == 0.0 ? (bot.getFormationSlot() % 2 == 0 ? 1.0 : -1.0) : laneFrac);

        Location run = ballLoc.clone()
                .add(toOpp.clone().multiply(10.0 + bot.getAttributes().anticipation * 4.0))
                .add(lateral.clone().multiply(5.5 * side));
        run.setY(ballLoc.getY());
        if (hasPassLane(game, bot.getTeam(), ballLoc, run)
                && insideFieldInset(game.getArena().getField(), run, PATH_INSET)) {
            return run;
        }
        return supportPoint(game, bot, ballLoc, ownGoal, oppGoal, pf);
    }

    /** One covering centre-back steps goal-side of the ball; uses formation width. */
    private static Location coverPoint(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                       Location oppGoal, PitchFrame pf) {
        Vector toOwn = flatDirection(ballLoc, ownGoal);
        if (toOwn.lengthSquared() < 1e-6) {
            toOwn = pf.forward().clone().multiply(-1);
        }
        toOwn.normalize();
        Location cover = ballLoc.clone().add(toOwn.multiply(5.0));
        cover.setY(ballLoc.getY());

        BotFormation formation = game.getFormation(bot.getTeam());
        double laneFrac = 0.0;
        if (formation != null) {
            laneFrac = formation.slot(bot.getFormationSlot()).lane();
        }
        Vector lateral = pf.lateral().clone();
        if (lateral.lengthSquared() > 1e-6) {
            lateral.normalize();
            cover.add(lateral.multiply(laneFrac * pf.halfWidth() * 0.35));
        }
        return clampToField(game.getArena(), cover);
    }

    /**
     * Formation slot for an off-ball player. Uses the bot's actual formation slot
     * (depth + lane) so the back four spreads across the pitch instead of clustering.
     */
    private static Location formationAnchor(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                            Location oppGoal, PitchFrame pf, boolean attacking) {
        BotFormation formation = game.getFormation(bot.getTeam());
        BotFormation.FormationSlot fslot = formation != null
                ? formation.slot(bot.getFormationSlot()) : null;

        double progress = progressAlongPitch(ballLoc, ownGoal, oppGoal);
        Vector rel = ballLoc.toVector().subtract(pf.center().toVector());
        rel.setY(0);
        double ballSide = clamp(rel.dot(pf.lateral()) / pf.halfWidth(), -1.0, 1.0);
        double ballDepth = progress * 2.0 - 1.0;

        double homeDepth;
        double homeLane;
        if (fslot != null) {
            homeDepth = fslot.depth() * 1.85 - 0.92;
            homeLane = fslot.lane();
        } else {
            int slot = outfieldSlotIndex(bot, game, bot.getTeam());
            int count = outfieldSlotCount(game, bot.getTeam());
            homeDepth = roleBaseDepth(bot.getRole());
            homeLane = count > 1 ? ((slot + 0.5) / count) * 2.0 - 1.0 : 0.0;
            homeLane *= roleWidth(bot.getRole());
        }

        double shiftDepth = ballDepth * shapeShiftForRole(bot.getRole(), attacking);
        double phase = attacking ? 0.10 : -0.06;
        double minDepthFrac = minDepthFracForRole(bot.getRole());
        double depthFrac = clamp(homeDepth + shiftDepth + phase, minDepthFrac, 0.92);

        if (bot.getRole() == BotRole.DEFENDER && progress > 0.52) {
            depthFrac += (progress - 0.52) * 0.55;
        }
        if (bot.getRole() == BotRole.MIDFIELDER && progress > 0.62) {
            depthFrac += (progress - 0.62) * 0.35;
        }
        depthFrac = clamp(depthFrac, minDepthFrac, 0.92);

        double laneFrac = clamp(homeLane * 0.88 + ballSide * 0.22, -0.98, 0.98);

        Location anchor = pf.center().clone()
                .add(pf.forward().clone().multiply(depthFrac * pf.halfLength() * 0.92))
                .add(pf.lateral().clone().multiply(laneFrac * pf.halfWidth() * 0.82));
        anchor.setY(ballLoc.getY());
        return clampToField(game.getArena(), anchor);
    }

    private static double shapeShiftForRole(BotRole role, boolean attacking) {
        return switch (role) {
            case DEFENDER -> attacking ? 0.24 : 0.18;
            case MIDFIELDER, PLAYMAKER -> attacking ? 0.32 : 0.26;
            case WINGER -> attacking ? 0.36 : 0.28;
            case STRIKER -> attacking ? 0.38 : 0.30;
            default -> attacking ? 0.28 : 0.22;
        };
    }

    /** Home depth along the pitch: -1 = own goal, +1 = opponent goal. */
    private static double roleBaseDepth(BotRole role) {
        return switch (role) {
            case STRIKER -> 0.5;
            case WINGER -> 0.22;
            case PLAYMAKER -> -0.02;
            case MIDFIELDER -> -0.05;
            case DEFENDER -> -0.38;
            default -> 0.0;
        };
    }

    /** Deepest allowed shape position per role (negative = toward own goal). */
    private static double minDepthFracForRole(BotRole role) {
        return switch (role) {
            case DEFENDER -> -0.62;
            case MIDFIELDER, PLAYMAKER -> -0.48;
            case WINGER -> -0.35;
            case STRIKER -> -0.22;
            default -> -0.95;
        };
    }

    /** Minimum distance in front of the goal line for outfield players. */
    private static double minDepthBlocksFromGoal(BotRole role) {
        return switch (role) {
            case DEFENDER -> 3.5;
            case MIDFIELDER, PLAYMAKER -> 8.0;
            case WINGER -> 10.0;
            case STRIKER -> 12.0;
            default -> 5.0;
        };
    }

    private static Location clampAwayFromOwnGoal(Game game, MatchBot bot, Location anchor,
                                               Location ownGoal, Location oppGoal) {
        if (anchor == null || isGoalkeeper(bot)) {
            return anchor;
        }
        double minDepth = minDepthBlocksFromGoal(bot.getRole());
        double depth = depthFromGoalLine(game, bot.getTeam(), anchor, ownGoal, oppGoal);
        if (depth >= minDepth) {
            return anchor;
        }
        Vector fieldDir = flatDirection(ownGoal, oppGoal);
        if (fieldDir.lengthSquared() < 1e-6) {
            return anchor;
        }
        fieldDir.normalize();
        Location adjusted = anchor.clone().add(fieldDir.multiply(minDepth - depth));
        adjusted.setY(anchor.getY());
        return adjusted;
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
            Vector sep = separationPush(anchor, mate.getLocation());
            if (isGoalkeeper(mate)) {
                double distKeeper = horizontalDistance(anchor, mate.getLocation());
                if (distKeeper < KEEPER_SEPARATION_RADIUS) {
                    Vector away = anchor.toVector().subtract(mate.getLocation().toVector());
                    away.setY(0);
                    if (away.lengthSquared() < 1e-6) {
                        away = new Vector(1, 0, 0);
                    }
                    away.normalize();
                    sep.add(away.multiply((KEEPER_SEPARATION_RADIUS - distKeeper) * 1.1));
                }
                sep.multiply(2.5);
            }
            push.add(sep);
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

    /** Steer off-ball runs away from the nearest opponent. */
    private static Location spreadFromOpponents(Game game, MatchBot bot, Location anchor) {
        if (anchor == null) {
            return null;
        }
        Location nearest = nearestOpponentLocation(game, bot.getTeam(), anchor);
        if (nearest == null) {
            return anchor;
        }
        double dist = horizontalDistance(anchor, nearest);
        if (dist >= 3.5) {
            return anchor;
        }
        Vector away = anchor.toVector().subtract(nearest.toVector());
        away.setY(0);
        if (away.lengthSquared() < 1e-6) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        Location shifted = anchor.clone().add(away.multiply((3.5 - dist) * 0.9 + 0.6));
        shifted.setY(anchor.getY());
        return clampToField(game.getArena(), shifted);
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
        if (ball == null) {
            return ballLoc;
        }
        return interceptPoint(ballLoc, ball.getVelocity(), self, chaser, anticipation);
    }

    private static Location interceptPoint(Location ballLoc, Vector velocity, Location self, boolean chaser,
                                           double anticipation) {
        if (ballLoc == null || self == null || velocity == null) {
            return ballLoc;
        }
        Vector ballVel = velocity.clone();
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

    private static boolean segmentNearLocation(Location from, Location to, Location point,
                                               double radius, double maxHeight) {
        if (from == null || to == null || point == null) {
            return false;
        }
        Vector a = from.toVector();
        Vector b = to.toVector();
        Vector p = point.toVector();
        Vector ab = b.clone().subtract(a);
        ab.setY(0);
        double lenSq = ab.lengthSquared();
        double dist;
        if (lenSq < 1e-8) {
            dist = Math.hypot(p.getX() - a.getX(), p.getZ() - a.getZ());
        } else {
            Vector ap = p.clone().subtract(a);
            ap.setY(0);
            double t = Math.max(0.0, Math.min(1.0, ap.dot(ab) / lenSq));
            Vector closest = a.clone().add(ab.multiply(t));
            dist = Math.hypot(p.getX() - closest.getX(), p.getZ() - closest.getZ());
        }
        return dist <= radius && Math.abs(p.getY() - to.getY()) <= maxHeight;
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
                              boolean strongKick, double lift, boolean receiverHuman, PassStyle style) {
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
