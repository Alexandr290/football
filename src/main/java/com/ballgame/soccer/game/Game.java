package com.ballgame.soccer.game;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.arena.BoundaryExit;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.crowd.CrowdAudio;
import com.ballgame.soccer.event.BallKickEvent;
import com.ballgame.soccer.event.GoalScoredEvent;
import com.ballgame.soccer.event.MatchStateChangeEvent;
import com.ballgame.soccer.mechanics.Mechanics;
import com.ballgame.soccer.scoreboard.MatchScoreboard;
import com.ballgame.soccer.replay.GoalReplay;
import com.ballgame.soccer.replay.ReplayAnimationTracker;
import com.ballgame.soccer.replay.ReplayRecorder;
import com.ballgame.soccer.util.ActionBar;
import com.ballgame.soccer.util.MatchBroadcastHud;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A single soccer match. Owns its participants, the ball, the clock, the
 * scoreboard and the full state machine from lobby countdown through full time
 * (including half time, golden-goal extra time and penalty shootouts).
 *
 * <p>{@link #tick()} is driven once per server tick by the {@link GameManager}.</p>
 */
public final class Game {

    private final SoccerPlugin plugin;
    private final SoccerSettings settings;
    private final Arena arena;

    private GameState state = GameState.WAITING;
    private GameState resumeState = GameState.PLAYING;

    private final Map<UUID, SoccerPlayer> players = new LinkedHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new LinkedHashMap<>();

    private Ball ball;
    private MatchScoreboard scoreboard;
    private MatchBroadcastHud broadcastHud;

    private int redScore;
    private int blueScore;

    private int currentHalf = 1;
    private int secondsRemaining;
    private int countdown;
    private int tickInSecond;
    private long matchTick;

    private boolean celebrating;
    private int celebrationTicks;

    private long redPossessionTicks;
    private long bluePossessionTicks;

    private boolean ended;

    /** Team awarded the throw-in (opposite of last touch). */
    private TeamSide throwInTeam;
    /** Match phase to return to after the throw-in is taken. */
    private GameState resumeAfterThrowIn = GameState.PLAYING;

    private ReplayRecorder replayRecorder;
    private ReplayAnimationTracker replayAnimations;
    private GoalReplay goalReplay;
    private GameState stateBeforeReplay;

    private CrowdAudio crowd;

    private String championshipId;
    private String fixtureId;
    private String matchRedName;
    private String matchBlueName;

    /** Goal details held until replay finishes. */
    private TeamSide pendingCelebrationTeam;
    private SoccerPlayer pendingScorer;
    private SoccerPlayer pendingAssist;
    private boolean pendingOwnGoal;

    public Game(SoccerPlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.settings = plugin.settings();
        this.arena = arena;
        if (settings.scoreboardEnabled) {
            this.scoreboard = new MatchScoreboard(settings.scoreboardTitle);
        }
    }

    // ==================================================================
    // Membership
    // ==================================================================

    public boolean addPlayer(Player player) {
        return addPlayer(player, null);
    }

    public boolean addPlayer(Player player, TeamSide forceTeam) {
        if (players.containsKey(player.getUniqueId())) {
            return false;
        }
        if (isFull()) {
            return false;
        }
        TeamSide team = forceTeam != null ? forceTeam : pickBalancedTeam();
        SoccerPlayer sp = new SoccerPlayer(player, team, settings.maxStamina);
        snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        players.put(player.getUniqueId(), sp);

        preparePlayer(player, sp);
        Location lobby = arena.getLobby() != null ? arena.getLobby() : arena.getTeamSpawn(team);
        if (lobby != null) {
            player.teleport(lobby);
        }
        if (scoreboard != null) {
            scoreboard.apply(player);
        }
        if (broadcastHud != null) {
            broadcastHud.attach(player);
        }
        plugin.messages().send(player, "joined-queue",
                "arena", arena.getName(), "team", teamName(team));
        broadcast(plugin.messages().format("player-joined",
                "team_color", teamChat(team),
                "player", player.getName(),
                "count", String.valueOf(countTeam(team))));

        if (state == GameState.WAITING && hasEnoughPlayers()) {
            startCountdown();
        }
        return true;
    }

    public void removePlayer(Player player, boolean restore) {
        SoccerPlayer sp = players.remove(player.getUniqueId());
        if (sp == null) {
            return;
        }
        if (scoreboard != null) {
            scoreboard.clear(player);
        }
        if (broadcastHud != null) {
            broadcastHud.detach(player);
        }
        resetPlayerVisuals(player);
        resetParticipantMovement(player);
        PlayerSnapshot snapshot = snapshots.remove(player.getUniqueId());
        if (restore && snapshot != null) {
            snapshot.restore(player);
        }
        broadcast(plugin.messages().format("player-left", "player", player.getName()));

        // If the match is live and a team becomes empty, end it.
        if (state.isLive() || state == GameState.KICKOFF || state == GameState.HALFTIME
                || state == GameState.THROW_IN) {
            if (countTeam(TeamSide.RED) == 0 || countTeam(TeamSide.BLUE) == 0) {
                finishMatch();
            }
        } else if (state == GameState.COUNTDOWN && !hasEnoughPlayers()) {
            // Not enough players anymore; back to waiting.
            setState(GameState.WAITING);
            broadcast(plugin.messages().raw("countdown-cancelled"));
        }
    }

    private void preparePlayer(Player player, SoccerPlayer sp) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(Math.max(1.0, player.getMaxHealth()));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExp(0f);
        player.setLevel(0);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFireTicks(0);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        equipTeamColors(player, sp.getTeam());
    }

    private void equipTeamColors(Player player, TeamSide team) {
        Color color = team == TeamSide.RED ? Color.RED : Color.BLUE;
        player.getInventory().setHelmet(dyed(Material.LEATHER_HELMET, color));
        player.getInventory().setChestplate(dyed(Material.LEATHER_CHESTPLATE, color));
        player.getInventory().setLeggings(dyed(Material.LEATHER_LEGGINGS, color));
        player.getInventory().setBoots(dyed(Material.LEATHER_BOOTS, color));
    }

    private ItemStack dyed(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void resetPlayerVisuals(Player player) {
        player.setExp(0f);
        player.setLevel(0);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    private TeamSide pickBalancedTeam() {
        int red = countTeam(TeamSide.RED);
        int blue = countTeam(TeamSide.BLUE);
        if (red == blue) {
            return Math.random() < 0.5 ? TeamSide.RED : TeamSide.BLUE;
        }
        return red < blue ? TeamSide.RED : TeamSide.BLUE;
    }

    // ==================================================================
    // State machine
    // ==================================================================

    private void setState(GameState newState) {
        if (state == newState) {
            return;
        }
        GameState old = state;
        state = newState;
        Bukkit.getPluginManager().callEvent(new MatchStateChangeEvent(this, old, newState));
    }

    public void startCountdown() {
        setState(GameState.COUNTDOWN);
        countdown = settings.lobbyCountdown;
        tickInSecond = 0;
        broadcast(plugin.messages().raw("match-starting-soon"));
        if (crowd != null) {
            crowd.onMatchAnticipation();
        }
    }

    private void beginMatch() {
        currentHalf = 1;
        redScore = 0;
        blueScore = 0;
        redPossessionTicks = 0;
        bluePossessionTicks = 0;

        if (ball == null) {
            ball = new Ball(plugin);
        }
        replayRecorder = new ReplayRecorder(settings.replayBufferSeconds * 20);
        replayAnimations = new ReplayAnimationTracker();
        crowd = new CrowdAudio(plugin, this);
        ball.spawn(ballSpawnLocation());
        if (settings.broadcastHud) {
            broadcastHud = new MatchBroadcastHud(plugin);
            forEachPlayer(broadcastHud::attach);
        }
        startHalf();
    }

    /**
     * The ball's kickoff position, lifted slightly above the configured spawn so
     * the ball rests on the pitch surface rather than embedded in the floor.
     */
    private Location ballSpawnLocation() {
        Location base = arena.getBallSpawn();
        if (base == null) {
            return null;
        }
        return base.clone().add(0, 0.6, 0);
    }

    private void startHalf() {
        secondsRemaining = Math.max(10, settings.matchDuration / settings.halves);
        startKickoff(GameState.PLAYING);
    }

    private void startKickoff(GameState resume) {
        this.resumeState = resume;
        setState(GameState.KICKOFF);
        countdown = settings.kickoffCountdown;
        tickInSecond = 0;
        positionForKickoff();
    }

    private void positionForKickoff() {
        if (ball == null) {
            ball = new Ball(plugin);
            ball.spawn(ballSpawnLocation());
        }
        ball.teleport(ballSpawnLocation());
        ball.setFrozen(true);
        ball.clearTouchHistory();
        celebrating = false;
        for (SoccerPlayer sp : players.values()) {
            Player p = sp.getBukkitPlayer();
            if (p == null) {
                continue;
            }
            Location spawn = arena.getTeamSpawn(sp.getTeam());
            if (spawn != null) {
                p.teleport(spawn);
            }
            resetParticipantMovement(p);
            sp.setStamina(settings.maxStamina);
        }
    }

    private void onKickoffEnd() {
        setState(resumeState);
        if (ball != null) {
            ball.setFrozen(false);
        }
        broadcast(plugin.messages().raw("kickoff"));
        forEachPlayer(p -> {
            p.sendTitle(Text.color("&a&lKICK OFF"), "", 0, 25, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        });
        if (crowd != null) {
            crowd.onKickoff();
        }
    }

    private void onSecond() {
        switch (state) {
            case COUNTDOWN:
                countdown--;
                if (countdown <= 5 && countdown > 0) {
                    plugin.messages().sendAll(participants(), "countdown", "seconds", String.valueOf(countdown));
                    forEachPlayer(p -> {
                        p.sendTitle(Text.color("&e" + countdown),
                                Text.color(plugin.messages().raw("countdown-get-ready")), 0, 22, 5);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    });
                }
                if (countdown <= 0) {
                    beginMatch();
                }
                break;
            case KICKOFF:
                countdown--;
                if (countdown > 0) {
                    forEachPlayer(p -> {
                        p.sendTitle(Text.color("&c" + countdown), "", 0, 22, 5);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    });
                } else {
                    onKickoffEnd();
                }
                break;
            case PLAYING:
            case EXTRA_TIME:
                secondsRemaining--;
                if (secondsRemaining == 60 || secondsRemaining == 30 || secondsRemaining == 10) {
                    broadcast(plugin.messages().format("time-remaining",
                            "time", Text.formatTime(secondsRemaining)));
                }
                if (secondsRemaining <= 0) {
                    onPhaseTimeUp();
                }
                break;
            case HALFTIME:
                countdown--;
                if (countdown <= 0) {
                    currentHalf++;
                    startHalf();
                }
                break;
            default:
                break;
        }
    }

    private void onPhaseTimeUp() {
        if (state == GameState.PLAYING) {
            if (currentHalf < settings.halves) {
                setState(GameState.HALFTIME);
                countdown = settings.halftimeBreak;
                if (ball != null) {
                    ball.setFrozen(true);
                }
                broadcast(plugin.messages().format("halftime",
                        "red", String.valueOf(redScore), "blue", String.valueOf(blueScore)));
                forEachPlayer(p -> p.sendTitle(Text.color("&e&lHALF TIME"),
                        Text.color("&f" + redScore + " - " + blueScore), 5, 40, 10));
            } else {
                endRegulation();
            }
        } else if (state == GameState.EXTRA_TIME) {
            // Golden goal time elapsed without a winner.
            if (settings.penaltyShootout) {
                startPenalties();
            } else {
                finishMatch();
            }
        }
    }

    private void endRegulation() {
        if (redScore == blueScore && settings.goldenGoal) {
            secondsRemaining = settings.goldenGoalDuration;
            broadcast(plugin.messages().raw("golden-goal"));
            forEachPlayer(p -> p.sendTitle(Text.color("&6&lGOLDEN GOAL"),
                    Text.color("&7Next goal wins!"), 5, 40, 10));
            startKickoff(GameState.EXTRA_TIME);
        } else {
            finishMatch();
        }
    }

    /**
     * A deliberately lightweight penalty shootout: simply nudges the match to a
     * decisive end by awarding the team with greater possession. Hook the
     * {@link MatchStateChangeEvent} to implement a fuller interactive shootout.
     */
    private void startPenalties() {
        setState(GameState.PENALTIES);
        broadcast(plugin.messages().raw("penalty-shootout"));
        if (redPossessionTicks >= bluePossessionTicks) {
            redScore++;
        } else {
            blueScore++;
        }
        finishMatch();
    }

    private void finishMatch() {
        if (ended) {
            return;
        }
        ended = true;
        if (broadcastHud != null) {
            broadcastHud.destroy();
            broadcastHud = null;
        }
        if (goalReplay != null) {
            goalReplay.cancel();
            goalReplay = null;
        }
        celebrating = false;
        setState(GameState.ENDED);
        forEachPlayer(this::resetParticipantMovement);
        scheduleMovementReset();
        if (crowd != null) {
            crowd.onFullTime();
        }
        if (ball != null) {
            ball.setFrozen(true);
        }

        TeamSide winner = null;
        if (redScore > blueScore) {
            winner = TeamSide.RED;
        } else if (blueScore > redScore) {
            winner = TeamSide.BLUE;
        }

        broadcast(plugin.messages().format("match-end",
                "red", String.valueOf(redScore), "blue", String.valueOf(blueScore)));
        if (winner != null) {
            broadcast(plugin.messages().format("winner", "team", teamName(winner)));
            celebrateWinner(winner);
        } else {
            broadcast(plugin.messages().raw("draw"));
        }
        recordMatchStats(winner);
        if (championshipId != null) {
            plugin.championships().onMatchFinished(this, winner);
        }

        final TeamSide finalWinner = winner;
        forEachPlayer(p -> {
            String title = finalWinner == null
                    ? plugin.messages().raw("title-draw")
                    : plugin.messages().format("title-winner",
                    "team", teamChat(finalWinner) + teamName(finalWinner).toUpperCase());
            p.sendTitle(Text.color(title),
                    Text.color("&f" + redScore + " - " + blueScore), 10, 70, 20);
        });

        // Tear the match down a few seconds later.
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.games().endGame(this), 20L * 6);
    }

    public void forceEnd() {
        if (!ended) {
            finishMatch();
        }
    }

    /**
     * Final cleanup: restore every player and remove the ball. Called by the
     * {@link GameManager}.
     */
    public void cleanup() {
        if (broadcastHud != null) {
            broadcastHud.destroy();
            broadcastHud = null;
        }
        if (ball != null) {
            ball.remove();
        }
        for (UUID id : new ArrayList<>(players.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                removePlayer(p, true);
            } else {
                players.remove(id);
                snapshots.remove(id);
            }
        }
    }

    // ==================================================================
    // Per-tick simulation
    // ==================================================================

    public void tick() {
        matchTick++;

        if (goalReplay != null) {
            if (goalReplay.tick()) {
                onReplayFinished();
            }
            updateReplayHud();
            if (crowd != null) {
                crowd.tick();
            }
            if (scoreboard != null && matchTick % 5 == 0) {
                refreshScoreboard();
            }
            return;
        }

        if (ball != null && ball.isSpawned()) {
            ball.tick();
        }

        if (settings.replayEnabled && state.isLive() && !celebrating && replayRecorder != null) {
            if (replayAnimations != null) {
                replayAnimations.tick();
            }
            replayRecorder.capture(this);
        }

        if (state == GameState.PLAYING || state == GameState.EXTRA_TIME) {
            if (!celebrating) {
                processInteractions();
                checkGoal();
                checkBoundary();
            }
            trackPossession();
        }

        if (celebrating) {
            celebrationTicks--;
            if (celebrationTicks <= 0) {
                celebrating = false;
                if (!ended && (state == GameState.PLAYING || state == GameState.EXTRA_TIME)) {
                    startKickoff(state == GameState.EXTRA_TIME ? GameState.EXTRA_TIME : GameState.PLAYING);
                }
            }
        }

        updateHud();
        updateBroadcastHud();

        if (++tickInSecond >= 20) {
            tickInSecond = 0;
            onSecond();
        }

        if (scoreboard != null && matchTick % 5 == 0) {
            refreshScoreboard();
        }

        if (crowd != null) {
            crowd.tick();
        }
    }

    private void trackPossession() {
        if (ball == null) {
            return;
        }
        TeamSide last = ball.getLastTouchTeam();
        if (last == TeamSide.RED) {
            redPossessionTicks++;
        } else if (last == TeamSide.BLUE) {
            bluePossessionTicks++;
        }
    }

    private void processInteractions() {
        if (ball == null || ball.getLocation() == null) {
            return;
        }
        Location ballLoc = ball.getLocation();
        double speed = ball.getSpeed();

        for (SoccerPlayer sp : players.values()) {
            Player p = sp.getBukkitPlayer();
            if (p == null || !p.isOnline()) {
                continue;
            }
            handleStamina(sp, p);

            Location feet = p.getLocation();
            double dx = feet.getX() - ballLoc.getX();
            double dz = feet.getZ() - ballLoc.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double dy = Math.abs((feet.getY() + 1.0) - ballLoc.getY());

            if (horiz > settings.collisionRadius + 0.8 || dy > 1.8) {
                continue;
            }

            boolean keeperZone = isInOwnGoalBox(sp.getTeam(), feet);

            if (speed > 0.6) {
                // The ball is moving fast: treat contact as a deflection / save.
                deflect(sp, p, ballLoc, keeperZone);
            } else {
                // Slow ball at the player's feet: dribble it along.
                Vector v = p.getVelocity();
                if (v.getX() * v.getX() + v.getZ() * v.getZ() > 0.0025) {
                    Vector nudge = Mechanics.dribbleNudge(p, settings);
                    ball.recordTouch(p, sp.getTeam());
                    ball.applyImpulse(nudge, null);
                    sp.addTouch();
                }
            }
        }
    }

    private void deflect(SoccerPlayer sp, Player p, Location ballLoc, boolean keeperZone) {
        Vector away = ball.getLocation().toVector().subtract(p.getLocation().toVector());
        away.setY(Math.max(0.1, away.getY()));
        if (away.lengthSquared() < 1.0e-6) {
            away = p.getLocation().getDirection();
        }
        away.normalize();
        double power = keeperZone ? 0.35 : (0.25 + ball.getSpeed() * 0.4);
        ball.recordTouch(p, sp.getTeam());
        ball.applyImpulse(away.multiply(power), null);
        sp.addTouch();
        if (keeperZone) {
            sp.addSave();
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.7f);
            if (crowd != null) {
                crowd.onSave(sp.getTeam());
            }
        }
        p.getWorld().spawnParticle(Particle.CRIT, ballLoc, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private void handleStamina(SoccerPlayer sp, Player p) {
        if (!settings.staminaEnabled) {
            return;
        }
        if (p.isSprinting()) {
            sp.drainStamina(settings.sprintDrain, 0);
            if (sp.getStamina() <= 0.5) {
                p.setSprinting(false);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false));
            }
        } else if (sp.isCharging()) {
            sp.drainStamina(settings.chargeDrain, 0);
        } else {
            sp.regenStamina(settings.staminaRegen, settings.maxStamina);
        }
    }

    private boolean isInOwnGoalBox(TeamSide team, Location loc) {
        return arena.getGoal(team) != null && arena.getGoal(team).contains(loc);
    }

    // ==================================================================
    // Goal & boundary detection
    // ==================================================================

    private void checkGoal() {
        if (celebrating || goalReplay != null || ended || ball == null) {
            return;
        }
        Location curr = ball.getLocation();
        if (curr == null) {
            return;
        }

        Location prev = ball.getPreviousTickLocation();
        TeamSide goalBox = arena.goalContaining(curr);
        if (goalBox == null && prev != null) {
            goalBox = arena.goalContainingAlongPath(prev, curr);
        }
        if (goalBox == null) {
            goalBox = arena.goalAtGoalLineExit(curr);
        }
        if (goalBox == null) {
            return;
        }
        scoreGoal(goalBox.opposite());
    }

    private void scoreGoal(TeamSide scoringTeam) {
        SoccerPlayer scorer = null;
        SoccerPlayer assist = null;
        boolean ownGoal = false;

        UUID lastId = ball.getLastTouchId();
        TeamSide lastTeam = ball.getLastTouchTeam();
        if (lastId != null) {
            SoccerPlayer last = players.get(lastId);
            if (lastTeam == scoringTeam) {
                scorer = last;
                UUID prevId = ball.getPrevTouchId();
                if (prevId != null && !prevId.equals(lastId)
                        && ball.getPrevTouchTeam() == scoringTeam) {
                    assist = players.get(prevId);
                }
            } else {
                // The last toucher was on the conceding team: own goal.
                ownGoal = true;
                scorer = last;
            }
        }

        if (scoringTeam == TeamSide.RED) {
            redScore++;
        } else {
            blueScore++;
        }

        // Stats
        if (scorer != null) {
            if (ownGoal) {
                scorer.addOwnGoal();
            } else {
                scorer.addGoal();
                if (assist != null) {
                    assist.addAssist();
                }
            }
        }

        Bukkit.getPluginManager().callEvent(
                new GoalScoredEvent(this, scoringTeam, scorer, assist, ownGoal));

        showGoalMoment(scoringTeam);
        if (crowd != null) {
            crowd.onGoal(scoringTeam, ownGoal);
        }

        if (settings.replayEnabled && replayRecorder != null && replayRecorder.hasEnoughFrames()) {
            pendingCelebrationTeam = scoringTeam;
            pendingScorer = scorer;
            pendingAssist = assist;
            pendingOwnGoal = ownGoal;
            stateBeforeReplay = state;
            goalReplay = new GoalReplay(
                    plugin, this,
                    replayRecorder.extractClip(settings.replayClipSeconds),
                    scoringTeam,
                    scorer != null ? scorer.getUuid() : null);
            setState(GameState.REPLAY);
            goalReplay.begin();
        } else {
            announceGoalDetails(scoringTeam, scorer, assist, ownGoal);
            beginCelebration(scoringTeam);
            checkWinAfterGoal();
        }
    }

    private void onReplayFinished() {
        goalReplay = null;
        if (stateBeforeReplay != null) {
            setState(stateBeforeReplay);
            stateBeforeReplay = null;
        }
        forEachPlayer(this::resetParticipantMovement);
        scheduleMovementReset();
        announceGoalDetails(
                pendingCelebrationTeam, pendingScorer, pendingAssist, pendingOwnGoal);
        beginCelebration(pendingCelebrationTeam);
        checkWinAfterGoal();
    }

    private void checkWinAfterGoal() {
        if (state == GameState.EXTRA_TIME) {
            finishMatch();
            return;
        }
        if (settings.mercyRuleLead > 0
                && Math.abs(redScore - blueScore) >= settings.mercyRuleLead) {
            finishMatch();
        }
    }

    /** Immediate GOAL title and sound — shown before the replay. */
    private void showGoalMoment(TeamSide team) {
        String red = String.valueOf(redScore);
        String blue = String.valueOf(blueScore);
        forEachPlayer(p -> {
            p.sendTitle(Text.color(teamChat(team) + plugin.messages().raw("title-goal")),
                    Text.color("&f" + red + " - " + blue), 5, 25, 8);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1f);
        });
    }

    private void announceGoalDetails(TeamSide team, SoccerPlayer scorer, SoccerPlayer assist, boolean ownGoal) {
        String red = String.valueOf(redScore);
        String blue = String.valueOf(blueScore);
        String scorerName = scorer != null ? scorer.getName()
                : plugin.messages().raw("scorer-unknown");
        if (ownGoal) {
            broadcast(plugin.messages().format("own-goal",
                    "scorer", scorerName, "red", red, "blue", blue));
        } else if (assist != null) {
            broadcast(plugin.messages().format("goal-with-assist",
                    "team", teamName(team), "scorer", scorerName,
                    "assist", assist.getName(), "red", red, "blue", blue));
        } else {
            broadcast(plugin.messages().format("goal",
                    "team", teamName(team), "scorer", scorerName,
                    "red", red, "blue", blue));
        }
    }

    private void beginCelebration(TeamSide team) {
        celebrating = true;
        celebrationTicks = 20 * 4;
        if (ball != null) {
            ball.setFrozen(true);
        }
        Location center = arena.getBallSpawn();
        if (center != null) {
            launchFirework(center, team);
        }
    }

    private void checkBoundary() {
        if (celebrating || goalReplay != null || ended || ball == null || ball.getLocation() == null || !settings.resetWhenOut) {
            return;
        }
        Location loc = ball.getLocation();
        BoundaryExit exit = arena.classifyBoundaryExit(loc);
        switch (exit) {
            case IN_PLAY:
                return;
            case TOUCHLINE:
                if (settings.throwInEnabled) {
                    startThrowIn(loc);
                } else {
                    resetBallToCenter(plugin.messages().raw("touchline-out"));
                }
                break;
            case GOAL_LINE:
                if (!tryScoreFromBoundary(loc)) {
                    resetBallToCenter(plugin.messages().raw("goal-line-out"));
                }
                break;
            case BELOW_PITCH:
                resetBallToCenter(plugin.messages().raw("below-pitch-out"));
                break;
            default:
                break;
        }
    }

    /** Last-chance goal check when the ball has crossed the goal line. */
    private boolean tryScoreFromBoundary(Location loc) {
        if (celebrating || ended || ball == null) {
            return false;
        }
        TeamSide goalBox = arena.goalContaining(loc);
        if (goalBox == null) {
            goalBox = arena.goalAtGoalLineExit(loc);
        }
        if (goalBox == null) {
            return false;
        }
        scoreGoal(goalBox.opposite());
        return true;
    }

    private void startThrowIn(Location exitLoc) {
        TeamSide lastTouch = ball.getLastTouchTeam();
        throwInTeam = lastTouch == null ? TeamSide.RED : lastTouch.opposite();
        resumeAfterThrowIn = state;

        Location spot = arena.computeThrowInSpot(exitLoc);
        ball.teleport(spot);
        ball.setFrozen(true);
        ball.clearTouchHistory();

        setState(GameState.THROW_IN);

        broadcast(plugin.messages().format("throw-in", "team", teamName(throwInTeam)));

        forEachPlayer(p -> {
            SoccerPlayer sp = players.get(p.getUniqueId());
            boolean taker = sp != null && sp.getTeam() == throwInTeam;
            if (taker) {
                p.sendTitle(
                        Text.color(plugin.messages().raw("throw-in-title")),
                        Text.color(plugin.messages().format("throw-in-subtitle-taker",
                                "team", teamName(throwInTeam))),
                        5, 50, 15);
            } else {
                p.sendTitle(
                        Text.color(plugin.messages().raw("throw-in-title")),
                        Text.color(plugin.messages().format("throw-in-subtitle-wait",
                                "team", teamName(throwInTeam))),
                        5, 50, 15);
            }
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
        });
    }

    private void endThrowIn() {
        throwInTeam = null;
        setState(resumeAfterThrowIn);
        forEachPlayer(p -> p.sendTitle(Text.color(plugin.messages().raw("play-on")), "", 0, 20, 10));
    }

    private void resetBallToCenter(String message) {
        ball.teleport(ballSpawnLocation());
        ball.clearTouchHistory();
        broadcast(message);
        forEachPlayer(p -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f));
    }

    // ==================================================================
    // Player actions (invoked by listeners)
    // ==================================================================

    public void shoot(Player player) {
        if (state == GameState.THROW_IN) {
            performThrowIn(player, BallKickEvent.KickType.SHOT);
            return;
        }
        kick(player, BallKickEvent.KickType.SHOT);
    }

    public void pass(Player player) {
        if (state == GameState.THROW_IN) {
            performThrowIn(player, BallKickEvent.KickType.THROW_IN);
            return;
        }
        kick(player, BallKickEvent.KickType.PASS);
    }

    private void performThrowIn(Player player, BallKickEvent.KickType type) {
        if (state != GameState.THROW_IN || throwInTeam == null) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || ball == null) {
            return;
        }
        if (sp.getTeam() != throwInTeam) {
            plugin.messages().send(player, "throw-in-wrong-team",
                    "team", teamName(throwInTeam));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        if (!reachable(player, settings.kickReach * 1.4)) {
            return;
        }

        BallKickEvent.KickType mechanicsType = type == BallKickEvent.KickType.SHOT
                ? BallKickEvent.KickType.SHOT
                : BallKickEvent.KickType.THROW_IN;
        Mechanics.Kick kick = Mechanics.compute(player, settings, mechanicsType, 0);

        BallKickEvent event = new BallKickEvent(player, ball, mechanicsType, kick.impulse, kick.spin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        ball.recordTouch(player, sp.getTeam());
        ball.applyImpulse(event.getImpulse(), event.getSpin());
        sp.addTouch();
        sp.addPass();

        endThrowIn();

        Location loc = ball.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private void kick(Player player, BallKickEvent.KickType type) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || ball == null || !reachable(player)) {
            return;
        }
        double charge = sp.chargeFraction(matchTick, settings.chargeTime * 20.0);
        Mechanics.Kick kick = Mechanics.compute(player, settings, type, charge);

        BallKickEvent event = new BallKickEvent(player, ball, type, kick.impulse, kick.spin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        ball.recordTouch(player, sp.getTeam());
        ball.applyImpulse(event.getImpulse(), event.getSpin());
        markReplayKickAnimation(player.getUniqueId(), type);
        sp.addTouch();
        if (type == BallKickEvent.KickType.SHOT) {
            sp.addShot();
            if (settings.staminaEnabled) {
                sp.drainStamina(settings.chargeDrain * 4, 0);
            }
        } else {
            sp.addPass();
        }
        sp.stopCharging();
        player.setExp(0f);

        Location loc = ball.getLocation();
        float pitch = (float) (1.0 + Math.min(1.0, charge));
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, pitch);
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 8, 0.1, 0.1, 0.1, 0.05);
    }

    public void attemptTackle(Player player) {
        if (!settings.tackleEnabled) {
            return;
        }
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || !sp.canTackle(matchTick)) {
            return;
        }
        if (settings.staminaEnabled && sp.getStamina() < settings.tackleDrain) {
            return;
        }
        sp.setTackleCooldown(matchTick, (long) (settings.tackleCooldown * 20));
        sp.addTackle();
        if (settings.staminaEnabled) {
            sp.drainStamina(settings.tackleDrain, 0);
        }

        Vector dir = player.getLocation().getDirection();
        dir.setY(0).normalize().multiply(0.9).setY(0.18);
        player.setVelocity(dir);
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                player.getLocation(), 12, 0.3, 0.05, 0.3, 0.01);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);

        if (ball != null && reachable(player, settings.kickReach * 1.3)) {
            Mechanics.Kick kick = Mechanics.compute(player, settings, BallKickEvent.KickType.TACKLE, 0);
            BallKickEvent event = new BallKickEvent(player, ball,
                    BallKickEvent.KickType.TACKLE, kick.impulse, kick.spin);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                ball.recordTouch(player, sp.getTeam());
                ball.applyImpulse(event.getImpulse(), event.getSpin());
            }
        }
    }

    public void setCharging(Player player, boolean charging) {
        if (state == GameState.THROW_IN) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null) {
            return;
        }
        if (charging) {
            if (player.isSprinting()) {
                // sprint + sneak = slide tackle instead of charging
                attemptTackle(player);
            } else {
                sp.startCharging(matchTick);
            }
        } else {
            sp.stopCharging();
        }
    }

    private boolean reachable(Player player) {
        return reachable(player, settings.kickReach);
    }

    private boolean reachable(Player player, double reach) {
        if (ball == null || ball.getLocation() == null) {
            return false;
        }
        Location ballLoc = ball.getLocation();
        Location eye = player.getEyeLocation();
        double horiz = Math.hypot(eye.getX() - ballLoc.getX(), eye.getZ() - ballLoc.getZ());
        double vert = Math.abs(player.getLocation().getY() - ballLoc.getY());
        if (horiz > reach || vert > 2.5) {
            return false;
        }
        // Must be roughly facing the ball (or be standing right on top of it).
        if (horiz < 1.2) {
            return true;
        }
        Vector look = eye.getDirection().setY(0);
        Vector toBall = ballLoc.toVector().subtract(eye.toVector()).setY(0);
        if (look.lengthSquared() < 1e-6 || toBall.lengthSquared() < 1e-6) {
            return true;
        }
        return look.normalize().dot(toBall.normalize()) > 0.3;
    }

    // ==================================================================
    // HUD & scoreboard
    // ==================================================================

    private void updateReplayHud() {
        if (!settings.actionBarHud) {
            return;
        }
        String label = plugin.messages().raw("title-replay");
        forEachPlayer(p -> ActionBar.send(p, Text.color(label)));
    }

    private void updateBroadcastHud() {
        if (!settings.broadcastHud || broadcastHud == null) {
            return;
        }
        if (celebrating || goalReplay != null) {
            broadcastHud.setVisible(false);
            return;
        }
        boolean halftime = state == GameState.HALFTIME;
        boolean liveClock = state == GameState.PLAYING || state == GameState.EXTRA_TIME
                || state == GameState.THROW_IN || halftime;
        if (!liveClock) {
            broadcastHud.setVisible(false);
            return;
        }

        TeamSide possession = null;
        if (ball != null) {
            possession = ball.getLastTouchTeam();
        }
        broadcastHud.setVisible(true);
        broadcastHud.update(
                MatchBroadcastHud.tvLine(
                        teamChat(TeamSide.RED), teamName(TeamSide.RED), redScore,
                        blueScore, teamName(TeamSide.BLUE), teamChat(TeamSide.BLUE),
                        Text.formatTime(elapsedMatchSeconds()),
                        halftime,
                        state == GameState.EXTRA_TIME,
                        possession),
                broadcastPhaseProgress(),
                state == GameState.EXTRA_TIME);
    }

    private double broadcastPhaseProgress() {
        int halfDuration = halfDurationSeconds();
        switch (state) {
            case PLAYING:
            case THROW_IN:
                return Math.min(1.0, (double) Math.max(0, halfDuration - secondsRemaining) / halfDuration);
            case EXTRA_TIME:
                return Math.min(1.0, (double) Math.max(0, settings.goldenGoalDuration - secondsRemaining)
                        / Math.max(1, settings.goldenGoalDuration));
            case HALFTIME:
                return 1.0;
            default:
                return 0.0;
        }
    }

    private int halfDurationSeconds() {
        return Math.max(10, settings.matchDuration / Math.max(1, settings.halves));
    }

    /** Elapsed match time (ascending), like a TV broadcast clock. */
    private int elapsedMatchSeconds() {
        int halfDuration = halfDurationSeconds();
        switch (state) {
            case PLAYING:
            case THROW_IN:
                return (currentHalf - 1) * halfDuration
                        + Math.max(0, halfDuration - secondsRemaining);
            case EXTRA_TIME:
                return settings.matchDuration
                        + Math.max(0, settings.goldenGoalDuration - secondsRemaining);
            case HALFTIME:
                return currentHalf * halfDuration;
            default:
                return 0;
        }
    }

    private void updateHud() {
        for (SoccerPlayer sp : players.values()) {
            Player p = sp.getBukkitPlayer();
            if (p == null) {
                continue;
            }
            double charge = sp.chargeFraction(matchTick, settings.chargeTime * 20.0);
            p.setExp((float) charge);
            p.setLevel((int) Math.round(sp.getStamina()));

            if (settings.actionBarHud) {
                String staminaBar = ActionBar.bar(
                        sp.getStamina() / settings.maxStamina, 10, "&a", "&7");
                String chargeBar = ActionBar.bar(charge, 10, "&c", "&8");
                ActionBar.send(p, plugin.messages().raw("hud-stamina") + staminaBar
                        + plugin.messages().raw("hud-power") + chargeBar);
            }
        }
    }

    private void refreshScoreboard() {
        if (scoreboard == null) {
            return;
        }
        Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("map", arena.getName());
        placeholders.put("state", plugin.messages().stateDisplay(state));
        placeholders.put("red_score", String.valueOf(redScore));
        placeholders.put("blue_score", String.valueOf(blueScore));
        placeholders.put("red_name", teamName(TeamSide.RED));
        placeholders.put("blue_name", teamName(TeamSide.BLUE));
        placeholders.put("time", clockText());
        placeholders.put("possession", possessionText());
        placeholders.put("players", String.valueOf(players.size()));
        scoreboard.update(settings.scoreboardTitle, settings.scoreboardLines, placeholders);
    }

    private String clockText() {
        switch (state) {
            case PLAYING:
            case EXTRA_TIME:
            case THROW_IN:
                return Text.formatTime(elapsedMatchSeconds())
                        + (settings.halves > 1
                        ? plugin.messages().format("half-suffix", "half", String.valueOf(currentHalf))
                        : "");
            case HALFTIME:
                return Text.formatTime(elapsedMatchSeconds());
            case COUNTDOWN:
            case KICKOFF:
                return ":" + countdown;
            default:
                return plugin.messages().stateDisplay(state);
        }
    }

    private String possessionText() {
        long total = redPossessionTicks + bluePossessionTicks;
        if (total == 0) {
            return plugin.messages().raw("possession-even");
        }
        int redPct = (int) Math.round((double) redPossessionTicks / total * 100.0);
        return redPct + "% / " + (100 - redPct) + "%";
    }

    // ==================================================================
    // Stats & effects
    // ==================================================================

    private void recordMatchStats(TeamSide winner) {
        for (SoccerPlayer sp : players.values()) {
            var stats = plugin.stats().get(sp.getUuid(), sp.getName());
            stats.setName(sp.getName());
            stats.addGame();
            stats.addGoals(sp.getGoals());
            stats.addAssists(sp.getAssists());
            stats.addOwnGoals(sp.getOwnGoals());
            stats.addTackles(sp.getTackles());
            stats.addSaves(sp.getSaves());
            if (winner == null) {
                stats.addDraw();
            } else if (sp.getTeam() == winner) {
                stats.addWin();
            } else {
                stats.addLoss();
            }
            int conceded = sp.getTeam() == TeamSide.RED ? blueScore : redScore;
            if (conceded == 0) {
                stats.addCleanSheet();
            }
        }
        plugin.stats().save();
    }

    private void celebrateWinner(TeamSide team) {
        for (SoccerPlayer sp : players.values()) {
            if (sp.getTeam() != team) {
                continue;
            }
            Player p = sp.getBukkitPlayer();
            if (p != null) {
                launchFirework(p.getLocation(), team);
            }
        }
    }

    private void launchFirework(Location loc, TeamSide team) {
        Firework firework = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        Color color = team == TeamSide.RED ? Color.RED : Color.BLUE;
        meta.addEffect(FireworkEffect.builder()
                .withColor(color)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }

    // ==================================================================
    // Helpers / accessors
    // ==================================================================

    private void broadcast(String message) {
        String line = plugin.messages().prefix() + Text.color(message);
        forEachPlayer(p -> p.sendMessage(line));
    }

    private void forEachPlayer(Consumer<Player> action) {
        for (SoccerPlayer sp : players.values()) {
            Player p = sp.getBukkitPlayer();
            if (p != null && p.isOnline()) {
                action.accept(p);
            }
        }
    }

    /** Exposed for replay playback to iterate match participants. */
    public void forEachParticipant(Consumer<Player> action) {
        forEachPlayer(action);
    }

    public java.util.Collection<SoccerPlayer> getParticipants() {
        return players.values();
    }

    public long getMatchTick() {
        return matchTick;
    }

    public boolean isReplaying() {
        return goalReplay != null;
    }

    public boolean isCelebrating() {
        return celebrating;
    }

    public boolean blocksPlayerInput() {
        return celebrating || goalReplay != null;
    }

    /**
     * Clears flight/gravity side-effects from replay cameras so players can move
     * normally again.
     */
    public ReplayAnimationTracker getReplayAnimations() {
        return replayAnimations;
    }

    public void markReplayArmSwing(Player player) {
        if (replayAnimations != null && state.isLive() && !celebrating && goalReplay == null) {
            replayAnimations.markArmSwing(player.getUniqueId());
        }
    }

    private void markReplayKickAnimation(UUID playerId, BallKickEvent.KickType type) {
        if (replayAnimations == null) {
            return;
        }
        if (type == BallKickEvent.KickType.SHOT) {
            replayAnimations.markArmSwing(playerId);
        } else {
            replayAnimations.markInteract(playerId);
        }
    }

    public void resetParticipantMovement(Player player) {
        player.setGravity(true);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0f);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        if (players.containsKey(player.getUniqueId())) {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    /** Re-applies movement reset next tick to fix client-side desync after teleports. */
    public void scheduleMovementReset() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            forEachPlayer(this::resetParticipantMovement);
        }, 1L);
    }

    private List<Player> participants() {
        List<Player> list = new ArrayList<>();
        forEachPlayer(list::add);
        return list;
    }

    private int countTeam(TeamSide team) {
        int count = 0;
        for (SoccerPlayer sp : players.values()) {
            if (sp.getTeam() == team) {
                count++;
            }
        }
        return count;
    }

    private boolean hasEnoughPlayers() {
        return countTeam(TeamSide.RED) >= settings.minPlayersPerTeam
                && countTeam(TeamSide.BLUE) >= settings.minPlayersPerTeam;
    }

    public boolean isFull() {
        return players.size() >= settings.maxPlayersPerTeam * 2;
    }

    private String teamName(TeamSide team) {
        if (team == TeamSide.RED && matchRedName != null && !matchRedName.isBlank()) {
            return matchRedName;
        }
        if (team == TeamSide.BLUE && matchBlueName != null && !matchBlueName.isBlank()) {
            return matchBlueName;
        }
        return team == TeamSide.RED ? settings.redName : settings.blueName;
    }

    private String teamChat(TeamSide team) {
        return team == TeamSide.RED ? settings.redChatColor : settings.blueChatColor;
    }

    public boolean contains(UUID uuid) {
        return players.containsKey(uuid);
    }

    public SoccerPlayer getPlayer(UUID uuid) {
        return players.get(uuid);
    }

    public Arena getArena() {
        return arena;
    }

    public GameState getState() {
        return state;
    }

    public int getRedScore() {
        return redScore;
    }

    public int getBlueScore() {
        return blueScore;
    }

    public Ball getBall() {
        return ball;
    }

    public int playerCount() {
        return players.size();
    }

    public boolean isEnded() {
        return ended;
    }

    public void setChampionship(String championshipId, String fixtureId) {
        this.championshipId = championshipId;
        this.fixtureId = fixtureId;
    }

    public void setMatchTeamNames(String redName, String blueName) {
        this.matchRedName = redName;
        this.matchBlueName = blueName;
    }

    public String getChampionshipId() {
        return championshipId;
    }

    public String getFixtureId() {
        return fixtureId;
    }
}
