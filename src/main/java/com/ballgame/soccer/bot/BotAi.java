package com.ballgame.soccer.bot;

import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.event.BallKickEvent;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tactical AI — roles, pressing, passing lanes, and composed finishing. */
final class BotAi {

    private static final double SHOOT_RANGE = 26.0;
    private static final double PASS_RANGE = 20.0;
    private static final double MARK_RANGE = 14.0;

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
        MatchBot designatedChaser = selectChaser(game, ballLoc, team, ownGoal, oppGoal);

        if (designatedChaser == bot) {
            if (distToBall > game.getSettings().kickReach * 0.9) {
                Location chaseTarget = interceptPoint(ball, ballLoc, self, designatedChaser == bot);
                bot.moveToward(chaseTarget, distToBall > 7.0, game.getSettings());
                return;
            }
            if (possession == team || possession == null) {
                handleAttack(game, bot, ballLoc, oppGoal, ownGoal, distToBall);
            } else {
                handlePress(game, bot, ballLoc, oppGoal, distToBall);
            }
            return;
        }

        handleOffBall(game, bot, ballLoc, ownGoal, oppGoal, team, possession, distToBall, matchTick);
    }

    private static void handleAttack(Game game, MatchBot bot, Location ballLoc, Location oppGoal,
                                     Location ownGoal, double distToBall) {
        bot.faceLocation(oppGoal);
        double goalDist = horizontalDistance(bot.getLocation(), oppGoal);
        double goalAngle = angleToGoal(bot.getLocation(), oppGoal);
        int pressure = countOpponentsNear(game, bot.getTeam(), ballLoc, 4.5);

        if (shouldShoot(bot, goalDist, goalAngle, pressure)) {
            bot.moveToward(ballLoc, false, game.getSettings());
            if (distToBall <= game.getSettings().kickReach * 1.05) {
                double charge = goalDist < 12.0 ? 0.92 : (goalDist < 18.0 ? 0.72 : 0.55);
                game.botKick(bot, BallKickEvent.KickType.SHOT, charge);
                bot.setActionCooldown(18);
            }
            return;
        }

        PassOption pass = findBestPass(game, bot, ballLoc, oppGoal);
        if (pass != null && (pressure >= 1 || bot.getRole().passBias() > 0.6)) {
            bot.faceLocation(pass.target());
            if (distToBall <= game.getSettings().kickReach * 1.05) {
                game.botKick(bot, BallKickEvent.KickType.PASS, pass.charge());
                bot.setActionCooldown(14);
            } else {
                bot.moveToward(ballLoc, pressure > 0, game.getSettings());
            }
            return;
        }

        if (distToBall <= game.getSettings().kickReach * 1.05) {
            Vector carry = dribbleDirection(bot.getLocation(), oppGoal, game, bot.getTeam());
            bot.faceDirection(carry);
            game.botDribble(bot);
            bot.setActionCooldown(3);
            if (goalDist > 8.0) {
                Location advance = curvedAdvance(bot.getLocation(), oppGoal, carry, game, bot.getTeam());
                bot.moveToward(advance, goalDist > 18.0, game.getSettings());
            }
            return;
        }

        bot.moveToward(ballLoc, goalDist > 14.0, game.getSettings());
    }

    private static void handlePress(Game game, MatchBot bot, Location ballLoc,
                                    Location oppGoal, double distToBall) {
        Location pressTarget = jockeyPoint(bot.getLocation(), ballLoc, oppGoal);
        bot.moveToward(pressTarget, true, game.getSettings());
        bot.faceLocation(ballLoc);
        if (distToBall <= game.getSettings().kickReach * 1.15) {
            game.botKick(bot, BallKickEvent.KickType.TACKLE, 0);
            bot.setActionCooldown(22);
        }
    }

    private static void handleOffBall(Game game, MatchBot bot, Location ballLoc, Location ownGoal,
                                      Location oppGoal, TeamSide team, TeamSide possession,
                                      double distToBall, long matchTick) {
        Location anchor = formationSpot(game, bot, ballLoc, ownGoal, oppGoal, possession);

        if (possession != null && possession != team) {
            if (bot.getRole() == BotRole.DEFENDER && distToBall < MARK_RANGE) {
                anchor = blend(anchor, defensiveMark(ballLoc, ownGoal), 0.55);
            }
            bot.moveToward(anchor, distToBall > 11.0, game.getSettings());
            bot.faceLocation(ballLoc);
            return;
        }

        if (possession == team) {
            MatchBot chaser = selectChaser(game, ballLoc, team, ownGoal, oppGoal);
            if (chaser != null && chaser != bot) {
                anchor = supportRun(bot, chaser.getLocation(), oppGoal, ownGoal, matchTick);
            }
        }

        bot.moveToward(anchor, horizontalDistance(bot.getLocation(), anchor) > 9.0, game.getSettings());
        if (distToBall < PASS_RANGE) {
            bot.faceLocation(ballLoc);
        } else {
            bot.faceLocation(oppGoal);
        }
    }

    private static Location interceptPoint(Ball ball, Location ballLoc, Location self, boolean chaser) {
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
        double lead = Math.min(4.5, Math.max(1.2, dist * 0.18) + speed * 6.0);
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

    private static MatchBot selectChaser(Game game, Location ballLoc, TeamSide team,
                                         Location ownGoal, Location oppGoal) {
        List<ChaserCandidate> candidates = new ArrayList<>();
        double ballProgress = progressAlongPitch(ballLoc, ownGoal, oppGoal);

        for (MatchBot bot : game.getBots()) {
            if (bot.getTeam() != team) {
                continue;
            }
            Location loc = bot.getLocation();
            if (loc == null) {
                continue;
            }
            double dist = horizontalDistance(loc, ballLoc);
            double roleWeight = bot.getRole().chaseWeight();
            if (bot.getRole() == BotRole.DEFENDER && ballProgress > 0.58) {
                roleWeight *= 0.35;
            }
            if (bot.getRole() == BotRole.STRIKER && ballProgress < 0.35) {
                roleWeight *= 0.75;
            }
            candidates.add(new ChaserCandidate(bot, dist / Math.max(0.2, roleWeight)));
        }

        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != team || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            double dist = horizontalDistance(player.getLocation(), ballLoc);
            candidates.add(new ChaserCandidate(null, dist / 1.15));
        }

        candidates.sort(Comparator.comparingDouble(c -> c.score));
        return candidates.isEmpty() ? null : candidates.get(0).bot;
    }

    private static Location formationSpot(Game game, MatchBot bot, Location ballLoc,
                                            Location ownGoal, Location oppGoal, TeamSide possession) {
        Vector pitch = flatDirection(ownGoal, oppGoal);
        if (pitch.lengthSquared() < 1e-6) {
            pitch = new Vector(0, 0, 1);
        }
        pitch.normalize();
        Vector lateral = new Vector(-pitch.getZ(), 0, pitch.getX());

        double progress = progressAlongPitch(ballLoc, ownGoal, oppGoal);
        double depth = bot.getRole().attackDepth();
        if (possession != null && possession != bot.getTeam()) {
            depth = Math.min(depth, 0.42);
        } else if (possession == bot.getTeam()) {
            depth = Math.min(0.88, depth + 0.08);
        }
        double along = progress * 0.55 + depth * 0.45;
        double width = bot.getRole().lateralSpread();
        if (bot.getSquadIndex() % 2 == 0) {
            width = -width;
        }

        double span = Math.max(6.0, horizontalDistance(ownGoal, oppGoal) * 0.34);
        Location spot = ownGoal.clone().add(pitch.clone().multiply(along * horizontalDistance(ownGoal, oppGoal)));
        spot.add(lateral.clone().multiply(width * span));
        spot.setY(ballLoc.getY());
        return clampToField(game.getArena(), spot);
    }

    private static Location supportRun(MatchBot bot, Location carrierLoc, Location oppGoal,
                                       Location ownGoal, long matchTick) {
        Vector toGoal = flatDirection(carrierLoc, oppGoal);
        if (toGoal.lengthSquared() < 1e-6) {
            toGoal = new Vector(0, 0, 1);
        }
        toGoal.normalize();
        Vector lateral = new Vector(-toGoal.getZ(), 0, toGoal.getX());
        double side = bot.getSquadIndex() % 2 == 0 ? 1.0 : -1.0;
        double ahead = bot.getRole() == BotRole.STRIKER ? 7.0 : 4.5;
        if (matchTick % 40 < 20 && bot.getRole() == BotRole.WINGER) {
            ahead += 2.5;
        }
        Location run = carrierLoc.clone()
                .add(toGoal.clone().multiply(ahead))
                .add(lateral.clone().multiply(3.5 * side * bot.getRole().lateralSpread() + side * 2.0));
        run.setY(carrierLoc.getY());
        return run;
    }

    private static Location defensiveMark(Location ballLoc, Location ownGoal) {
        return blend(ownGoal, ballLoc, 0.38);
    }

    private static PassOption findBestPass(Game game, MatchBot bot, Location ballLoc, Location oppGoal) {
        PassOption best = null;
        double bestScore = 0.35;

        for (MatchBot teammate : game.getBots()) {
            PassOption option = scorePassTarget(game, bot, teammate.getLocation(), teammate.getRole(),
                    ballLoc, oppGoal, true);
            if (option != null && option.score() > bestScore) {
                bestScore = option.score();
                best = option;
            }
        }

        for (SoccerPlayer sp : game.getHumanParticipants()) {
            if (sp.getTeam() != bot.getTeam() || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            PassOption option = scorePassTarget(game, bot, player.getLocation(), BotRole.MIDFIELDER,
                    ballLoc, oppGoal, false);
            if (option != null && option.score() > bestScore) {
                bestScore = option.score();
                best = option;
            }
        }
        return best;
    }

    private static PassOption scorePassTarget(Game game, MatchBot passer, Location targetLoc,
                                              BotRole targetRole, Location ballLoc, Location oppGoal,
                                              boolean isBot) {
        if (targetLoc == null) {
            return null;
        }
        double distFromBall = horizontalDistance(targetLoc, ballLoc);
        if (distFromBall < 3.5 || distFromBall > PASS_RANGE) {
            return null;
        }
        if (countOpponentsNear(game, passer.getTeam(), targetLoc, 2.8) >= 2) {
            return null;
        }
        if (!hasPassLane(game, passer.getTeam(), ballLoc, targetLoc)) {
            return null;
        }

        double goalProgress = 1.0 / (1.0 + horizontalDistance(targetLoc, oppGoal));
        double openness = 1.0 / (1.0 + countOpponentsNear(game, passer.getTeam(), targetLoc, 5.0));
        double roleFit = targetRole == BotRole.STRIKER ? 1.15 : 1.0;
        double score = goalProgress * 1.4 + openness * 0.9 + roleFit * 0.25
                - distFromBall * 0.025 + passer.getRole().passBias() * 0.2;
        double charge = distFromBall > 12.0 ? 0.72 : 0.48;
        return new PassOption(targetLoc, score, charge);
    }

    private static boolean shouldShoot(MatchBot bot, double goalDist, double goalAngle, int pressure) {
        if (goalDist > SHOOT_RANGE || goalAngle > 38.0) {
            return false;
        }
        if (bot.getRole() == BotRole.DEFENDER && goalDist > 16.0) {
            return false;
        }
        if (pressure >= 2 && goalDist > 10.0 && bot.getRole().passBias() > 0.5) {
            return false;
        }
        return goalDist < 11.0 || goalAngle < 18.0 || (bot.getRole() == BotRole.STRIKER && goalDist < 18.0);
    }

    private static Vector dribbleDirection(Location from, Location oppGoal, Game game, TeamSide team) {
        Vector toGoal = flatDirection(from, oppGoal);
        if (toGoal.lengthSquared() < 1e-6) {
            toGoal = new Vector(0, 0, 1);
        }
        toGoal.normalize();
        Vector lateral = new Vector(-toGoal.getZ(), 0, toGoal.getX());

        Location nearestOpp = nearestOpponentLocation(game, team, from);
        if (nearestOpp != null && horizontalDistance(from, nearestOpp) < 3.5) {
            double side = Math.signum(toGoal.getX() * (nearestOpp.getX() - from.getX())
                    + toGoal.getZ() * (nearestOpp.getZ() - from.getZ()));
            if (Math.abs(side) < 0.1) {
                side = 1.0;
            }
            return toGoal.clone().multiply(0.55).add(lateral.clone().multiply(0.85 * -side)).normalize();
        }
        return toGoal;
    }

    private static boolean hasPassLane(Game game, TeamSide team, Location from, Location to) {
        Vector dir = flatDirection(from, to);
        double len = dir.length();
        if (len < 1.0) {
            return true;
        }
        dir.normalize();
        for (double d = 2.0; d < len - 1.0; d += 2.0) {
            Location probe = from.clone().add(dir.clone().multiply(d));
            if (countOpponentsNear(game, team, probe, 1.6) > 0) {
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
        double inset = 1.2;
        loc.setX(Math.max(field.getMinX() + inset, Math.min(field.getMaxX() - inset, loc.getX())));
        loc.setZ(Math.max(field.getMinZ() + inset, Math.min(field.getMaxZ() - inset, loc.getZ())));
        return loc;
    }

    private static Location blend(Location a, Location b, double t) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.clone().add(b.clone().subtract(a).multiply(t));
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

    private record ChaserCandidate(MatchBot bot, double score) {
    }

    private record PassOption(Location target, double score, double charge) {
    }
}
