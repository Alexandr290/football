package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.bot.MatchBot;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.GameState;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Automated referee decisions when no human referee is present.
 * Stops play and awards a restart (penalty, free kick, etc.).
 */
public final class AutoReferee {

    private static final double HOLD_SPEED = 0.38;
    private static final double PROXIMITY_HOLD_RADIUS = 1.2;
    private static final int SANCTION_COOLDOWN_TICKS = 200;

    private final SoccerPlugin plugin;
    private final Map<UUID, Long> holdStartTick = new HashMap<>();
    private final Map<String, Long> contestHoldStart = new HashMap<>();
    private final Map<UUID, Long> diveSneakStart = new HashMap<>();
    private long sanctionCooldownUntil;

    public AutoReferee(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void reset() {
        holdStartTick.clear();
        contestHoldStart.clear();
        diveSneakStart.clear();
        sanctionCooldownUntil = 0;
    }

    public void tick(Game game) {
        if (!canJudgeLive(game)) {
            holdStartTick.clear();
            contestHoldStart.clear();
            return;
        }
        SoccerSettings settings = plugin.settings();
        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return;
        }

        tickBallHold(game, settings, ball);
        tickShirtPulling(game, settings, ball);
        tickSimulation(game, ball);
    }

    public void tickRestart(Game game) {
        if (!canJudgeRestarts(game)) {
            return;
        }
        SoccerSettings settings = plugin.settings();
        GameState state = game.getState();

        if (state == GameState.THROW_IN) {
            tickThrowInTimeWaste(game, settings);
        } else if (state == GameState.OFFSIDE) {
            tickRestartTimeWaste(game, settings);
        }
    }

    public void onHumanSlideTackle(Game game, Player tackler) {
        // Slide tackles are not sanctioned — normal ball contest.
    }

    public void onBotSlideTackle(Game game, MatchBot bot) {
        // Slide tackles are not sanctioned — normal ball contest.
    }

    private void tickBallHold(Game game, SoccerSettings settings, Ball ball) {
        UUID holder = ball.getLastTouchId();
        if (holder == null) {
            holdStartTick.clear();
            return;
        }
        if (ball.getSpeed() > HOLD_SPEED) {
            holdStartTick.remove(holder);
            return;
        }

        SoccerPlayer holderSp = game.getPlayer(holder);
        if (holderSp == null || holderSp.isReferee() || holderSp.isSentOff() || holderSp.getTeam() == null) {
            holdStartTick.clear();
            return;
        }

        long now = game.getMatchTick();
        holdStartTick.putIfAbsent(holder, now);
        long held = now - holdStartTick.getOrDefault(holder, now);

        boolean inOwnBox = game.isInOwnGoalBox(holderSp.getTeam(), ball.getLocation());
        if (inOwnBox) {
            int limit = settings.autoRefereeKeeperHoldSeconds * 20;
            if (held >= limit) {
                sanction(game, holder, "auto-referee-keeper-hold", RefereeRestartType.PENALTY);
                holdStartTick.clear();
            }
        } else {
            int limit = settings.autoRefereePlayerHoldSeconds * 20;
            if (held >= limit) {
                sanction(game, holder, "auto-referee-player-hold", RefereeRestartType.PENALTY);
                holdStartTick.clear();
            }
        }
    }

    private void tickShirtPulling(Game game, SoccerSettings settings, Ball ball) {
        if (ball.getSpeed() > 0.55) {
            contestHoldStart.clear();
            return;
        }
        java.util.List<SoccerPlayer> field = game.getActiveFieldPlayers();
        long now = game.getMatchTick();
        int limit = settings.autoRefereeHoldContestSeconds * 20;

        for (int i = 0; i < field.size(); i++) {
            SoccerPlayer a = field.get(i);
            if (a.getTeam() == null) {
                continue;
            }
            Location aLoc = game.participantLocation(a.getUuid());
            if (aLoc == null) {
                continue;
            }
            for (int j = i + 1; j < field.size(); j++) {
                SoccerPlayer b = field.get(j);
                if (b.getTeam() == null || a.getTeam() == b.getTeam()) {
                    continue;
                }
                Location bLoc = game.participantLocation(b.getUuid());
                if (bLoc == null) {
                    continue;
                }
                String key = pairKey(a.getUuid(), b.getUuid());
                if (horizontalDistance(aLoc, bLoc) > PROXIMITY_HOLD_RADIUS) {
                    contestHoldStart.remove(key);
                    continue;
                }
                contestHoldStart.putIfAbsent(key, now);
                long held = now - contestHoldStart.getOrDefault(key, now);
                if (held >= limit) {
                    UUID offender = pickHoldOffender(ball, a, b);
                    sanction(game, offender, "auto-referee-holding", RefereeRestartType.PENALTY);
                    contestHoldStart.clear();
                    return;
                }
            }
        }
    }

    private void tickSimulation(Game game, Ball ball) {
        Location ballLoc = ball.getLocation();
        if (ballLoc == null) {
            diveSneakStart.clear();
            return;
        }
        double ballReach = plugin.settings().kickReach * 1.5;
        long now = game.getMatchTick();

        for (SoccerPlayer sp : game.getActiveFieldPlayers()) {
            if (sp.isBot() || sp.isReferee() || sp.isSentOff() || sp.getTeam() == null) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            UUID id = sp.getUuid();
            if (!player.isSneaking()) {
                diveSneakStart.remove(id);
                continue;
            }
            Location playerLoc = player.getLocation();
            if (horizontalDistance(playerLoc, ballLoc) <= ballReach) {
                diveSneakStart.remove(id);
                continue;
            }
            SoccerPlayer nearestOpp = findNearestOpponent(game, sp.getTeam(), playerLoc, 3.0);
            if (nearestOpp == null) {
                diveSneakStart.remove(id);
                continue;
            }
            Location oppLoc = game.participantLocation(nearestOpp.getUuid());
            if (oppLoc == null || horizontalDistance(oppLoc, ballLoc) <= ballReach) {
                diveSneakStart.remove(id);
                continue;
            }
            diveSneakStart.putIfAbsent(id, now);
            if (now - diveSneakStart.getOrDefault(id, now) >= 24) {
                sanction(game, id, "auto-referee-simulation", RefereeRestartType.PENALTY);
                diveSneakStart.remove(id);
                return;
            }
        }
    }

    private void tickThrowInTimeWaste(Game game, SoccerSettings settings) {
        UUID takerId = game.getThrowInTakerId();
        if (takerId == null) {
            return;
        }
        int limit = settings.autoRefereeTimeWasteSeconds * 20;
        if (game.getThrowInRestartPulse() < limit) {
            return;
        }
        sanction(game, takerId, "auto-referee-time-waste", RefereeRestartType.PENALTY);
    }

    private void tickRestartTimeWaste(Game game, SoccerSettings settings) {
        UUID takerId = game.getFreeKickTakerId();
        if (takerId == null) {
            takerId = findRestartTakerFallback(game, game.getFreeKickTeam());
        }
        if (takerId == null) {
            return;
        }
        int limit = settings.autoRefereeTimeWasteSeconds * 20;
        if (game.getOffsideRestartPulse() < limit) {
            return;
        }
        String key = game.isPenaltyRestart()
                ? "auto-referee-penalty-time-waste"
                : "auto-referee-time-waste";
        sanction(game, takerId, key, RefereeRestartType.PENALTY);
    }

    private UUID pickHoldOffender(Ball ball, SoccerPlayer a, SoccerPlayer b) {
        UUID last = ball.getLastTouchId();
        if (last != null) {
            if (last.equals(a.getUuid())) {
                return b.getUuid();
            }
            if (last.equals(b.getUuid())) {
                return a.getUuid();
            }
        }
        return a.getUuid();
    }

    private UUID findRestartTakerFallback(Game game, TeamSide team) {
        if (team == null) {
            return null;
        }
        for (SoccerPlayer sp : game.getActiveFieldPlayers()) {
            if (sp.getTeam() == team) {
                return sp.getUuid();
            }
        }
        return null;
    }

    private void sanction(Game game, UUID offenderId, String messageKey, RefereeRestartType restart) {
        if (game.getMatchTick() < sanctionCooldownUntil) {
            return;
        }
        SoccerPlayer offender = game.getPlayer(offenderId);
        if (offender == null || offender.getTeam() == null) {
            return;
        }
        sanctionCooldownUntil = game.getMatchTick() + SANCTION_COOLDOWN_TICKS;
        holdStartTick.clear();
        contestHoldStart.clear();
        TeamSide awarded = offender.getTeam().opposite();
        game.applyBotRefereeSanction(offenderId, messageKey, restart, awarded);
    }

    private boolean canJudgeLive(Game game) {
        return plugin.settings().autoRefereeEnabled
                && !game.hasHumanReferee()
                && game.getState().isLive()
                && !game.isAwaitingRefereeDecision();
    }

    private boolean canJudgeRestarts(Game game) {
        return plugin.settings().autoRefereeEnabled
                && !game.hasHumanReferee()
                && !game.isAwaitingRefereeDecision()
                && (game.getState() == GameState.THROW_IN || game.getState() == GameState.OFFSIDE);
    }

    private SoccerPlayer findNearestOpponent(Game game, TeamSide tacklerTeam, Location from, double radius) {
        SoccerPlayer nearest = null;
        double best = radius;
        for (SoccerPlayer sp : game.getActiveFieldPlayers()) {
            if (sp.getTeam() == tacklerTeam || sp.isReferee() || sp.isSentOff()) {
                continue;
            }
            Location loc = game.participantLocation(sp.getUuid());
            if (loc == null) {
                continue;
            }
            double d = horizontalDistance(from, loc);
            if (d < best) {
                best = d;
                nearest = sp;
            }
        }
        return nearest;
    }

    private static String pairKey(UUID a, UUID b) {
        if (a.compareTo(b) < 0) {
            return a + "|" + b;
        }
        return b + "|" + a;
    }

    private static double horizontalDistance(Location a, Location b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
