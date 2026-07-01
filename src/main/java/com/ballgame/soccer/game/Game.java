package com.ballgame.soccer.game;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.arena.BoundaryExit;
import com.ballgame.soccer.arena.OutLineCrossing;
import com.ballgame.soccer.arena.ThrowInGeometry;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.bot.BotAi;
import com.ballgame.soccer.bot.BotRole;
import com.ballgame.soccer.bot.MatchBot;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.crowd.CrowdAudio;
import com.ballgame.soccer.crowd.CrowdPackMode;
import com.ballgame.soccer.event.BallKickEvent;
import com.ballgame.soccer.event.GoalScoredEvent;
import com.ballgame.soccer.event.MatchStateChangeEvent;
import com.ballgame.soccer.event.OffsideEvent;
import com.ballgame.soccer.mechanics.Mechanics;
import com.ballgame.soccer.mechanics.OffsideChecker;
import com.ballgame.soccer.mechanics.OffsideSnapshot;
import com.ballgame.soccer.scoreboard.MatchScoreboard;
import com.ballgame.soccer.replay.GoalReplay;
import com.ballgame.soccer.replay.ReplayAnimationTracker;
import com.ballgame.soccer.replay.ReplayRecorder;
import com.ballgame.soccer.referee.RefereeController;
import com.ballgame.soccer.util.ActionBar;
import com.ballgame.soccer.util.Cuboid;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Map<UUID, MatchBot> bots = new LinkedHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new LinkedHashMap<>();

    private boolean soloMatch;
    private final java.util.EnumMap<TeamSide, BotDifficulty> botDifficulties =
            new java.util.EnumMap<>(TeamSide.class);

    private Ball ball;
    private MatchScoreboard scoreboard;
    private MatchBroadcastHud broadcastHud;
    /** Non-participants who receive the server-wide TV score / sidebar. */
    private final Set<UUID> serverViewers = new HashSet<>();

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
    private int cleanupTaskId = -1;
    private boolean livePlayStarted;
    private java.util.Set<UUID> lobbyReturnCandidates = java.util.Collections.emptySet();

    /** Team awarded the throw-in (opposite of last touch). */
    private TeamSide throwInTeam;
    /** Designated player or bot who must take the throw-in. */
    private UUID throwInTakerId;
    /** Cached throw-in spot for snap-back and taker placement. */
    private Location throwInSpot;
    private ThrowInGeometry throwInGeometry;
    private int throwInRestartPulse;
    /** Ball was thrown but play has not resumed until it enters the pitch. */
    private boolean throwInAwaitingFieldEntry;
    private int throwInAwaitingTicks;
    /** Ignore touchline outs briefly after a completed throw-in. */
    private int throwInBoundaryGrace;
    /** Match phase to return to after the throw-in is taken. */
    private GameState resumeAfterThrowIn = GameState.PLAYING;

    private static final int THROW_IN_BOUNDARY_GRACE_TICKS = 30;
    private static final int THROW_IN_RETAKES_AFTER_TICKS = 50;

    /** Cached pitch axis for offside line geometry. */
    private OffsideChecker.PitchAxis pitchAxis;
    /** Positions at the moment the ball was last played by a teammate. */
    private OffsideSnapshot pendingOffsideCheck;
    /** Team awarded the indirect free kick after offside. */
    private TeamSide freeKickTeam;
    /** Match phase to return to after the free kick is taken. */
    private GameState resumeAfterFreeKick = GameState.PLAYING;
    /** Designated player who must take the offside free kick. */
    private UUID freeKickTakerId;
    /** Cached restart spot for snap-back and taker placement. */
    private Location freeKickSpot;
    private int offsideRestartPulse;
    /** Next touch is exempt (throw-in, goal kick / center reset, kickoff). */
    private boolean offsideExemptTouch;

    private ReplayRecorder replayRecorder;
    private ReplayAnimationTracker replayAnimations;
    private GoalReplay goalReplay;
    private GameState stateBeforeReplay;

    private CrowdAudio crowd;
    private RefereeController referee;

    private String championshipId;
    private String fixtureId;
    private String matchRedName;
    private String matchBlueName;

    /** Sticky per-team chaser so only one bot hunts the ball and roles stay stable. */
    private final java.util.EnumMap<TeamSide, UUID> stickyBallChaserId = new java.util.EnumMap<>(TeamSide.class);
    private final java.util.EnumMap<TeamSide, Boolean> humanChasingBall = new java.util.EnumMap<>(TeamSide.class);
    private long ballChaserRefreshTick = -1;

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
        if (isTeamFull(team)) {
            return false;
        }
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
        attachServerViewer(player);
        plugin.messages().send(player, "joined-queue",
                "arena", arena.getName(), "team", teamName(team));
        promptCrowdPackIfNeeded(player);
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
        serverViewers.remove(player.getUniqueId());
        resetPlayerVisuals(player);
        resetParticipantMovement(player);
        PlayerSnapshot snapshot = snapshots.remove(player.getUniqueId());
        if (restore && snapshot != null) {
            snapshot.restore(player);
        }
        broadcast(plugin.messages().format("player-left", "player", player.getName()));

        if (state == GameState.OFFSIDE && freeKickTakerId != null
                && player.getUniqueId().equals(freeKickTakerId)) {
            freeKickTakerId = null;
            if (settings.offsideAutoTaker) {
                reassignFreeKickTaker(false);
            }
        }

        if (state == GameState.THROW_IN && throwInTakerId != null
                && player.getUniqueId().equals(throwInTakerId)) {
            throwInTakerId = null;
            if (settings.throwInAutoTaker) {
                reassignThrowInTaker(false);
            }
        }

        if (soloMatch && players.isEmpty()) {
            removeAllBots();
        }

        // If the match is live and a team becomes empty, end it.
        if (state.isLive() || state == GameState.KICKOFF || state == GameState.HALFTIME
                || state == GameState.THROW_IN || state == GameState.OFFSIDE
                || state == GameState.PENALTIES || state == GameState.REPLAY) {
            if (countTeam(TeamSide.RED) == 0 || countTeam(TeamSide.BLUE) == 0) {
                if (!livePlayStarted && state == GameState.KICKOFF) {
                    abortActiveMatchToLobby();
                    broadcast(plugin.messages().raw("countdown-cancelled"));
                    return;
                }
                finishMatch();
            }
        } else if (state == GameState.COUNTDOWN && !hasEnoughPlayers()) {
            abortActiveMatchToLobby();
            broadcast(plugin.messages().raw("countdown-cancelled"));
        }

        if (players.isEmpty()) {
            if (ended) {
                plugin.games().endGame(this);
            } else if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
                abortActiveMatchToLobby();
            }
            return;
        }
        plugin.games().syncServerViewers();
    }

    private void promptCrowdPackIfNeeded(Player player) {
        if (!settings.crowdEnabled || !settings.crowdRealSounds) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!players.containsKey(player.getUniqueId()) || !player.isOnline()) {
                return;
            }
            if (plugin.crowdPack().canPlayCustom(player)) {
                return;
            }
            if (settings.crowdPackPromptOnJoin) {
                plugin.crowdPack().offer(player);
            } else if (plugin.crowdPack().mode() == CrowdPackMode.MANUAL) {
                plugin.messages().send(player, "crowd-pack-manual-accept-hint");
            }
        }, 30L);
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

    /**
     * Tears down an in-progress match (ball, referee, bots, replay) and returns
     * the arena lobby to {@link GameState#WAITING}. Used when a countdown or
     * kickoff is cancelled — e.g. after disconnects/timeouts leave a team empty.
     */
    private void abortActiveMatchToLobby() {
        if (ended) {
            return;
        }
        cancelScheduledCleanup();
        if (goalReplay != null) {
            goalReplay.cancel();
            goalReplay = null;
        }
        celebrating = false;
        livePlayStarted = false;
        clearOffsidePhase();
        throwInTakerId = null;
        resumeAfterThrowIn = GameState.PLAYING;
        freeKickTakerId = null;
        cleanupResourcesOnly();
        crowd = null;
        replayRecorder = null;
        replayAnimations = null;
        pitchAxis = null;
        setState(GameState.WAITING);
        plugin.games().syncServerViewers();
    }

    public void startCountdown() {
        setState(GameState.COUNTDOWN);
        countdown = settings.lobbyCountdown;
        tickInSecond = 0;
        broadcast(plugin.messages().raw("match-starting-soon"));
        if (crowd != null) {
            crowd.onMatchAnticipation();
        }
        plugin.games().syncServerViewers();
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
        pitchAxis = OffsideChecker.PitchAxis.fromArena(arena);
        referee = new RefereeController(plugin, this, arena, settings);
        referee.spawn();
        clearOffsidePhase();
        ball.spawn(ballSpawnLocation());
        if (settings.broadcastHud) {
            broadcastHud = new MatchBroadcastHud(plugin);
        }
        plugin.games().syncServerViewers();
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
        clearOffsidePhase();
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
        repositionBotsForKickoff();
    }

    private void onKickoffEnd() {
        livePlayStarted = true;
        setState(resumeState);
        if (championshipId != null && fixtureId != null) {
            plugin.championships().onMatchStarted(this);
        }
        if (ball != null) {
            ball.setFrozen(false);
        }
        broadcast(plugin.messages().raw("kickoff"));
        offsideExemptTouch = true;
        if (referee != null) {
            referee.onKickoff();
        }
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
                    if (hasEnoughPlayers()) {
                        beginMatch();
                    } else {
                        abortActiveMatchToLobby();
                        broadcast(plugin.messages().raw("countdown-cancelled"));
                    }
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
        lobbyReturnCandidates = new java.util.HashSet<>(players.keySet());
        clearServerViewers();
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
        if (referee != null) {
            referee.remove();
            referee = null;
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

        // Tear the match down a few seconds later (immediately if everyone left).
        long cleanupDelay = players.isEmpty() ? 1L : 20L * 6;
        cleanupTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cleanupTaskId = -1;
            plugin.games().endGame(this);
        }, cleanupDelay).getTaskId();
    }

    /** Cancels the delayed {@link GameManager#endGame} task if still pending. */
    public void cancelScheduledCleanup() {
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
            cleanupTaskId = -1;
        }
    }

    /**
     * Drops every participant from an ended match without sending them back to a
     * network lobby — used when a fresh match replaces a recently finished one.
     */
    public void releaseAllPlayersQuietly() {
        for (UUID id : new ArrayList<>(players.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                releasePlayerQuietly(player);
                plugin.games().forget(player);
            } else {
                players.remove(id);
                snapshots.remove(id);
            }
        }
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
        cancelScheduledCleanup();
        cleanupResourcesOnly();
        List<Player> networkReturn = new ArrayList<>();
        for (UUID id : new ArrayList<>(players.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                removePlayer(p, true);
                if (plugin.games().getGame(p) == this) {
                    networkReturn.add(p);
                }
            } else {
                players.remove(id);
                snapshots.remove(id);
            }
        }
        removeAllBots();
        returnNetworkPlayersToLobby(networkReturn);
    }

    /** Removes match entities without touching participants or network routing. */
    void cleanupResourcesOnly() {
        clearServerViewers();
        if (broadcastHud != null) {
            broadcastHud.destroy();
            broadcastHud = null;
        }
        if (referee != null) {
            referee.remove();
            referee = null;
        }
        if (ball != null) {
            ball.remove();
            ball = null;
        }
        removeAllBots();
    }

    private void releasePlayerQuietly(Player player) {
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
        if (snapshot != null) {
            snapshot.restore(player);
        }
    }

    private void returnNetworkPlayersToLobby(Collection<Player> players) {
        if (players.isEmpty() || !livePlayStarted) {
            return;
        }
        List<Player> eligible = new ArrayList<>();
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (!lobbyReturnCandidates.contains(player.getUniqueId())) {
                continue;
            }
            if (plugin.games().getGame(player) != this) {
                continue;
            }
            eligible.add(player);
        }
        if (!eligible.isEmpty()) {
            plugin.network().returnToLobbyAfterMatchEnd(this, eligible);
        }
    }

    // ==================================================================
    // Match bots (solo mode)
    // ==================================================================

    public void setSoloMatch(boolean soloMatch) {
        this.soloMatch = soloMatch;
    }

    public boolean isSoloMatch() {
        return soloMatch;
    }

    public BotDifficulty getBotDifficulty() {
        return getBotDifficulty(TeamSide.RED);
    }

    public BotDifficulty getBotDifficulty(TeamSide team) {
        if (team == null) {
            return BotDifficulty.MEDIUM;
        }
        return botDifficulties.getOrDefault(team, BotDifficulty.MEDIUM);
    }

    public void setBotDifficulty(BotDifficulty botDifficulty) {
        setBotDifficulty(TeamSide.RED, botDifficulty);
        setBotDifficulty(TeamSide.BLUE, botDifficulty);
    }

    public void setBotDifficulty(TeamSide team, BotDifficulty botDifficulty) {
        if (team == null) {
            return;
        }
        botDifficulties.put(team, botDifficulty != null ? botDifficulty : BotDifficulty.MEDIUM);
    }

    public SoccerSettings getSettings() {
        return settings;
    }

    public Collection<MatchBot> getBots() {
        return bots.values();
    }

    public Collection<SoccerPlayer> getHumanParticipants() {
        return players.values();
    }

    public MatchBot addBot(TeamSide team, int index) {
        if (!settings.botsEnabled || isTeamFull(team)) {
            return null;
        }
        Location spot = resolveBotSpawn(team, index);
        if (spot == null) {
            return null;
        }
        String name = botDisplayName(index);
        MatchBot bot = new MatchBot(plugin, this, name, team, index, spot);
        bots.put(bot.getUuid(), bot);
        if (state == GameState.WAITING && hasEnoughPlayers()) {
            startCountdown();
        }
        return bot;
    }

    public void removeAllBots() {
        for (MatchBot bot : new ArrayList<>(bots.values())) {
            bot.remove();
        }
        bots.clear();
    }

    private Location resolveBotSpawn(TeamSide team, int index) {
        if (index <= 0) {
            return BotAi.keeperSpawnSpot(this, team);
        }
        Location base = arena.getTeamSpawn(team);
        if (base == null) {
            base = arena.getBallSpawn();
        }
        if (base == null) {
            return null;
        }
        return spreadBotSpawn(base, index);
    }

    private Location spreadBotSpawn(Location base, int index) {
        Location spot = base.clone();
        // Spread bots out over concentric rings so larger squads don't all pile
        // onto the same point at kickoff.
        int perRing = 6;
        int ring = index / perRing;
        int slot = index % perRing;
        double radius = 1.6 + ring * 1.8;
        double angle = slot * (Math.PI * 2.0 / perRing) + ring * 0.6;
        spot.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        return spot;
    }

    private String botDisplayName(int index) {
        java.util.List<String> names = settings.botNames;
        if (names != null && index >= 0 && index < names.size()) {
            return names.get(index);
        }
        return "Bot " + (index + 1);
    }

    private void tickBots() {
        refreshBallChasers();
        for (MatchBot bot : bots.values()) {
            bot.tick(matchTick);
        }
    }

    private void refreshBallChasers() {
        if (ballChaserRefreshTick == matchTick || ball == null || ball.getLocation() == null) {
            return;
        }
        ballChaserRefreshTick = matchTick;
        Location ballLoc = ball.getLocation();
        for (TeamSide team : TeamSide.values()) {
            BotAi.BallChaserState state = BotAi.resolveBallChaser(this, ballLoc, team, stickyBallChaserId.get(team));
            stickyBallChaserId.put(team, state.botId());
            humanChasingBall.put(team, state.humanChasing());
        }
    }

    /** Bot designated to press/chase the ball for this team (null if a human is closest). */
    public UUID getBallChaserBotId(TeamSide team) {
        return stickyBallChaserId.get(team);
    }

    public MatchBot getBot(UUID id) {
        return id == null ? null : bots.get(id);
    }

    public boolean isHumanChasingBall(TeamSide team) {
        return Boolean.TRUE.equals(humanChasingBall.get(team));
    }

    public Location getBallLocation() {
        return ball != null ? ball.getLocation() : null;
    }

    private void processBotInteractions() {
        if (ball == null || ball.getLocation() == null) {
            return;
        }
        Location ballLoc = ball.getLocation();
        double speed = ball.getSpeed();

        for (MatchBot bot : bots.values()) {
            Location feet = bot.getLocation();
            if (feet == null) {
                continue;
            }
            SoccerPlayer sp = bot.getProfile();
            boolean isKeeper = bot.getRole() == BotRole.GOALKEEPER;
            Location ownGoal = arena.getGoal(sp.getTeam()) != null
                    ? arena.getGoal(sp.getTeam()).getCenter() : null;
            boolean nearOwnGoal = ownGoal != null
                    && Math.hypot(feet.getX() - ownGoal.getX(), feet.getZ() - ownGoal.getZ()) <= 14.0;
            boolean keeperZone = isInOwnGoalBox(sp.getTeam(), feet)
                    || (isKeeper && nearOwnGoal);

            double horiz = Math.hypot(feet.getX() - ballLoc.getX(), feet.getZ() - ballLoc.getZ());
            double dy = Math.abs((feet.getY() + 1.0) - ballLoc.getY());
            double catchRadius = isKeeper && nearOwnGoal
                    ? settings.collisionRadius + 0.9
                    : settings.collisionRadius + 0.8;
            double maxHeight = isKeeper && nearOwnGoal ? 2.15 : 1.8;
            if (horiz > catchRadius || dy > maxHeight) {
                continue;
            }

            boolean isChaser = bot.getUuid().equals(getBallChaserBotId(sp.getTeam()));
            double speedThreshold = isKeeper && nearOwnGoal ? 0.35 : 0.6;

            if (speed > speedThreshold || (isKeeper && nearOwnGoal && horiz <= 0.9)) {
                if (isKeeper) {
                    // The keeper gathers the ball and launches it back up the field.
                    goalkeeperGather(bot, speed > 0.6);
                } else {
                    deflectBot(bot, sp, ballLoc, keeperZone);
                }
            } else if (isChaser && bot.getVelocity().lengthSquared() > 0.0025) {
                Vector nudge = Mechanics.dribbleNudge(bot, settings);
                ball.recordTouch(bot.getUuid(), sp.getTeam());
                ball.applyImpulse(nudge, null);
                sp.addTouch();
            }
        }
    }

    private void deflectBot(MatchBot bot, SoccerPlayer sp, Location ballLoc, boolean keeperZone) {
        Location feet = bot.getLocation();
        Vector away;
        if (keeperZone && bot.getRole() == BotRole.GOALKEEPER) {
            Location target = midfieldClearanceTarget(ballLoc);
            away = target.toVector().subtract(ball.getLocation().toVector());
            away.setY(0.05);
        } else {
            away = ball.getLocation().toVector().subtract(feet.toVector());
            away.setY(Math.max(0.1, away.getY()));
            if (away.lengthSquared() < 1.0e-6) {
                away = feet.getDirection();
            }
        }
        away.normalize();
        double power = keeperZone && bot.getRole() == BotRole.GOALKEEPER
                ? 0.58
                : keeperZone ? 0.35 : (0.25 + ball.getSpeed() * 0.4);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(away.multiply(power), null);
        sp.addTouch();
        if (keeperZone) {
            sp.addSave();
            if (crowd != null) {
                crowd.onSave(sp.getTeam());
            }
        }
        feet.getWorld().spawnParticle(Particle.CRIT, ballLoc, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private Location midfieldClearanceTarget(Location fallback) {
        if (arena.getField() != null) {
            Location center = arena.getField().getCenter();
            center.setY(fallback != null ? fallback.getY() : center.getY());
            return center;
        }
        if (arena.getBallSpawn() != null) {
            Location spawn = arena.getBallSpawn().clone();
            spawn.setY(fallback != null ? fallback.getY() : spawn.getY());
            return spawn;
        }
        return fallback != null ? fallback.clone() : new Location(null, 0, 0, 0);
    }

    public void botKick(MatchBot bot, BallKickEvent.KickType type, double charge) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Mechanics.Kick kick = Mechanics.compute(bot, settings, type, charge);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        if (type == BallKickEvent.KickType.SHOT) {
            sp.addShot();
        } else if (type == BallKickEvent.KickType.PASS || type == BallKickEvent.KickType.THROW_IN) {
            sp.addPass();
        } else if (type == BallKickEvent.KickType.TACKLE) {
            sp.addTackle();
        }
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    public void botPassAt(MatchBot bot, Location target, double chargeFraction) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot) || target == null) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedTarget = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), target, false);
        BotDifficulty difficulty = getBotDifficulty(bot.getTeam());
        double adjustedCharge = difficulty == BotDifficulty.EASY ? chargeFraction * 0.82 : chargeFraction;
        Mechanics.Kick kick = Mechanics.computeBotPassToward(
                from != null ? from : bot.getLocation(), adjustedTarget, settings, adjustedCharge);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addPass();
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85f, 1.05f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    public void botStrongPassAt(MatchBot bot, Location target, double powerFraction, double verticalLift) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot) || target == null) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedTarget = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), target, false);
        BotDifficulty difficulty = getBotDifficulty(bot.getTeam());
        double powerScale = difficulty == BotDifficulty.EASY ? 0.78 : 1.0;
        double passPower = Math.max(0.14, Math.min(0.55, powerFraction * powerScale));
        double passLift = Math.max(0.0, Math.min(0.12, verticalLift));
        Mechanics.Kick kick = Mechanics.computeBotShotToward(
                from != null ? from : bot.getLocation(), adjustedTarget, settings, passPower, passLift);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addPass();
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 0.9f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 6, 0.15, 0.12, 0.15, 0.035);
        }
    }

    public void botShootAtGoal(MatchBot bot, Location aimPoint, double powerFraction, double verticalLift) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot) || aimPoint == null) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedAim = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), aimPoint, true);
        BotDifficulty difficulty = getBotDifficulty(bot.getTeam());
        double powerScale = switch (difficulty) {
            case EASY -> 0.72;
            case MEDIUM -> 1.0;
            case HARD -> 1.08;
        };
        double liftScale = difficulty == BotDifficulty.EASY ? 1.35 : 1.0;
        Mechanics.Kick kick = Mechanics.computeBotShotToward(
                from != null ? from : bot.getLocation(), adjustedAim, settings,
                powerFraction * powerScale, verticalLift * liftScale);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addShot();
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    private Location botAdjustedTarget(MatchBot bot, Location from, Location target, boolean shot) {
        BotDifficulty difficulty = getBotDifficulty(bot.getTeam());
        if (difficulty == BotDifficulty.MEDIUM || target == null) {
            return target;
        }
        Location adjusted = target.clone();
        if (from == null || from.getWorld() == null || adjusted.getWorld() == null
                || !from.getWorld().equals(adjusted.getWorld())) {
            return adjusted;
        }
        double distance = Math.max(1.0, from.distance(adjusted));
        double miss = switch (difficulty) {
            case EASY -> shot ? 1.6 + distance * 0.055 : 1.0 + distance * 0.045;
            case HARD -> shot ? 0.08 : 0.05;
            case MEDIUM -> 0.0;
        };
        if (miss <= 0.0) {
            return adjusted;
        }
        double seed = bot.getUuid().getMostSignificantBits() * 0.000001
                + bot.getUuid().getLeastSignificantBits() * 0.0000001
                + matchTick * 0.37
                + (shot ? 17.0 : 3.0);
        double angle = seed - Math.floor(seed / (Math.PI * 2.0)) * (Math.PI * 2.0);
        adjusted.add(Math.cos(angle) * miss, shot && difficulty == BotDifficulty.EASY ? 0.45 : 0.0,
                Math.sin(angle) * miss);
        return adjusted;
    }

    /**
     * Bot shot on goal with a controlled, distance-aware trajectory. This keeps
     * close-range efforts low and driven (so bots stop ballooning the ball over
     * the bar) while still allowing a touch of loft on longer strikes.
     */
    public void botShoot(MatchBot bot, double powerFraction, double verticalLift) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Mechanics.Kick kick = Mechanics.computeBotShot(bot, settings, powerFraction, verticalLift);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addShot();
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    public void botDribble(MatchBot bot) {
        if (ball == null || bot.getLocation() == null) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Vector nudge = Mechanics.dribbleNudge(bot, settings);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(nudge, null);
        sp.addTouch();
    }

    private boolean reachable(MatchBot bot) {
        return reachable(bot, settings.kickReach, true, 2.5);
    }

    private boolean reachable(MatchBot bot, double reach) {
        return reachable(bot, reach, true, 2.5);
    }

    private boolean reachable(MatchBot bot, double reach, boolean requireFacing) {
        return reachable(bot, reach, requireFacing, 2.5);
    }

    private boolean reachable(MatchBot bot, double reach, boolean requireFacing, double maxVertical) {
        if (ball == null || ball.getLocation() == null || bot.getLocation() == null) {
            return false;
        }
        Location ballLoc = ball.getLocation();
        Location eye = bot.getEyeLocation();
        double horiz = Math.hypot(eye.getX() - ballLoc.getX(), eye.getZ() - ballLoc.getZ());
        double vert = Math.abs(bot.getLocation().getY() - ballLoc.getY());
        if (horiz > reach || vert > maxVertical) {
            return false;
        }
        if (!requireFacing || horiz < 1.2) {
            return true;
        }
        Vector look = eye.getDirection().setY(0);
        Vector toBall = ballLoc.toVector().subtract(eye.toVector()).setY(0);
        if (look.lengthSquared() < 1e-6 || toBall.lengthSquared() < 1e-6) {
            return true;
        }
        return look.normalize().dot(toBall.normalize()) > 0.25;
    }

    public void botGoalkeeperSave(MatchBot bot) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || bot.getLocation() == null || bot.getRole() != BotRole.GOALKEEPER) {
            return;
        }
        if (!reachable(bot, Math.max(1.85, settings.kickReach * 1.0), false, 2.4)) {
            return;
        }
        goalkeeperGather(bot, ball.getSpeed() > 0.6);
    }

    /**
     * The keeper claims the ball and immediately distributes it: he stops the
     * incoming ball, picks the best-placed teammate up the field (or the most open
     * channel) via the AI, and launches a powerful, distance-scaled kick — a hard
     * punt for a long ball, or a firmer driven throw for a nearby option.
     *
     * @param countSave whether this counted as a save (a fast inbound ball)
     */
    private void goalkeeperGather(MatchBot bot, boolean countSave) {
        if (ball == null || ball.getLocation() == null || bot.getLocation() == null
                || bot.getRole() != BotRole.GOALKEEPER) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location ballLoc = ball.getLocation();

        Location target = BotAi.keeperDistributionTarget(this, bot);
        if (target == null) {
            target = midfieldClearanceTarget(ballLoc);
        }

        double dist = Math.hypot(target.getX() - ballLoc.getX(), target.getZ() - ballLoc.getZ());
        double power;
        double lift;
        if (dist > 22.0) {
            power = 0.95;   // long, lofted goal-kick / punt
            lift = 0.48;
        } else if (dist > 13.0) {
            power = 0.80;   // driven ball into midfield
            lift = 0.30;
        } else {
            power = 0.60;   // short, low throw/roll to a nearby teammate
            lift = 0.06;
        }

        Mechanics.Kick kick = Mechanics.computeBotShotToward(ballLoc, target, settings, power, lift);
        ball.recordTouch(bot.getUuid(), sp.getTeam());
        // Gather first (kill the incoming pace) so the distribution flies cleanly.
        ball.teleport(ballLoc);
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addPass();
        if (countSave) {
            sp.addSave();
            if (crowd != null) {
                crowd.onSave(sp.getTeam());
            }
        }
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.8f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 8, 0.2, 0.1, 0.2, 0.03);
            if (countSave) {
                loc.getWorld().spawnParticle(Particle.CRIT, loc, 10, 0.15, 0.15, 0.15, 0.04);
            }
        }
    }

    private void repositionBotsForKickoff() {
        for (MatchBot bot : bots.values()) {
            Location spot = resolveBotSpawn(bot.getTeam(), bot.getSquadIndex());
            if (spot != null) {
                bot.setLocation(spot);
                bot.syncVisual();
            }
        }
    }

    // ==================================================================
    // Per-tick simulation
    // ==================================================================

    private boolean shouldSimulateBall() {
        return switch (state) {
            case KICKOFF, PLAYING, THROW_IN, OFFSIDE, HALFTIME, EXTRA_TIME, PENALTIES, REPLAY, ENDED -> true;
            default -> false;
        };
    }

    public void tick() {
        matchTick++;

        GameState refState = goalReplay != null ? GameState.REPLAY : state;
        if (referee != null) {
            referee.tick(refState, celebrating || goalReplay != null, ball,
                    players.values(), pitchAxis);
        }

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

        if (ball != null && ball.isSpawned() && shouldSimulateBall()) {
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
                processBotInteractions();
                checkGoal();
                checkBoundary();
            }
            if (throwInBoundaryGrace > 0) {
                throwInBoundaryGrace--;
            }
            trackPossession();
        } else if (state == GameState.OFFSIDE) {
            tickOffsideRestart();
        } else if (state == GameState.THROW_IN) {
            tickThrowInRestart();
        }

        if ((state.isLive() || state == GameState.KICKOFF) && !bots.isEmpty()) {
            tickBots();
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
                    if (tryHandleOffsideTouch(p, sp.getTeam())) {
                        continue;
                    }
                    Vector nudge = Mechanics.dribbleNudge(p, settings);
                    ball.recordTouch(p, sp.getTeam());
                    ball.applyImpulse(nudge, null);
                    sp.addTouch();
                }
            }
        }
    }

    private void deflect(SoccerPlayer sp, Player p, Location ballLoc, boolean keeperZone) {
        if (tryHandleOffsideTouch(p, sp.getTeam())) {
            return;
        }
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
                // Last touch was the conceding team. If the ball was previously
                // played by the scoring team (a shot the keeper/defender only got
                // a touch on but could not stop), credit that shooter instead of
                // blaming the deflector with an own goal.
                UUID prevId = ball.getPrevTouchId();
                if (prevId != null && !prevId.equals(lastId)
                        && ball.getPrevTouchTeam() == scoringTeam) {
                    scorer = players.get(prevId);
                    ownGoal = false;
                } else {
                    ownGoal = true;
                    scorer = last;
                }
            }
        }

        if (settings.offsideEnabled && !ownGoal && scorer != null
                && tryOffsideGoalDisallowed(scorer)) {
            return;
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
        if (referee != null) {
            referee.onGoal();
        }
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
        Location prev = ball.getPreviousTickLocation();

        if (settings.throwInEnabled && arena.hasOutLines() && throwInBoundaryGrace <= 0) {
            OutLineCrossing crossing = arena.detectOutLineCrossing(prev, loc);
            if (crossing != null) {
                startThrowIn(crossing.throwInSpot(), crossing.geometry());
                return;
            }
        }

        BoundaryExit exit = arena.classifyBoundaryExit(loc);
        switch (exit) {
            case IN_PLAY:
                return;
            case TOUCHLINE:
                if (settings.throwInEnabled && !arena.hasOutLines() && throwInBoundaryGrace <= 0) {
                    startThrowIn(loc);
                } else if (!settings.throwInEnabled) {
                    resetBallToCenter(plugin.messages().raw("touchline-out"));
                }
                break;
            case GOAL_LINE:
                tryScoreFromBoundary(loc);
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
        startThrowIn(exitLoc, null);
    }

    private void startThrowIn(Location exitLoc, ThrowInGeometry presetGeometry) {
        clearOffsidePhase();
        TeamSide lastTouch = ball.getLastTouchTeam();
        throwInTeam = lastTouch == null ? TeamSide.RED : lastTouch.opposite();
        resumeAfterThrowIn = state;

        if (presetGeometry != null) {
            throwInGeometry = presetGeometry;
            throwInSpot = presetGeometry.spot().clone();
        } else {
            Location spot = arena.computeThrowInSpot(exitLoc);
            throwInGeometry = arena.computeThrowInGeometry(exitLoc);
            throwInSpot = spot.clone();
        }

        if (arena.getField() != null) {
            throwInSpot.setY(arena.getField().getMinY() + 0.35);
        }
        throwInRestartPulse = 0;
        throwInAwaitingFieldEntry = false;
        throwInAwaitingTicks = 0;
        throwInTakerId = null;
        ball.teleport(throwInSpot);
        ball.setFrozen(true);
        ball.clearTouchHistory();

        setState(GameState.THROW_IN);

        if (referee != null) {
            referee.onThrowIn(throwInSpot);
        }

        broadcast(plugin.messages().format("throw-in", "team", teamName(throwInTeam)));

        if (settings.throwInAutoTaker) {
            assignThrowInTaker(true);
        } else {
            notifyThrowInRestart();
        }
    }

    private void endThrowIn() {
        clearThrowInRestart();
        throwInTeam = null;
        offsideExemptTouch = true;
        setState(resumeAfterThrowIn);
        forEachPlayer(p -> p.sendTitle(Text.color(plugin.messages().raw("play-on")), "", 0, 20, 10));
    }

    private void clearThrowInRestart() {
        if (throwInTakerId != null) {
            SoccerPlayer taker = players.get(throwInTakerId);
            if (taker != null) {
                Player player = taker.getBukkitPlayer();
                if (player != null) {
                    ThrowInRestartHelper.clearParticipantMovement(player);
                }
            }
        }
        forEachPlayer(ThrowInRestartHelper::clearParticipantMovement);
        throwInTakerId = null;
        throwInSpot = null;
        throwInGeometry = null;
        throwInRestartPulse = 0;
        throwInAwaitingFieldEntry = false;
        throwInAwaitingTicks = 0;
    }

    private void assignThrowInTaker(boolean announce) {
        if (throwInSpot == null || throwInTeam == null) {
            return;
        }
        UUID takerId = findNearestThrowInTakerId(throwInSpot, throwInTeam);
        if (takerId == null) {
            return;
        }
        applyThrowInTaker(takerId, announce);
    }

    private void reassignThrowInTaker(boolean announce) {
        if (state != GameState.THROW_IN || throwInTeam == null || throwInSpot == null) {
            return;
        }
        SoccerPlayer previous = throwInTakerId != null ? players.get(throwInTakerId) : null;
        if (previous != null) {
            Player previousPlayer = previous.getBukkitPlayer();
            if (previousPlayer != null) {
                ThrowInRestartHelper.clearParticipantMovement(previousPlayer);
            }
        }
        UUID takerId = findNearestThrowInTakerId(throwInSpot, throwInTeam);
        if (takerId == null) {
            return;
        }
        applyThrowInTaker(takerId, announce);
    }

    private UUID findNearestThrowInTakerId(Location spot, TeamSide team) {
        if (throwInTakerId != null && isEligibleThrowInTaker(throwInTakerId, team, spot)) {
            double currentDist = throwInTakerDistance(throwInTakerId, spot);
            UUID challenger = findNearestThrowInTakerIdRaw(spot, team, throwInTakerId);
            if (challenger == null) {
                return throwInTakerId;
            }
            double challengerDist = throwInTakerDistance(challenger, spot);
            if (challengerDist + 3.0 < currentDist) {
                return challenger;
            }
            return throwInTakerId;
        }
        return findNearestThrowInTakerIdRaw(spot, team, null);
    }

    private boolean isEligibleThrowInTaker(UUID id, TeamSide team, Location spot) {
        SoccerPlayer human = players.get(id);
        if (human != null && !human.isBot() && human.getTeam() == team) {
            Player player = human.getBukkitPlayer();
            return player != null && player.isOnline()
                    && ThrowInRestartHelper.horizontalDistance(player.getLocation(), spot) <= 18.0;
        }
        MatchBot bot = bots.get(id);
        return bot != null && bot.getTeam() == team && bot.getLocation() != null
                && ThrowInRestartHelper.horizontalDistance(bot.getLocation(), spot) <= 18.0;
    }

    private double throwInTakerDistance(UUID id, Location spot) {
        SoccerPlayer human = players.get(id);
        if (human != null && !human.isBot()) {
            Player player = human.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                return ThrowInRestartHelper.horizontalDistance(player.getLocation(), spot);
            }
        }
        MatchBot bot = bots.get(id);
        if (bot != null && bot.getLocation() != null) {
            return ThrowInRestartHelper.horizontalDistance(bot.getLocation(), spot);
        }
        return Double.MAX_VALUE;
    }

    private UUID findNearestThrowInTakerIdRaw(Location spot, TeamSide team, UUID ignoreId) {
        UUID nearestId = null;
        double best = Double.MAX_VALUE;
        for (SoccerPlayer sp : players.values()) {
            if (sp.getTeam() != team || sp.isBot()) {
                continue;
            }
            if (ignoreId != null && sp.getUuid().equals(ignoreId)) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            double dist = ThrowInRestartHelper.horizontalDistance(player.getLocation(), spot);
            if (dist < best) {
                best = dist;
                nearestId = sp.getUuid();
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() != team) {
                continue;
            }
            if (ignoreId != null && bot.getUuid().equals(ignoreId)) {
                continue;
            }
            Location loc = bot.getLocation();
            if (loc == null) {
                continue;
            }
            double dist = ThrowInRestartHelper.horizontalDistance(loc, spot);
            if (dist < best) {
                best = dist;
                nearestId = bot.getUuid();
            }
        }
        return nearestId;
    }

    private void applyThrowInTaker(UUID takerId, boolean announce) {
        if (takerId == null || throwInSpot == null || throwInGeometry == null) {
            return;
        }
        throwInTakerId = takerId;
        Location takerSpot = ThrowInRestartHelper.computeTakerSpot(throwInSpot, throwInGeometry);

        SoccerPlayer human = players.get(takerId);
        if (human != null && !human.isBot()) {
            Player player = human.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                return;
            }
            player.teleport(takerSpot);
            ThrowInRestartHelper.applyTakerVisuals(player, settings);
            scheduleMovementReset();
        } else {
            MatchBot bot = bots.get(takerId);
            if (bot != null) {
                bot.setLocation(takerSpot);
            }
        }

        if (announce) {
            broadcast(plugin.messages().format("throw-in-taker-assigned", "player", throwInTakerName()));
            notifyThrowInRestart();
        }
    }

    private void notifyThrowInRestart() {
        String takerName = throwInTakerName();
        forEachPlayer(p -> {
            SoccerPlayer sp = players.get(p.getUniqueId());
            boolean isTaker = throwInTakerId != null && p.getUniqueId().equals(throwInTakerId);
            boolean onTakingTeam = sp != null && sp.getTeam() == throwInTeam;
            if (isTaker) {
                p.sendTitle(
                        Text.color(plugin.messages().raw("throw-in-title")),
                        Text.color(throwInTakerId != null
                                ? plugin.messages().format("throw-in-subtitle-taker-named",
                                "team", teamName(throwInTeam))
                                : plugin.messages().format("throw-in-subtitle-taker",
                                "team", teamName(throwInTeam))),
                        5, 60, 15);
            } else if (onTakingTeam) {
                p.sendTitle(
                        Text.color(plugin.messages().raw("throw-in-title")),
                        Text.color(plugin.messages().raw("throw-in-subtitle-teammate-wait")),
                        5, 50, 15);
            } else {
                p.sendTitle(
                        Text.color(plugin.messages().raw("throw-in-title")),
                        Text.color(throwInTakerId != null
                                ? plugin.messages().format("throw-in-subtitle-wait-named",
                                "player", takerName, "team", teamName(throwInTeam))
                                : plugin.messages().format("throw-in-subtitle-wait",
                                "team", teamName(throwInTeam))),
                        5, 50, 15);
            }
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
        });
    }

    private String throwInTakerName() {
        if (throwInTakerId == null) {
            return teamName(throwInTeam);
        }
        SoccerPlayer taker = players.get(throwInTakerId);
        if (taker != null) {
            return taker.getName();
        }
        MatchBot bot = bots.get(throwInTakerId);
        if (bot != null) {
            return bot.getProfile().getName();
        }
        return teamName(throwInTeam);
    }

    private void tickThrowInRestart() {
        if (ball == null || throwInTeam == null || throwInSpot == null || throwInGeometry == null) {
            return;
        }
        if (throwInAwaitingFieldEntry) {
            tickThrowInAwaitingEntry();
            return;
        }

        throwInRestartPulse++;
        Location ballLoc = ball.getLocation();
        Cuboid field = arena.getField();

        if (settings.throwInTakerParticles && throwInRestartPulse % 4 == 0) {
            ThrowInRestartHelper.spawnBallMarker(ballLoc, throwInTeam);
        }

        if (throwInTakerId != null && field != null) {
            SoccerPlayer human = players.get(throwInTakerId);
            if (human != null && !human.isBot()) {
                Player player = human.getBukkitPlayer();
                if (player != null && player.isOnline()) {
                    Location clamped = ThrowInRestartHelper.clampTakerPosition(
                            player.getLocation(),
                            throwInSpot,
                            throwInGeometry,
                            field,
                            settings.throwInTakerWanderBlocks);
                    boolean crossedOut = ThrowInRestartHelper.hasCrossedTouchlineOutward(
                            player.getLocation(), throwInGeometry, field);
                    if (crossedOut
                            || (settings.throwInTakerSnapBack
                            && needsThrowInSnapBack(player.getLocation(), clamped))) {
                        player.teleport(clamped);
                    }
                    ThrowInRestartHelper.applyTakerVisuals(player, settings);
                }
            } else {
                MatchBot bot = bots.get(throwInTakerId);
                if (bot != null && bot.getLocation() != null) {
                    Location clamped = ThrowInRestartHelper.clampTakerPosition(
                            bot.getLocation(),
                            throwInSpot,
                            throwInGeometry,
                            field,
                            settings.throwInTakerWanderBlocks);
                    if (ThrowInRestartHelper.hasCrossedTouchlineOutward(bot.getLocation(), throwInGeometry, field)
                            || (settings.throwInTakerSnapBack
                            && needsThrowInSnapBack(bot.getLocation(), clamped))) {
                        bot.setLocation(clamped);
                    }
                    if (throwInRestartPulse == 40) {
                        performBotThrowIn(bot, BallKickEvent.KickType.THROW_IN);
                    }
                }
            }
        }

        for (SoccerPlayer sp : players.values()) {
            if (throwInTakerId != null && sp.getUuid().equals(throwInTakerId)) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                ThrowInRestartHelper.clearParticipantMovement(player);
            }
        }

        if (settings.actionBarHud && throwInRestartPulse % 4 == 0) {
            updateThrowInActionBar();
        }
    }

    private void tickThrowInAwaitingEntry() {
        throwInAwaitingTicks++;
        Location ballLoc = ball.getLocation();
        if (ballLoc == null) {
            return;
        }

        if (ThrowInRestartHelper.isBallInPlay(throwInGeometry, ballLoc, arena.getField())) {
            completeThrowIn();
            return;
        }

        if (ball.getSpeed() < 0.08 && throwInAwaitingTicks >= THROW_IN_RETAKES_AFTER_TICKS) {
            retakeThrowIn();
        }
    }

    private void retakeThrowIn() {
        throwInAwaitingFieldEntry = false;
        throwInAwaitingTicks = 0;
        throwInRestartPulse = 0;
        ball.teleport(throwInSpot);
        ball.setFrozen(true);
        if (throwInTakerId != null) {
            SoccerPlayer human = players.get(throwInTakerId);
            if (human != null && !human.isBot()) {
                Player player = human.getBukkitPlayer();
                if (player != null && player.isOnline()) {
                    ThrowInRestartHelper.applyTakerVisuals(player, settings);
                }
            }
        }
    }

    private void completeThrowIn() {
        throwInAwaitingFieldEntry = false;
        throwInAwaitingTicks = 0;
        throwInBoundaryGrace = THROW_IN_BOUNDARY_GRACE_TICKS;
        endThrowIn();
    }

    private Vector ensureThrowInImpulse(Vector impulse) {
        if (throwInGeometry == null || impulse == null) {
            return impulse;
        }
        Vector inward = throwInGeometry.inward().clone();
        inward.setY(0);
        if (inward.lengthSquared() < 1e-6) {
            return impulse;
        }
        inward.normalize();
        Vector horizontal = new Vector(impulse.getX(), 0, impulse.getZ());
        double speed = horizontal.length();
        if (speed < 0.12) {
            return inward.clone().multiply(0.42);
        }
        if (horizontal.normalize().dot(inward) < 0.25) {
            return inward.clone().multiply(Math.max(0.35, speed));
        }
        return impulse;
    }

    private void releaseThrowInKick(SoccerPlayer sp, Vector impulse, Vector spin) {
        ball.recordTouch(sp.getUuid(), sp.getTeam());
        ball.applyImpulse(ensureThrowInImpulse(impulse), spin);
        ball.setFrozen(false);
        sp.addTouch();
        sp.addPass();
        throwInAwaitingFieldEntry = true;
        throwInAwaitingTicks = 0;

        if (throwInTakerId != null) {
            SoccerPlayer taker = players.get(throwInTakerId);
            if (taker != null && !taker.isBot()) {
                Player player = taker.getBukkitPlayer();
                if (player != null) {
                    ThrowInRestartHelper.clearParticipantMovement(player);
                }
            }
        }
    }

    private boolean needsThrowInSnapBack(Location current, Location clamped) {
        if (current == null || clamped == null || current.getWorld() == null || clamped.getWorld() == null) {
            return false;
        }
        return Math.hypot(current.getX() - clamped.getX(), current.getZ() - clamped.getZ()) > 0.08;
    }

    private void updateThrowInActionBar() {
        String takerName = throwInTakerName();
        forEachPlayer(p -> {
            if (throwInTakerId != null && p.getUniqueId().equals(throwInTakerId)) {
                ActionBar.send(p, plugin.messages().raw("throw-in-actionbar-taker"));
            } else {
                ActionBar.send(p, plugin.messages().format("throw-in-actionbar-wait",
                        "player", takerName, "team", teamName(throwInTeam)));
            }
        });
    }

    // ==================================================================
    // Offside (IFAB: judged at pass, penalised on involvement)
    // ==================================================================

    private void beginOffsidePhase(Player player, TeamSide team, BallKickEvent.KickType type) {
        if (!settings.offsideEnabled || pitchAxis == null || ball == null) {
            pendingOffsideCheck = null;
            return;
        }
        if (type != BallKickEvent.KickType.PASS && type != BallKickEvent.KickType.SHOT) {
            return;
        }
        pendingOffsideCheck = OffsideSnapshot.capture(
                pitchAxis, team, player.getUniqueId(), ball.getLocation(), players);
    }

    private void clearOffsidePhase() {
        pendingOffsideCheck = null;
        freeKickTeam = null;
        clearFreeKickRestart();
    }

    private void clearFreeKickRestart() {
        if (freeKickTakerId != null) {
            SoccerPlayer taker = players.get(freeKickTakerId);
            if (taker != null) {
                Player player = taker.getBukkitPlayer();
                if (player != null) {
                    OffsideRestartHelper.clearParticipantMovement(player);
                }
            }
        }
        forEachPlayer(OffsideRestartHelper::clearParticipantMovement);
        freeKickTakerId = null;
        freeKickSpot = null;
        offsideRestartPulse = 0;
    }

    /**
     * Returns true if play was stopped for offside (touch should not be applied).
     */
    private boolean tryHandleOffsideTouch(Player player, TeamSide touchTeam) {
        if (offsideExemptTouch) {
            offsideExemptTouch = false;
            clearOffsidePhase();
            return false;
        }
        if (!settings.offsideEnabled || pendingOffsideCheck == null) {
            return false;
        }
        if (touchTeam != pendingOffsideCheck.getAttackingTeam()) {
            clearOffsidePhase();
            return false;
        }
        if (player.getUniqueId().equals(pendingOffsideCheck.getPlayedBy())) {
            return false;
        }
        if (!pendingOffsideCheck.wasOffside(player.getUniqueId())) {
            return false;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null) {
            return false;
        }
        callOffside(sp, player.getLocation());
        return true;
    }

    private boolean tryOffsideGoalDisallowed(SoccerPlayer scorer) {
        if (pendingOffsideCheck == null
                || pendingOffsideCheck.getAttackingTeam() != scorer.getTeam()) {
            return false;
        }
        if (!pendingOffsideCheck.wasOffside(scorer.getUuid())) {
            return false;
        }
        Player p = scorer.getBukkitPlayer();
        Location spot = p != null ? p.getLocation() : ball.getLocation();
        callOffside(scorer, spot);
        return true;
    }

    private void callOffside(SoccerPlayer offender, Location spot) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        TeamSide attacking = offender.getTeam();
        TeamSide defending = attacking.opposite();
        Location restart = pitchAxis != null ? pitchAxis.clampToField(spot) : spot;
        if (restart == null) {
            restart = spot;
        }

        OffsideEvent event = new OffsideEvent(this, offender, attacking, defending, restart);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            clearOffsidePhase();
            return;
        }

        clearOffsidePhase();
        resumeAfterFreeKick = state;
        freeKickTeam = defending;

        ball.teleport(restart);
        ball.setFrozen(true);
        ball.clearTouchHistory();

        setState(GameState.OFFSIDE);

        if (referee != null) {
            referee.onOffside(restart);
        }

        broadcast(plugin.messages().format("offside",
                "player", offender.getName(), "team", teamName(defending)));

        assignFreeKickTaker(restart, defending);
        notifyOffsideRestart();
    }

    private void assignFreeKickTaker(Location restart, TeamSide defending) {
        freeKickSpot = restart.clone();
        freeKickTakerId = null;
        if (!settings.offsideAutoTaker) {
            return;
        }
        SoccerPlayer taker = OffsideRestartHelper.findNearestTaker(players, defending, restart);
        if (taker == null) {
            return;
        }
        applyFreeKickTaker(taker, restart, defending, true);
    }

    private void reassignFreeKickTaker(boolean announce) {
        if (state != GameState.OFFSIDE || freeKickTeam == null || freeKickSpot == null) {
            return;
        }
        SoccerPlayer previous = freeKickTakerId != null ? players.get(freeKickTakerId) : null;
        if (previous != null) {
            Player previousPlayer = previous.getBukkitPlayer();
            if (previousPlayer != null) {
                OffsideRestartHelper.clearParticipantMovement(previousPlayer);
            }
        }
        SoccerPlayer taker = OffsideRestartHelper.findNearestTaker(players, freeKickTeam, freeKickSpot);
        if (taker == null) {
            return;
        }
        applyFreeKickTaker(taker, freeKickSpot, freeKickTeam, announce);
    }

    private void applyFreeKickTaker(SoccerPlayer taker, Location restart, TeamSide defending, boolean announce) {
        if (taker == null || restart == null) {
            return;
        }
        Player player = taker.getBukkitPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        freeKickTakerId = taker.getUuid();
        Location takerSpot = OffsideRestartHelper.computeTakerSpot(restart, pitchAxis, defending);
        player.teleport(takerSpot);
        OffsideRestartHelper.applyTakerVisuals(player, settings);
        scheduleMovementReset();

        if (announce) {
            broadcast(plugin.messages().format("offside-taker-assigned", "player", taker.getName()));
            notifyOffsideRestart();
        }
    }

    private void notifyOffsideRestart() {
        String takerName = freeKickTakerName();
        forEachPlayer(p -> {
            SoccerPlayer sp = players.get(p.getUniqueId());
            boolean isTaker = freeKickTakerId != null && p.getUniqueId().equals(freeKickTakerId);
            boolean onTakingTeam = sp != null && sp.getTeam() == freeKickTeam;
            if (isTaker) {
                p.sendTitle(
                        Text.color(plugin.messages().raw("offside-title")),
                        Text.color(freeKickTakerId != null
                                ? plugin.messages().format("offside-subtitle-taker-named",
                                "team", teamName(freeKickTeam))
                                : plugin.messages().format("offside-subtitle-taker",
                                "team", teamName(freeKickTeam))),
                        5, 60, 15);
            } else if (onTakingTeam) {
                p.sendTitle(
                        Text.color(plugin.messages().raw("offside-title")),
                        Text.color(plugin.messages().raw("offside-subtitle-teammate-wait")),
                        5, 50, 15);
            } else {
                p.sendTitle(
                        Text.color(plugin.messages().raw("offside-title")),
                        Text.color(freeKickTakerId != null
                                ? plugin.messages().format("offside-subtitle-wait-named",
                                "player", takerName, "team", teamName(freeKickTeam))
                                : plugin.messages().format("offside-subtitle-wait",
                                "team", teamName(freeKickTeam))),
                        5, 50, 15);
            }
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.8f);
        });
    }

    private String freeKickTakerName() {
        if (freeKickTakerId == null) {
            return teamName(freeKickTeam);
        }
        SoccerPlayer taker = players.get(freeKickTakerId);
        return taker != null ? taker.getName() : teamName(freeKickTeam);
    }

    private void tickOffsideRestart() {
        if (ball == null || freeKickTeam == null) {
            return;
        }
        offsideRestartPulse++;
        Location ballLoc = ball.getLocation();

        if (settings.offsideTakerParticles && offsideRestartPulse % 4 == 0) {
            OffsideRestartHelper.spawnBallMarker(ballLoc, freeKickTeam);
        }

        Location anchorSpot = freeKickSpot != null ? freeKickSpot : ballLoc;
        Location takerSpot = pitchAxis != null
                ? OffsideRestartHelper.computeTakerSpot(anchorSpot, pitchAxis, freeKickTeam)
                : anchorSpot;

        for (SoccerPlayer sp : players.values()) {
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (freeKickTakerId != null && sp.getUuid().equals(freeKickTakerId)) {
                if (settings.offsideTakerSnapBack
                        && OffsideRestartHelper.horizontalDistance(player.getLocation(), takerSpot)
                        > settings.offsideTakerWanderBlocks) {
                    player.teleport(takerSpot);
                    OffsideRestartHelper.applyTakerVisuals(player, settings);
                }
            } else if (sp.getTeam() != freeKickTeam) {
                OffsideRestartHelper.restrainOpponent(player, ballLoc, settings);
            } else {
                player.setWalkSpeed(0.2f);
            }
        }

        if (settings.actionBarHud && offsideRestartPulse % 4 == 0) {
            updateOffsideActionBar();
        }
    }

    private void updateOffsideActionBar() {
        String takerName = freeKickTakerName();
        forEachPlayer(p -> {
            if (freeKickTakerId != null && p.getUniqueId().equals(freeKickTakerId)) {
                ActionBar.send(p, plugin.messages().raw("offside-actionbar-taker"));
            } else {
                ActionBar.send(p, plugin.messages().format("offside-actionbar-wait",
                        "player", takerName, "team", teamName(freeKickTeam)));
            }
        });
    }

    private void endFreeKick() {
        clearFreeKickRestart();
        freeKickTeam = null;
        setState(resumeAfterFreeKick);
        forEachPlayer(p -> p.sendTitle(Text.color(plugin.messages().raw("play-on")), "", 0, 20, 10));
    }

    private void performFreeKick(Player player, BallKickEvent.KickType type) {
        if (state != GameState.OFFSIDE || freeKickTeam == null) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || ball == null) {
            return;
        }
        if (sp.getTeam() != freeKickTeam) {
            plugin.messages().send(player, "offside-wrong-team",
                    "team", teamName(freeKickTeam));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        if (settings.offsideAutoTaker && settings.offsideTakerOnlyKick
                && freeKickTakerId != null && !player.getUniqueId().equals(freeKickTakerId)) {
            plugin.messages().send(player, "offside-wrong-taker", "player", freeKickTakerName());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        if (!reachable(player, settings.kickReach * 1.4)) {
            return;
        }

        BallKickEvent.KickType mechanicsType = type == BallKickEvent.KickType.SHOT
                ? BallKickEvent.KickType.SHOT
                : BallKickEvent.KickType.PASS;
        Mechanics.Kick kick = Mechanics.compute(player, settings, mechanicsType, 0);

        BallKickEvent event = new BallKickEvent(player, ball, mechanicsType, kick.impulse, kick.spin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        ball.recordTouch(player, sp.getTeam());
        ball.applyImpulse(event.getImpulse(), event.getSpin());
        ball.setFrozen(false);
        sp.addTouch();
        sp.addPass();

        endFreeKick();

        Location loc = ball.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private void resetBallToCenter(String message) {
        ball.teleport(ballSpawnLocation());
        ball.clearTouchHistory();
        clearOffsidePhase();
        offsideExemptTouch = true;
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
        if (state == GameState.OFFSIDE) {
            performFreeKick(player, BallKickEvent.KickType.SHOT);
            return;
        }
        kick(player, BallKickEvent.KickType.SHOT);
    }

    public void pass(Player player) {
        if (state == GameState.THROW_IN) {
            performThrowIn(player, BallKickEvent.KickType.THROW_IN);
            return;
        }
        if (state == GameState.OFFSIDE) {
            performFreeKick(player, BallKickEvent.KickType.PASS);
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
        if (settings.throwInAutoTaker && settings.throwInTakerOnlyKick
                && throwInTakerId != null && !player.getUniqueId().equals(throwInTakerId)) {
            plugin.messages().send(player, "throw-in-wrong-taker", "player", throwInTakerName());
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

        releaseThrowInKick(sp, event.getImpulse(), event.getSpin());

        Location loc = ball.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private void performBotThrowIn(MatchBot bot, BallKickEvent.KickType type) {
        if (state != GameState.THROW_IN || throwInTeam == null || ball == null) {
            return;
        }
        if (throwInTakerId == null || !throwInTakerId.equals(bot.getUuid())) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        if (sp.getTeam() != throwInTeam || !reachable(bot)) {
            return;
        }

        BallKickEvent.KickType mechanicsType = type == BallKickEvent.KickType.SHOT
                ? BallKickEvent.KickType.SHOT
                : BallKickEvent.KickType.THROW_IN;
        Mechanics.Kick kick = Mechanics.compute(bot, settings, mechanicsType, 0);

        releaseThrowInKick(sp, kick.impulse, kick.spin);
        bot.swingArm();

        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
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

        beginOffsidePhase(player, sp.getTeam(), type);
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
        if (state == GameState.THROW_IN || state == GameState.OFFSIDE) {
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
                || state == GameState.THROW_IN || state == GameState.OFFSIDE || halftime;
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
            case OFFSIDE:
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
            case OFFSIDE:
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
        if (state == GameState.OFFSIDE && settings.actionBarHud) {
            return;
        }
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
            case OFFSIDE:
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
    // Server-wide match viewing (score + TV ticker for all online players)
    // ==================================================================

    /** Whether this match should be shown to everyone on the server. */
    public boolean isBroadcastEligible() {
        if (ended) {
            return false;
        }
        return switch (state) {
            case COUNTDOWN, KICKOFF, PLAYING, THROW_IN, OFFSIDE, HALFTIME,
                 EXTRA_TIME, PENALTIES, REPLAY -> true;
            default -> false;
        };
    }

    /** Higher rank wins when multiple matches run at once. */
    public int broadcastPriority() {
        if (!isBroadcastEligible()) {
            return 0;
        }
        return switch (state) {
            case PLAYING, EXTRA_TIME, THROW_IN, OFFSIDE -> 100;
            case KICKOFF -> 90;
            case REPLAY -> 85;
            case HALFTIME -> 80;
            case COUNTDOWN -> 70;
            case PENALTIES -> 60;
            default -> 0;
        };
    }

    /** Attach TV ticker and sidebar score to a non-participant (or refresh a participant). */
    public void attachServerViewer(Player player) {
        if (player == null || !player.isOnline() || ended || !isBroadcastEligible()) {
            return;
        }
        if (settings.broadcastHud && broadcastHud != null) {
            broadcastHud.attach(player);
        }
        if (settings.scoreboardEnabled && scoreboard != null) {
            scoreboard.apply(player);
        }
        if (!players.containsKey(player.getUniqueId())) {
            serverViewers.add(player.getUniqueId());
        }
    }

    /** Detach server-wide overlays from a spectator. Participants are managed separately. */
    public void detachServerViewer(Player player) {
        if (player == null || players.containsKey(player.getUniqueId())) {
            return;
        }
        if (broadcastHud != null) {
            broadcastHud.detach(player);
        }
        if (scoreboard != null) {
            scoreboard.clear(player);
        }
        serverViewers.remove(player.getUniqueId());
    }

    /** Remove TV/score overlays from every spectator when the match ends. */
    public void clearServerViewers() {
        for (UUID viewerId : new HashSet<>(serverViewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                detachServerViewer(viewer);
            }
        }
        serverViewers.clear();
        if (scoreboard != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!players.containsKey(online.getUniqueId())) {
                    scoreboard.clear(online);
                }
            }
        }
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

    public OffsideChecker.PitchAxis getPitchAxis() {
        return pitchAxis;
    }

    public TeamSide getBallLastTouchTeam() {
        return ball != null ? ball.getLastTouchTeam() : null;
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

    public int getTeamCount(TeamSide team) {
        return countTeam(team);
    }

    public boolean isTeamFull(TeamSide team) {
        return countTeam(team) >= settings.maxPlayersPerTeam;
    }

    public String getTeamDisplayName(TeamSide team) {
        return teamName(team);
    }

    private int countTeam(TeamSide team) {
        int count = 0;
        for (SoccerPlayer sp : players.values()) {
            if (sp.getTeam() == team) {
                count++;
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() == team) {
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
        return players.size() + bots.size() >= settings.maxPlayersPerTeam * 2;
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
        return players.size() + bots.size();
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
