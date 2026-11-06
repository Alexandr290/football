package com.ballgame.soccer.game;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.arena.BoundaryExit;
import com.ballgame.soccer.arena.OutLineCrossing;
import com.ballgame.soccer.arena.ThrowInGeometry;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.bot.BotAi;
import com.ballgame.soccer.bot.BotFormation;
import com.ballgame.soccer.bot.BotRole;
import com.ballgame.soccer.bot.FormationSpots;
import com.ballgame.soccer.bot.MatchBot;
import com.ballgame.soccer.cinematic.MatchIntro;
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
import com.ballgame.soccer.replay.ReplayFrame;
import com.ballgame.soccer.replay.ReplayRecorder;
import com.ballgame.soccer.referee.AutoReferee;
import com.ballgame.soccer.referee.RefereeController;
import com.ballgame.soccer.referee.RefereeItems;
import com.ballgame.soccer.referee.RefereeRestartType;
import com.ballgame.soccer.util.ActionBar;
import com.ballgame.soccer.util.Cuboid;
import com.ballgame.soccer.util.Items;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
    private final java.util.EnumMap<TeamSide, BotFormation> teamFormations =
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
    /** Ticks lost to replays, restarts and celebrations this regulation half. */
    private int halfStoppageAccumTicks;
    private boolean inStoppageTime;
    private int stoppageSecondsRemaining;
    private int stoppageSecondsAdded;
    private int countdown;
    private int tickInSecond;
    /** Seconds left to wait for humans before auto-filling bots (0 = inactive). */
    private int lobbyFillGraceRemaining;
    private boolean suppressCountdownOnBotAdd;
    private long matchTick;

    private boolean celebrating;
    private int celebrationTicks;

    private long redPossessionTicks;
    private long bluePossessionTicks;
    private int redPenaltyFouls;
    private int bluePenaltyFouls;
    private UUID pendingPasserId;

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
    private boolean varReviewPlayback;
    private GameState resumeAfterRefereeStop = GameState.PLAYING;
    private boolean refereePenaltyRestart;
    private UUID penaltyKeeperId;
    private final Map<UUID, Location> penaltyLineupSpots = new HashMap<>();
    private boolean penaltyKeeperDived;
    private int penaltyKeeperReleaseTicks;

    private static final int SHOOTOUT_KICKS_PER_SIDE = 5;

    private boolean shootoutActive;
    private int shootoutRedGoals;
    private int shootoutBlueGoals;
    private int shootoutRedTaken;
    private int shootoutBlueTaken;
    private TeamSide shootoutTurn = TeamSide.RED;
    private List<UUID> shootoutRedOrder = List.of();
    private List<UUID> shootoutBlueOrder = List.of();
    private boolean shootoutAwaitingResult;
    private int shootoutResultTicks;
    private TeamSide shootoutKickingTeam;
    private int shootoutInterKickCooldown;
    private boolean shootoutScheduleNextKick;
    private TeamSide matchWinnerOverride;
    private boolean humanRefereeMatch;
    private boolean awaitingRefereeDecision;
    private List<ReplayFrame> refereeVarClip;

    private CrowdAudio crowd;
    private RefereeController referee;
    private AutoReferee autoReferee;

    private String championshipId;
    private String fixtureId;
    private String matchRedName;
    private String matchBlueName;
    private String redNationCode;
    private String blueNationCode;
    private Color redKitColor;
    private Color blueKitColor;
    private String redKitChatColor;
    private String blueKitChatColor;
    /** Full-AI cup match with no human players on the pitch. */
    private boolean botOnlyMatch;
    /** Human-claimed starting-XI slots per side (slot index 0..10 → player UUID). */
    private final java.util.EnumMap<TeamSide, java.util.Map<Integer, UUID>> squadClaims =
            new java.util.EnumMap<>(TeamSide.class);

    /** Sticky per-team chaser so only one bot hunts the ball and roles stay stable. */
    private final java.util.EnumMap<TeamSide, UUID> stickyBallChaserId = new java.util.EnumMap<>(TeamSide.class);
    private final java.util.EnumMap<TeamSide, Boolean> humanChasingBall = new java.util.EnumMap<>(TeamSide.class);
    private long ballChaserRefreshTick = -1;
    private static final int REPLAY_CAPTURE_INTERVAL = 1;
    private static final int KICK_TRAP_IMMUNITY_TICKS = 12;

    /** Goal details held until replay finishes. */
    private TeamSide pendingCelebrationTeam;
    private SoccerPlayer pendingScorer;
    private SoccerPlayer pendingAssist;
    private boolean pendingOwnGoal;

    private final Map<UUID, Long> kickTrapImmunityUntil = new HashMap<>();

    /** Broadcast pre-match show running before the first kickoff (null otherwise). */
    private MatchIntro intro;
    /** Team taking the next kickoff (coin toss, alternating halves, conceding side after a goal). */
    private TeamSide kickoffTeam;
    private TeamSide openingKickoffTeam;
    /** Player or bot standing over the ball for the pending kickoff. */
    private UUID kickoffTakerId;
    /** Ticks the kickoff taker has to get play going before everyone else may close in. */
    private int kickoffPendingTicks;
    /** Scorer of the goal being celebrated (bot or human), for the celebration run. */
    private UUID celebrationScorerId;
    private TeamSide celebrationTeam;

    /** Recent ball positions: bots react to where the ball was a few ticks ago. */
    private static final int BALL_MEMORY_TICKS = 12;
    private final Location[] ballMemory = new Location[BALL_MEMORY_TICKS];
    private final Vector[] ballVelocityMemory = new Vector[BALL_MEMORY_TICKS];
    /** Bot goalkeeper holding the ball in his hands (null when the ball is in play). */
    private UUID keeperHolderId;
    private int keeperHoldTicks;

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

        if (state == GameState.WAITING && !humanRefereeMatch && !soloMatch) {
            onLobbyPresenceChanged();
        }
        return true;
    }

    public boolean addReferee(Player player) {
        if (players.containsKey(player.getUniqueId())) {
            return false;
        }
        if (hasHumanReferee()) {
            return false;
        }
        SoccerPlayer sp = SoccerPlayer.createReferee(player, settings.maxStamina);
        snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        players.put(player.getUniqueId(), sp);
        humanRefereeMatch = true;

        prepareReferee(player);
        Location mid = refereeStandLocation();
        if (mid != null) {
            player.teleport(mid);
        }
        if (scoreboard != null) {
            scoreboard.apply(player);
        }
        attachServerViewer(player);
        plugin.messages().send(player, "referee-joined", "arena", arena.getName());
        broadcast(plugin.messages().format("referee-joined-broadcast", "player", player.getName()));

        // Human referee starts the match manually (whistle / start item).
        if (state == GameState.WAITING && hasEnoughPlayers()) {
            plugin.messages().send(player, "referee-ready-to-start");
        }
        return true;
    }

    private Location refereeStandLocation() {
        Location spawn = arena.getBallSpawn();
        if (spawn != null) {
            return spawn.clone().add(0, 0, 0);
        }
        Location lobby = arena.getLobby();
        return lobby != null ? lobby.clone() : null;
    }

    private void prepareReferee(Player player) {
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
        Color black = Color.fromRGB(30, 30, 30);
        player.getInventory().setChestplate(dyed(Material.LEATHER_CHESTPLATE, black));
        player.getInventory().setLeggings(dyed(Material.LEATHER_LEGGINGS, Color.BLACK));
        player.getInventory().setBoots(dyed(Material.LEATHER_BOOTS, Color.BLACK));
        RefereeItems.equip(player, plugin);
    }

    public boolean hasHumanReferee() {
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bot referee stops play and awards a restart after detecting a foul.
     */
    public void applyBotRefereeSanction(UUID offenderId, String violationKey,
                                        RefereeRestartType restartType, TeamSide ballTeam) {
        if (hasHumanReferee() || offenderId == null || restartType == null || ballTeam == null) {
            return;
        }
        if (restartType == RefereeRestartType.PENALTY) {
            return;
        }
        SoccerPlayer offender = resolveParticipant(offenderId);
        if (offender == null || offender.isReferee()) {
            return;
        }

        resumeAfterRefereeStop = state;
        awaitingRefereeDecision = true;
        captureRefereeVarClip();
        if (ball != null) {
            ball.setFrozen(true);
        }
        setState(GameState.REFEREE_STOP);
        if (referee != null && ball != null && ball.getLocation() != null) {
            referee.onOffside(ball.getLocation());
        } else {
            broadcast(plugin.messages().raw("referee-whistle-stop"));
        }

        plugin.messages().sendAll(participants(), violationKey,
                "player", offender.getName(),
                "team", teamName(ballTeam));

        applyRefereeDecision(null, offenderId, restartType, ballTeam);
    }

    public void onBotSlideTackle(MatchBot bot) {
        if (autoReferee != null) {
            autoReferee.onBotSlideTackle(this, bot);
        }
    }

    public Location participantLocation(UUID uuid) {
        SoccerPlayer sp = players.get(uuid);
        if (sp != null) {
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                return player.getLocation();
            }
        }
        MatchBot bot = bots.get(uuid);
        return bot != null ? bot.getLocation() : null;
    }

    public java.util.List<SoccerPlayer> getActiveFieldPlayers() {
        java.util.List<SoccerPlayer> list = new java.util.ArrayList<>();
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee() && !sp.isSentOff() && sp.getTeam() != null) {
                list.add(sp);
            }
        }
        for (MatchBot bot : bots.values()) {
            list.add(bot.getProfile());
        }
        return list;
    }

    public boolean isHumanReferee(UUID uuid) {
        SoccerPlayer sp = players.get(uuid);
        return sp != null && sp.isReferee();
    }

    public boolean isAwaitingRefereeDecision() {
        return awaitingRefereeDecision || state == GameState.REFEREE_STOP;
    }

    public void toggleRefereeWhistle(Player referee) {
        if (!isHumanReferee(referee.getUniqueId())) {
            return;
        }
        if (state == GameState.WAITING) {
            refereeStartMatch(referee);
            return;
        }
        if (state == GameState.REFEREE_STOP) {
            plugin.messages().send(referee, "referee-whistle-await-decision");
            return;
        }
        if (!state.isLive() && state != GameState.THROW_IN && state != GameState.OFFSIDE) {
            plugin.messages().send(referee, "referee-whistle-no-play");
            return;
        }
        resumeAfterRefereeStop = state;
        awaitingRefereeDecision = true;
        captureRefereeVarClip();
        if (ball != null) {
            ball.setFrozen(true);
        }
        setState(GameState.REFEREE_STOP);
        broadcast(plugin.messages().raw("referee-whistle-stop"));
        referee.playSound(referee.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 1f, 1f);
        forEachPlayer(p -> p.sendTitle(
                Text.color(plugin.messages().raw("referee-whistle-title")),
                Text.color(plugin.messages().raw("referee-whistle-subtitle")),
                5, 30, 10));
        plugin.messages().send(referee, "referee-whistle-hint");
    }

    private void captureRefereeVarClip() {
        refereeVarClip = null;
        if (replayRecorder == null || !replayRecorder.hasEnoughFrames()) {
            return;
        }
        refereeVarClip = replayRecorder.snapshotClip(5);
    }

    public void resumeRefereePlay() {
        if (state != GameState.REFEREE_STOP) {
            return;
        }
        awaitingRefereeDecision = false;
        GameState resume = resumeAfterRefereeStop != null ? resumeAfterRefereeStop : GameState.PLAYING;
        setState(resume);
        if (ball != null && (resume.isLive() || resume == GameState.THROW_IN || resume == GameState.OFFSIDE)) {
            ball.setFrozen(false);
        }
        broadcast(plugin.messages().raw("referee-whistle-resume"));
    }

    public void refereeStartMatch(Player referee) {
        if (!isHumanReferee(referee.getUniqueId())) {
            return;
        }
        if (state != GameState.WAITING) {
            plugin.messages().send(referee, "referee-start-not-waiting");
            return;
        }
        if (!hasEnoughPlayers()) {
            plugin.messages().send(referee, "referee-start-need-players");
            return;
        }
        broadcast(plugin.messages().format("referee-start-match", "player", referee.getName()));
        startPreMatchCountdown();
    }

    public void refereeEndMatch(Player referee) {
        if (!isHumanReferee(referee.getUniqueId())) {
            return;
        }
        if (state == GameState.WAITING || state == GameState.ENDED) {
            plugin.messages().send(referee, "referee-end-not-active");
            return;
        }
        broadcast(plugin.messages().format("referee-end-match", "player", referee.getName()));
        forceEnd();
    }

    /**
     * Adds manual stoppage / injury time. Works during regulation play or while
     * already in stoppage — extends the clock by the given seconds (capped).
     */
    public void refereeAddStoppageTime(Player referee, int seconds) {
        if (!isHumanReferee(referee.getUniqueId())) {
            return;
        }
        if (seconds <= 0) {
            return;
        }
        if (state != GameState.PLAYING && state != GameState.THROW_IN && state != GameState.OFFSIDE
                && state != GameState.REFEREE_STOP && state != GameState.VAR_REVIEW
                && state != GameState.EXTRA_TIME && state != GameState.KICKOFF) {
            plugin.messages().send(referee, "referee-add-time-unavailable");
            return;
        }
        int add = Math.min(seconds, Math.max(1, settings.stoppageTimeMaxSeconds));
        if (state == GameState.EXTRA_TIME) {
            secondsRemaining += add;
            broadcast(plugin.messages().format("referee-extra-time-added",
                    "time", Text.formatTime(add), "player", referee.getName()));
        } else if (inStoppageTime) {
            stoppageSecondsAdded += add;
            stoppageSecondsRemaining += add;
            broadcast(plugin.messages().format("referee-stoppage-added",
                    "time", Text.formatTime(add), "player", referee.getName()));
        } else {
            secondsRemaining += add;
            halfStoppageAccumTicks += add * 20;
            broadcast(plugin.messages().format("referee-stoppage-added",
                    "time", Text.formatTime(add), "player", referee.getName()));
        }
        referee.playSound(referee.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        plugin.messages().send(referee, "referee-add-time-confirm",
                "time", Text.formatTime(add), "remaining", formatMatchClock());
    }

    public void beginVarReview(Player referee) {
        beginVarReview(referee, 5);
    }

    public void beginVarReview(Player referee, int clipSeconds) {
        if (!isHumanReferee(referee.getUniqueId())) {
            return;
        }
        List<ReplayFrame> clip = refereeVarClip;
        if (clip == null || clip.size() < 10) {
            if (replayRecorder == null || !replayRecorder.hasEnoughFrames()) {
                plugin.messages().send(referee, "referee-var-unavailable");
                return;
            }
            clip = replayRecorder.extractClip(Math.max(3, clipSeconds));
        }
        if (clip == null || clip.size() < 10) {
            plugin.messages().send(referee, "referee-var-unavailable");
            return;
        }
        if (goalReplay != null) {
            return;
        }
        if (state == GameState.REFEREE_STOP) {
            // keep resumeAfterRefereeStop
        } else if (state.isLive() || state == GameState.THROW_IN || state == GameState.OFFSIDE) {
            resumeAfterRefereeStop = state;
        } else {
            resumeAfterRefereeStop = GameState.PLAYING;
        }
        if (ball != null) {
            ball.setFrozen(true);
        }
        varReviewPlayback = true;
        stateBeforeReplay = state;
        goalReplay = GoalReplay.varReview(plugin, this, clip);
        setState(GameState.VAR_REVIEW);
        goalReplay.begin();
        broadcast(plugin.messages().raw("referee-var-started"));
    }

    public void applyRefereeDecision(Player referee, UUID offenderId,
                                     RefereeRestartType restartType, TeamSide ballTeam) {
        if (offenderId == null || restartType == null || ballTeam == null) {
            return;
        }
        // In-match fouls never restart with a penalty — only the shootout uses them.
        if (restartType == RefereeRestartType.PENALTY && state != GameState.PENALTIES) {
            return;
        }
        if (referee != null && !isHumanReferee(referee.getUniqueId())) {
            return;
        }
        if (referee == null && hasHumanReferee()) {
            return;
        }
        SoccerPlayer offender = resolveParticipant(offenderId);
        if (offender == null || offender.isReferee()) {
            return;
        }

        if (restartType == RefereeRestartType.PENALTY) {
            offender.addFoul();
            if (offender.getTeam() == TeamSide.RED) {
                redPenaltyFouls++;
            } else if (offender.getTeam() == TeamSide.BLUE) {
                bluePenaltyFouls++;
            }
        }

        awaitingRefereeDecision = false;
        refereeVarClip = null;

        Location restart = computeRefereeRestartSpot(restartType, ballTeam);
        if (restart == null) {
            restart = ball != null ? ball.getLocation() : arena.getBallSpawn();
        }

        resumeAfterFreeKick = resumeAfterRefereeStop != null ? resumeAfterRefereeStop : GameState.PLAYING;
        freeKickTeam = ballTeam;
        refereePenaltyRestart = restartType == RefereeRestartType.PENALTY;

        if (ball != null && restart != null) {
            ball.teleport(restart);
            ball.setFrozen(true);
            ball.clearTouchHistory();
        }

        setState(GameState.OFFSIDE);
        assignRefereeKickTaker(restart, ballTeam);
        notifyRefereeRestart(restartType, offender, ballTeam);
        playRefereeDecisionEffects(offender, restartType, ballTeam);
        scheduleRefereeRestartKick();
    }

    private void playRefereeDecisionEffects(SoccerPlayer offender,
                                            RefereeRestartType restartType, TeamSide ballTeam) {
        String restartName = switch (restartType) {
            case PENALTY -> plugin.messages().raw("referee-restart-penalty");
            case FREE_KICK -> plugin.messages().raw("referee-restart-freekick");
            case CORNER_KICK -> plugin.messages().raw("referee-restart-corner");
            case DROP_BALL -> plugin.messages().raw("referee-restart-drop");
        };
        forEachPlayer(p -> {
            p.sendTitle(
                    Text.color(plugin.messages().raw("referee-menu-title")),
                    Text.color("&f" + offender.getName() + " &7→ &f" + restartName
                            + " &7(" + teamName(ballTeam) + ")"),
                    5, 50, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.7f);
        });
    }

    private void assignRefereeKickTaker(Location restart, TeamSide ballTeam) {
        freeKickSpot = restart != null ? restart.clone() : null;
        freeKickTakerId = null;
        penaltyKeeperId = null;
        if (refereePenaltyRestart) {
            assignPenaltyParticipants(ballTeam, restart);
            return;
        }
        SoccerPlayer taker = findNearestParticipant(ballTeam, restart);
        if (taker != null && taker.getBukkitPlayer() != null && taker.getBukkitPlayer().isOnline()) {
            applyFreeKickTaker(taker, restart, ballTeam, true);
        }
    }

    private void assignPenaltyParticipants(TeamSide attacking, Location spot) {
        SoccerPlayer taker = findPenaltyTaker(attacking);
        if (taker != null) {
            freeKickTakerId = taker.getUuid();
        }
        assignPenaltyGoalkeeper(attacking.opposite(), spot);
        if (taker != null) {
            placePenaltyTaker(taker, spot, attacking);
        }
        assignPenaltyLineup(attacking, spot, taker);
    }

    /**
     * Penalty taker: teammate with the most goals in this match (humans and bots).
     */
    private SoccerPlayer findPenaltyTaker(TeamSide team) {
        SoccerPlayer best = null;
        int bestGoals = -1;

        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff() || sp.getTeam() != team || sp.isBot()) {
                continue;
            }
            int goals = sp.getGoals();
            if (goals > bestGoals) {
                bestGoals = goals;
                best = sp;
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() != team) {
                continue;
            }
            SoccerPlayer sp = bot.getProfile();
            if (sp.isSentOff()) {
                continue;
            }
            int goals = sp.getGoals();
            if (goals > bestGoals) {
                bestGoals = goals;
                best = sp;
            }
        }
        if (best != null) {
            return best;
        }
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee() && !sp.isSentOff() && sp.getTeam() == team && !sp.isBot()) {
                return sp;
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() == team) {
                return bot.getProfile();
            }
        }
        return null;
    }

    private void assignPenaltyLineup(TeamSide attacking, Location penaltySpot, SoccerPlayer taker) {
        penaltyLineupSpots.clear();
        if (penaltySpot == null || pitchAxis == null) {
            return;
        }

        Vector attack = pitchAxis.attackDirection(attacking).clone().normalize();
        Vector lateral = new Vector(-attack.getZ(), 0, attack.getX()).normalize();
        double backDist = 9.0;
        Location lineCenter = penaltySpot.clone().add(attack.clone().multiply(-backDist));

        Cuboid field = arena.getField();
        if (field != null && field.getCenter() != null) {
            Location fieldCenter = field.getCenter();
            if (Math.abs(attack.getX()) > Math.abs(attack.getZ())) {
                lineCenter.setZ(fieldCenter.getZ());
            } else {
                lineCenter.setX(fieldCenter.getX());
            }
            lineCenter.setY(pitchGroundY());
        }

        java.util.List<SoccerPlayer> others = new java.util.ArrayList<>();
        for (SoccerPlayer sp : getActiveFieldPlayers()) {
            if (sp.isReferee() || sp.isSentOff()) {
                continue;
            }
            if (taker != null && taker.getUuid().equals(sp.getUuid())) {
                continue;
            }
            if (penaltyKeeperId != null && penaltyKeeperId.equals(sp.getUuid())) {
                continue;
            }
            others.add(sp);
        }

        double spacing = 1.8;
        int count = others.size();
        for (int i = 0; i < count; i++) {
            double offset = (i - (count - 1) / 2.0) * spacing;
            Location pos = lineCenter.clone().add(lateral.clone().multiply(offset));
            pos.setYaw(OffsideRestartHelper.yawFromDirection(attack));
            pos.setPitch(0f);
            SoccerPlayer sp = others.get(i);
            penaltyLineupSpots.put(sp.getUuid(), pos);
            placeParticipantAt(sp, pos);
        }
    }

    private void placeParticipantAt(SoccerPlayer sp, Location pos) {
        if (sp.isBot()) {
            MatchBot bot = bots.get(sp.getUuid());
            if (bot != null) {
                bot.setLocation(pos);
            }
            return;
        }
        Player player = sp.getBukkitPlayer();
        if (player != null && player.isOnline()) {
            player.teleport(pos);
            player.setWalkSpeed(0.0f);
        }
    }

    private void placePenaltyTaker(SoccerPlayer taker, Location spot, TeamSide attacking) {
        if (spot == null || pitchAxis == null || taker == null) {
            return;
        }
        Location takerSpot = OffsideRestartHelper.computeTakerSpot(spot, pitchAxis, attacking);
        Cuboid defendGoal = arena.getGoal(attacking.opposite());
        Location aim = defendGoal != null ? defendGoal.getCenter() : spot;

        if (taker.isBot()) {
            MatchBot bot = bots.get(taker.getUuid());
            if (bot != null) {
                bot.setLocation(takerSpot);
                if (aim != null) {
                    bot.faceLocation(aim);
                }
            }
            return;
        }
        Player player = taker.getBukkitPlayer();
        if (player != null && player.isOnline()) {
            player.teleport(takerSpot);
            if (settings.offsideTakerGlow) {
                player.setGlowing(true);
            }
            player.setWalkSpeed(0.2f);
            scheduleMovementReset();
        }
    }

    private void assignPenaltyGoalkeeper(TeamSide defending, Location penaltySpot) {
        Location keeperSpot = computeGoalkeeperSpot(defending);
        if (keeperSpot == null) {
            return;
        }

        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff() || sp.isBot() || sp.getTeam() != defending) {
                continue;
            }
            penaltyKeeperId = sp.getUuid();
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                player.teleport(keeperSpot);
                player.setWalkSpeed(0.2f);
            }
            return;
        }

        MatchBot keeperBot = findGoalkeeperBot(defending);
        if (keeperBot != null) {
            penaltyKeeperId = keeperBot.getUuid();
            keeperBot.setLocation(keeperSpot);
            if (penaltySpot != null) {
                keeperBot.faceLocation(penaltySpot);
            }
        }
    }

    private MatchBot findGoalkeeperBot(TeamSide team) {
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() == team && bot.getRole() == BotRole.GOALKEEPER) {
                return bot;
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() == team) {
                return bot;
            }
        }
        return null;
    }

    private Location computeGoalkeeperSpot(TeamSide defending) {
        return BotAi.keeperSpawnSpot(this, defending);
    }

    private SoccerPlayer findNearestParticipant(TeamSide team, Location spot) {
        if (spot == null) {
            return null;
        }
        SoccerPlayer human = OffsideRestartHelper.findNearestTaker(players, team, spot);
        if (human != null) {
            return human;
        }
        MatchBot nearestBot = null;
        double best = Double.MAX_VALUE;
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() != team || bot.getLocation() == null) {
                continue;
            }
            double dist = OffsideRestartHelper.horizontalDistance(bot.getLocation(), spot);
            if (dist < best) {
                best = dist;
                nearestBot = bot;
            }
        }
        return nearestBot != null ? nearestBot.getProfile() : null;
    }

    private void scheduleRefereeRestartKick() {
        if (freeKickTakerId == null) {
            return;
        }
        SoccerPlayer taker = getPlayer(freeKickTakerId);
        if (taker == null || !taker.isBot()) {
            return;
        }
        long delay = refereePenaltyRestart ? 60L : 35L;
        Bukkit.getScheduler().runTaskLater(plugin, this::executeBotRefereeRestartKick, delay);
    }

    private boolean teamHasHumanPlayers(TeamSide team) {
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee() && !sp.isSentOff() && !sp.isBot() && sp.getTeam() == team) {
                return true;
            }
        }
        return false;
    }

    private void executeBotRefereeRestartKick() {
        if (state != GameState.OFFSIDE || ball == null || freeKickTeam == null) {
            return;
        }
        Location ballLoc = ball.getLocation();
        if (ballLoc == null) {
            return;
        }
        MatchBot taker = freeKickTakerId != null ? bots.get(freeKickTakerId) : null;
        if (taker == null) {
            double best = Double.MAX_VALUE;
            for (MatchBot bot : bots.values()) {
                if (bot.getTeam() != freeKickTeam || bot.getLocation() == null) {
                    continue;
                }
                double dist = OffsideRestartHelper.horizontalDistance(bot.getLocation(), ballLoc);
                if (dist < best) {
                    best = dist;
                    taker = bot;
                }
            }
        }
        if (taker == null) {
            endFreeKick();
            return;
        }

        if (refereePenaltyRestart) {
            executeBotPenaltyKick(taker);
            return;
        }

        Location takerSpot = pitchAxis != null
                ? OffsideRestartHelper.computeTakerSpot(ballLoc, pitchAxis, freeKickTeam)
                : ballLoc;
        taker.setLocation(takerSpot);

        Location passTarget = BotAi.restartPassTarget(this, taker, ballLoc);
        if (passTarget != null) {
            double dist = Math.hypot(passTarget.getX() - ballLoc.getX(), passTarget.getZ() - ballLoc.getZ());
            boolean lofted = dist > 22.0;
            taker.faceLocation(passTarget);
            Mechanics.Kick pass = Mechanics.computeBotShotToward(ballLoc, passTarget, settings,
                    lofted ? Math.min(0.75, groundPassFraction(dist) * 0.85) : groundPassFraction(dist),
                    lofted ? 0.3 : 0.0);
            touchBall(taker.getUuid(), freeKickTeam);
            ball.setFrozen(false);
            ball.applyImpulse(pass.impulse, pass.spin);
            taker.getProfile().addTouch();
            registerPass(taker.getProfile());
            markBotKick(taker);
            taker.swingArm();
            endFreeKick();
            beginBotOffsidePhase(taker);
            return;
        }

        Vector impulse;
        Cuboid defendGoal = arena.getGoal(freeKickTeam.opposite());
        if (defendGoal != null && defendGoal.getCenter() != null) {
            impulse = defendGoal.getCenter().toVector().subtract(ballLoc.toVector());
        } else if (pitchAxis != null) {
            impulse = pitchAxis.attackDirection(freeKickTeam).clone();
        } else {
            impulse = new Vector(0, 0, 1);
        }
        impulse.setY(refereePenaltyRestart ? 0.06 : 0.1);
        if (impulse.lengthSquared() > 1e-6) {
            impulse.normalize().multiply(refereePenaltyRestart ? 1.15 : 0.9);
        }

        touchBall(taker.getUuid(), freeKickTeam);
        ball.applyImpulse(impulse, null);
        ball.setFrozen(false);
        taker.getProfile().addTouch();
        if (refereePenaltyRestart) {
            taker.getProfile().addShot();
        } else {
            registerPass(taker.getProfile());
        }
        endFreeKick();
    }

    private void executeBotPenaltyKick(MatchBot taker) {
        if (state != GameState.OFFSIDE || ball == null || freeKickTeam == null) {
            return;
        }
        Location ballLoc = ball.getLocation();
        if (ballLoc == null) {
            return;
        }
        Cuboid defendGoal = arena.getGoal(freeKickTeam.opposite());
        Location aim = defendGoal != null ? randomPenaltyTarget(freeKickTeam) : null;
        if (aim == null) {
            endFreeKick();
            return;
        }

        Location takerSpot = pitchAxis != null && freeKickSpot != null
                ? OffsideRestartHelper.computeTakerSpot(freeKickSpot, pitchAxis, freeKickTeam)
                : ballLoc;
        taker.setLocation(takerSpot);
        taker.faceLocation(aim);

        Mechanics.Kick kick = Mechanics.computeBotShotToward(ballLoc, aim, settings, 0.92, 0.06);
        touchBall(taker.getUuid(), freeKickTeam);
        ball.applyImpulse(kick.impulse, kick.spin);
        ball.setFrozen(false);
        taker.getProfile().addTouch();
        taker.getProfile().addShot();
        taker.swingArm();

        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 6, 0.1, 0.1, 0.1, 0.05);
        }
        endFreeKick();
    }

    private void notifyRefereeRestart(RefereeRestartType restartType,
                                      SoccerPlayer offender, TeamSide ballTeam) {
        String restartKey = switch (restartType) {
            case PENALTY -> "referee-decision-penalty";
            case FREE_KICK -> "referee-decision-freekick";
            case CORNER_KICK -> "referee-decision-corner";
            case DROP_BALL -> "referee-decision-drop";
        };
        broadcast(plugin.messages().format(restartKey,
                "player", offender.getName(),
                "team", teamName(ballTeam)));
        forEachPlayer(p -> p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.7f));
    }

    private Location computeRefereeRestartSpot(RefereeRestartType type, TeamSide ballTeam) {
        if (type == RefereeRestartType.PENALTY) {
            return penaltySpot(ballTeam);
        }
        if (type == RefereeRestartType.CORNER_KICK) {
            return cornerSpot(ballTeam);
        }
        Location incident = ball != null ? ball.getLocation() : null;
        if (incident == null && refereeVarClip != null && !refereeVarClip.isEmpty()) {
            org.bukkit.World world = arena.getBallSpawn() != null ? arena.getBallSpawn().getWorld() : null;
            if (world != null) {
                incident = refereeVarClip.get(refereeVarClip.size() - 1).ballLocation(world);
            }
        }
        if (incident != null) {
            Location spot = incident.clone();
            if (pitchAxis != null) {
                spot = pitchAxis.clampToField(spot);
            }
            return spot;
        }
        return arena.getBallSpawn();
    }

    private Location cornerSpot(TeamSide attacking) {
        Cuboid field = arena.getField();
        if (field == null) {
            return ball != null ? ball.getLocation() : arena.getBallSpawn();
        }
        Cuboid defendGoal = arena.getGoal(attacking.opposite());
        Location goalCenter = defendGoal != null ? defendGoal.getCenter() : field.getCenter();
        if (goalCenter == null) {
            return field.getCenter();
        }

        boolean attackAlongX = field.getSizeX() >= field.getSizeZ();
        double inset = 0.8;
        double y = Math.max(field.getMinY() + 0.1,
                ball != null && ball.getLocation() != null ? ball.getLocation().getY() : field.getMinY() + 0.1);

        Location ballRef = ball != null ? ball.getLocation() : goalCenter;
        double cornerX;
        double cornerZ;
        if (attackAlongX) {
            boolean attackHighX = goalCenter.getX() >= field.getCenter().getX();
            cornerX = attackHighX ? field.getMaxX() - inset : field.getMinX() + inset;
            cornerZ = ballRef.getZ() >= field.getCenter().getZ() ? field.getMaxZ() - inset : field.getMinZ() + inset;
        } else {
            boolean attackHighZ = goalCenter.getZ() >= field.getCenter().getZ();
            cornerZ = attackHighZ ? field.getMaxZ() - inset : field.getMinZ() + inset;
            cornerX = ballRef.getX() >= field.getCenter().getX() ? field.getMaxX() - inset : field.getMinX() + inset;
        }
        return new Location(field.getWorld(), cornerX, y, cornerZ);
    }

    private double pitchGroundY() {
        Cuboid field = arena.getField();
        return field != null ? field.getMinY() + 0.05 : 64.0;
    }

    /**
     * Centre of the goal mouth on the goal line, aligned with the pitch centre
     * (not the goal cuboid centre, which may be offset laterally).
     */
    private Location goalMouthOnPitch(TeamSide defending) {
        Cuboid goal = arena.getGoal(defending);
        Cuboid field = arena.getField();
        if (goal == null || field == null || goal.getWorld() == null) {
            return goal != null ? goal.getCenter() : null;
        }
        Location fieldCenter = field.getCenter();
        if (fieldCenter == null) {
            return goal.getCenter();
        }

        double goalMidX = (goal.getMinX() + goal.getMaxX()) * 0.5;
        double goalMidZ = (goal.getMinZ() + goal.getMaxZ()) * 0.5;
        double towardX = fieldCenter.getX() - goalMidX;
        double towardZ = fieldCenter.getZ() - goalMidZ;
        double y = pitchGroundY();

        double x;
        double z;
        if (Math.abs(towardX) >= Math.abs(towardZ)) {
            z = fieldCenter.getZ();
            x = towardX >= 0.0 ? goal.getMaxX() : goal.getMinX();
        } else {
            x = fieldCenter.getX();
            z = towardZ >= 0.0 ? goal.getMaxZ() : goal.getMinZ();
        }
        return new Location(goal.getWorld(), x, y, z);
    }

    private Location penaltySpot(TeamSide attacking) {
        Location marked = arena.getPenaltySpotForAttack(attacking);
        if (marked != null && marked.getWorld() != null) {
            Location spot = marked.clone();
            spot.setY(pitchGroundY());
            if (pitchAxis != null) {
                Vector attack = pitchAxis.attackDirection(attacking).clone().normalize();
                spot.setYaw(OffsideRestartHelper.yawFromDirection(attack));
                spot.setPitch(0f);
            }
            return spot;
        }
        if (pitchAxis == null) {
            return arena.getBallSpawn();
        }
        Location mouth = goalMouthOnPitch(attacking.opposite());
        if (mouth == null) {
            return arena.getBallSpawn();
        }
        Vector attack = pitchAxis.attackDirection(attacking).clone().normalize();
        Location spot = mouth.clone().subtract(attack.multiply(11.0));
        spot.setY(pitchGroundY());
        spot.setYaw(OffsideRestartHelper.yawFromDirection(attack));
        spot.setPitch(0f);
        return spot;
    }

    /**
     * Random penalty target: left, center, or right (low shot).
     */
    private Location randomPenaltyTarget(TeamSide attacking) {
        Cuboid goal = arena.getGoal(attacking.opposite());
        if (goal == null || pitchAxis == null) {
            return null;
        }
        Location center = goalMouthOnPitch(attacking.opposite());
        if (center == null) {
            return null;
        }
        Vector attack = pitchAxis.attackDirection(attacking).clone().normalize();
        Vector lateral = new Vector(-attack.getZ(), 0, attack.getX()).normalize();

        double widthX = goal.getMaxX() - goal.getMinX();
        double widthZ = goal.getMaxZ() - goal.getMinZ();
        double halfWidth = Math.max(widthX, widthZ) * 0.32;
        double groundY = pitchGroundY() + 0.25;

        int pick = ThreadLocalRandom.current().nextInt(3);
        Location target = center.clone();
        switch (pick) {
            case 1 -> target.add(lateral.clone().multiply(halfWidth));
            case 2 -> target.add(lateral.clone().multiply(-halfWidth));
            default -> { /* center */ }
        }
        target.setY(groundY);
        return target;
    }

    private void sendOffPlayer(SoccerPlayer sp) {
        sp.setSentOff(true);
        Player player = sp.getBukkitPlayer();
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setGameMode(GameMode.SPECTATOR);
        Location spec = arena.getSpectator() != null ? arena.getSpectator() : arena.getLobby();
        if (spec != null) {
            player.teleport(spec);
        }
        plugin.messages().send(player, "referee-sent-off");
    }

    public String teamDisplayName(TeamSide team) {
        return teamName(team);
    }

    public void removePlayer(Player player, boolean restore) {
        SoccerPlayer sp = players.remove(player.getUniqueId());
        if (sp == null) {
            return;
        }
        if (intro != null) {
            intro.onParticipantLeft(player);
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

        if (sp.isReferee()) {
            humanRefereeMatch = hasHumanReferee();
        }

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

        if (state == GameState.WAITING && countHumanFieldPlayers() == 0) {
            cancelLobbyGrace();
        }

        // If the match is live and a team becomes empty, end it.
        if (state.isLive() || state == GameState.KICKOFF || state == GameState.HALFTIME
                || state == GameState.THROW_IN || state == GameState.OFFSIDE
                || state == GameState.PENALTIES || state == GameState.REPLAY
                || state == GameState.VAR_REVIEW || state == GameState.REFEREE_STOP
                || state == GameState.INTRO) {
            if (!sp.isReferee()
                    && (countTeam(TeamSide.RED) == 0 || countTeam(TeamSide.BLUE) == 0)) {
                if (!livePlayStarted && (state == GameState.KICKOFF || state == GameState.INTRO)) {
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
        Color color = getTeamKitColor(team);
        player.getInventory().setHelmet(dyed(Material.LEATHER_HELMET, color));
        player.getInventory().setChestplate(dyed(Material.LEATHER_CHESTPLATE, color));
        player.getInventory().setLeggings(dyed(Material.LEATHER_LEGGINGS, color));
        player.getInventory().setBoots(dyed(Material.LEATHER_BOOTS, color));
        equipCrossPassTrigger(player);
    }

    public Color getTeamKitColor(TeamSide team) {
        if (team == TeamSide.RED && redKitColor != null) {
            return redKitColor;
        }
        if (team == TeamSide.BLUE && blueKitColor != null) {
            return blueKitColor;
        }
        return team == TeamSide.RED ? Color.fromRGB(210, 45, 45) : Color.fromRGB(45, 85, 210);
    }

    public String getTeamKitChatColor(TeamSide team) {
        if (team == TeamSide.RED && redKitChatColor != null) {
            return redKitChatColor;
        }
        if (team == TeamSide.BLUE && blueKitChatColor != null) {
            return blueKitChatColor;
        }
        return team == TeamSide.RED ? settings.redChatColor : settings.blueChatColor;
    }

    private void refreshKitColors() {
        com.ballgame.soccer.fifa.FifaNation redNation = redNationCode != null
                ? plugin.fifaCatalog().get(redNationCode) : null;
        com.ballgame.soccer.fifa.FifaNation blueNation = blueNationCode != null
                ? plugin.fifaCatalog().get(blueNationCode) : null;
        com.ballgame.soccer.fifa.NationKitColors.KitPair kits =
                com.ballgame.soccer.fifa.NationKitColors.resolve(redNation, blueNation);
        redKitColor = kits.red();
        blueKitColor = kits.blue();
        redKitChatColor = kits.redChat();
        blueKitChatColor = kits.blueChat();
    }

    private void refreshHumanKitVisuals() {
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff()) {
                continue;
            }
            Player p = sp.getBukkitPlayer();
            if (p != null) {
                equipTeamColors(p, sp.getTeam());
            }
        }
    }

    public void refreshBotKitVisuals() {
        for (MatchBot bot : bots.values()) {
            bot.refreshKitVisual();
        }
    }

    public void setBotOnlyMatch(boolean botOnlyMatch) {
        this.botOnlyMatch = botOnlyMatch;
    }

    public boolean isBotOnlyMatch() {
        return botOnlyMatch;
    }

    /** Starts countdown when the lobby is filled with bots only. */
    public void tryStartBotOnlyLobby() {
        if (!botOnlyMatch || state != GameState.WAITING || humanRefereeMatch) {
            return;
        }
        if (hasEnoughPlayers() && countHumanFieldPlayers() == 0) {
            cancelLobbyGrace();
            startPreMatchCountdown();
        }
    }

    /** Hotbar marker so Q (drop item) always fires for the low cross. */
    private void equipCrossPassTrigger(Player player) {
        ItemStack trigger = Items.named(Material.SLIME_BALL, " ");
        ItemMeta meta = trigger.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            trigger.setItemMeta(meta);
        }
        player.getInventory().setItem(0, trigger);
        player.getInventory().setHeldItemSlot(0);
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
        if (keeperHolderId != null && !newState.isLive()) {
            releaseKeeperHold(false);
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
        kickoffTakerId = null;
        kickoffPendingTicks = 0;
        cleanupResourcesOnly();
        crowd = null;
        replayRecorder = null;
        replayAnimations = null;
        pitchAxis = null;
        cancelLobbyGrace();
        setState(GameState.WAITING);
        plugin.games().syncServerViewers();
    }

    public void startCountdown() {
        startPreMatchCountdown();
    }

    /** 3..2..1 countdown before teleporting onto the pitch. */
    public void startPreMatchCountdown() {
        cancelLobbyGrace();
        setState(GameState.COUNTDOWN);
        countdown = settings.kickoffCountdown;
        tickInSecond = 0;
        broadcast(plugin.messages().raw("match-starting-soon"));
        if (crowd != null) {
            crowd.onMatchAnticipation();
        }
        plugin.games().syncServerViewers();
    }

    private void cancelLobbyGrace() {
        lobbyFillGraceRemaining = 0;
    }

    private void onLobbyPresenceChanged() {
        if (state != GameState.WAITING || humanRefereeMatch || soloMatch) {
            return;
        }
        if (hasEnoughHumansPerTeam()) {
            cancelLobbyGrace();
            startPreMatchCountdown();
            return;
        }
        if (lobbyFillGraceRemaining > 0 || countHumanFieldPlayers() <= 0) {
            return;
        }
        if (settings.lobbyBotFillGrace <= 0) {
            autoFillLobbyBots();
            if (hasEnoughPlayers()) {
                startPreMatchCountdown();
            }
            return;
        }
        lobbyFillGraceRemaining = settings.lobbyBotFillGrace;
        broadcast(plugin.messages().format("lobby-waiting-for-players",
                "seconds", String.valueOf(lobbyFillGraceRemaining)));
    }

    private void tickLobbyGraceSecond() {
        if (state != GameState.WAITING || lobbyFillGraceRemaining <= 0
                || humanRefereeMatch || soloMatch) {
            return;
        }
        if (hasEnoughHumansPerTeam()) {
            cancelLobbyGrace();
            startPreMatchCountdown();
            return;
        }
        lobbyFillGraceRemaining--;
        if (lobbyFillGraceRemaining == 10 || lobbyFillGraceRemaining == 5) {
            broadcast(plugin.messages().format("lobby-waiting-for-players",
                    "seconds", String.valueOf(lobbyFillGraceRemaining)));
        }
        if (lobbyFillGraceRemaining <= 0) {
            autoFillLobbyBots();
            if (hasEnoughPlayers()) {
                broadcast(plugin.messages().raw("lobby-bots-filled"));
                startPreMatchCountdown();
            }
        }
    }

    private void autoFillLobbyBots() {
        if (!settings.botsEnabled) {
            return;
        }
        suppressCountdownOnBotAdd = true;
        try {
            fillSquadBots(TeamSide.RED);
            fillSquadBots(TeamSide.BLUE);
            deployFormationLineup();
        } finally {
            suppressCountdownOnBotAdd = false;
        }
    }

    private int countHumanFieldPlayers() {
        int count = 0;
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee() && !sp.isSentOff()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasEnoughHumansPerTeam() {
        int red = 0;
        int blue = 0;
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff()) {
                continue;
            }
            if (sp.getTeam() == TeamSide.RED) {
                red++;
            } else {
                blue++;
            }
        }
        return red >= settings.minPlayersPerTeam && blue >= settings.minPlayersPerTeam;
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
        if (!humanRefereeMatch && settings.refereeEnabled) {
            referee = new RefereeController(plugin, this, arena, settings);
            referee.spawn();
            if (settings.autoRefereeEnabled) {
                autoReferee = new AutoReferee(plugin);
                autoReferee.reset();
            } else {
                autoReferee = null;
            }
        } else {
            autoReferee = null;
        }
        clearOffsidePhase();
        ball.spawn(ballSpawnLocation());
        if (settings.broadcastHud) {
            broadcastHud = new MatchBroadcastHud(plugin);
        }
        decideOpeningKickoff();
        plugin.games().syncServerViewers();
        if (!startIntro()) {
            startHalf();
        }
    }

    /** Coin toss: the winner kicks off the first half, the other side the second. */
    private void decideOpeningKickoff() {
        openingKickoffTeam = settings.kickoffRules && ThreadLocalRandom.current().nextBoolean()
                ? TeamSide.BLUE : TeamSide.RED;
        kickoffTeam = openingKickoffTeam;
        kickoffTakerId = chooseKickoffTaker(kickoffTeam);
        if (settings.kickoffRules) {
            broadcast(plugin.messages().format("kickoff-coin-toss",
                    "team_color", teamChat(openingKickoffTeam),
                    "team", teamName(openingKickoffTeam)));
        }
    }

    private boolean startIntro() {
        if (!settings.introEnabled || intro != null || ended) {
            return false;
        }
        MatchIntro show = new MatchIntro(plugin, this);
        boolean started;
        try {
            started = show.start();
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Pre-match show failed to start", ex);
            started = false;
        }
        if (!started) {
            show.stop();
            return false;
        }
        intro = show;
        setState(GameState.INTRO);
        tickInSecond = 0;
        return true;
    }

    private void tickIntro() {
        MatchIntro show = intro;
        boolean done;
        try {
            done = show == null || show.tick();
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Pre-match show crashed, skipping", ex);
            done = true;
        }
        if (done) {
            finishIntro();
            return;
        }
        if (broadcastHud != null) {
            broadcastHud.setVisible(false);
        }
        if (crowd != null) {
            crowd.tick();
        }
        if (scoreboard != null && matchTick % 5 == 0) {
            refreshScoreboard();
        }
    }

    private void finishIntro() {
        stopIntro();
        if (ended || state != GameState.INTRO) {
            return;
        }
        startHalf();
    }

    private void stopIntro() {
        MatchIntro show = intro;
        intro = null;
        if (show != null) {
            show.stop();
        }
    }

    public boolean isIntroRunning() {
        return intro != null && state == GameState.INTRO;
    }

    public void voteSkipIntro(Player player) {
        if (isIntroRunning()) {
            intro.voteSkip(player);
        }
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
        halfStoppageAccumTicks = 0;
        inStoppageTime = false;
        stoppageSecondsRemaining = 0;
        stoppageSecondsAdded = 0;
        TeamSide opening = openingKickoffTeam != null ? openingKickoffTeam : TeamSide.RED;
        kickoffTeam = currentHalf % 2 == 1 ? opening : opening.opposite();
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
        forgetBallMemory();
        clearOffsidePhase();
        celebrating = false;
        celebrationScorerId = null;
        celebrationTeam = null;
        kickoffPendingTicks = 0;
        kickoffTakerId = chooseKickoffTaker(kickoffTeam);
        for (SoccerPlayer sp : players.values()) {
            Player p = sp.getBukkitPlayer();
            if (p == null) {
                continue;
            }
            if (sp.isReferee()) {
                Location mid = refereeStandLocation();
                if (mid != null) {
                    p.teleport(mid);
                }
                resetParticipantMovement(p);
                continue;
            }
            Location spawn = kickoffSpotFor(sp);
            if (spawn != null) {
                p.teleport(spawn);
            }
            resetParticipantMovement(p);
            equipCrossPassTrigger(p);
            sp.setStamina(settings.maxStamina);
        }
        for (MatchBot bot : bots.values()) {
            Location spot = kickoffSpotFor(bot);
            if (spot != null) {
                bot.setLocation(spot);
                bot.syncVisual();
            }
        }
    }

    /** Kickoff position for a human participant (the taker stands over the ball). */
    public Location kickoffSpotFor(SoccerPlayer sp) {
        if (sp == null) {
            return null;
        }
        if (sp.isReferee()) {
            return refereeStandLocation();
        }
        Location base = resolveHumanFormationSpawn(sp);
        if (base == null) {
            base = arena.getTeamSpawn(sp.getTeam());
        }
        return kickoffAdjusted(sp.getTeam(), base, sp.getUuid().equals(kickoffTakerId));
    }

    public Location kickoffSpotFor(MatchBot bot) {
        if (bot == null) {
            return null;
        }
        Location base = resolveBotSpawn(bot.getTeam(), bot.getFormationSlot());
        return kickoffAdjusted(bot.getTeam(), base, bot.getUuid().equals(kickoffTakerId));
    }

    /**
     * Laws of the game at a kickoff: everyone in their own half, the defending
     * side outside the centre circle, and the taker standing over the ball.
     */
    private Location kickoffAdjusted(TeamSide team, Location base, boolean taker) {
        if (base == null || team == null || !settings.kickoffRules) {
            return base;
        }
        Location center = arena.getBallSpawn();
        OffsideChecker.PitchAxis axis = pitchAxis != null ? pitchAxis : OffsideChecker.PitchAxis.fromArena(arena);
        if (center == null || axis == null) {
            return base;
        }
        Vector attack = axis.attackDirection(team).clone();
        attack.setY(0);
        if (attack.lengthSquared() < 1.0e-6) {
            return base;
        }
        attack.normalize();
        double groundY = pitchGroundY();
        if (taker) {
            Location spot = center.clone().subtract(attack.clone().multiply(0.75));
            spot.setY(groundY);
            spot.setYaw(OffsideRestartHelper.yawFromDirection(attack));
            spot.setPitch(0f);
            return spot;
        }
        Location spot = base.clone();
        spot.setY(groundY);
        keepInOwnHalf(spot, center, attack);
        if (team != kickoffTeam) {
            double radius = centreCircleRadius() + 0.6;
            Vector offset = spot.toVector().subtract(center.toVector());
            offset.setY(0);
            double dist = offset.length();
            if (dist < radius) {
                if (dist < 0.05) {
                    offset = attack.clone().multiply(-1.0);
                }
                offset.normalize().multiply(radius);
                spot.setX(center.getX() + offset.getX());
                spot.setZ(center.getZ() + offset.getZ());
                keepInOwnHalf(spot, center, attack);
            }
        }
        return spot;
    }

    private static void keepInOwnHalf(Location spot, Location center, Vector attack) {
        Vector rel = spot.toVector().subtract(center.toVector());
        rel.setY(0);
        double progress = rel.dot(attack);
        if (progress > -1.0) {
            spot.subtract(attack.clone().multiply(progress + 1.0));
        }
    }

    private double centreCircleRadius() {
        Cuboid field = arena.getField();
        double halfLength = field != null ? Math.max(field.getSizeX(), field.getSizeZ()) * 0.5 : 30.0;
        return Math.max(4.0, Math.min(9.15, halfLength * 0.17));
    }

    /** Most advanced outfield player of the kicking side (humans preferred when level). */
    private UUID chooseKickoffTaker(TeamSide team) {
        if (team == null || !settings.kickoffRules) {
            return null;
        }
        Location center = arena.getBallSpawn();
        if (center == null) {
            return null;
        }
        UUID best = null;
        double bestScore = Double.MAX_VALUE;
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff() || sp.getTeam() != team) {
                continue;
            }
            Player p = sp.getBukkitPlayer();
            Location spot = resolveHumanFormationSpawn(sp);
            if (p == null || !p.isOnline() || spot == null) {
                continue;
            }
            double score = Math.hypot(spot.getX() - center.getX(), spot.getZ() - center.getZ()) - 4.0;
            if (score < bestScore) {
                bestScore = score;
                best = sp.getUuid();
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() != team || bot.getFormationSlot() <= 0) {
                continue;
            }
            Location spot = resolveBotSpawn(team, bot.getFormationSlot());
            if (spot == null) {
                continue;
            }
            double score = Math.hypot(spot.getX() - center.getX(), spot.getZ() - center.getZ());
            if (score < bestScore) {
                bestScore = score;
                best = bot.getUuid();
            }
        }
        return best;
    }

    public TeamSide getKickoffTeam() {
        return kickoffTeam;
    }

    public UUID getKickoffTakerId() {
        return kickoffTakerId;
    }

    /** Kickoff whistle has gone but nobody has touched the ball yet. */
    public boolean isKickoffPending() {
        return kickoffPendingTicks > 0;
    }

    public RefereeController getReferee() {
        return referee;
    }

    public CrowdAudio getCrowd() {
        return crowd;
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
        kickoffPendingTicks = settings.kickoffRules && kickoffTakerId != null ? 100 : 0;
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
            case WAITING:
                tickLobbyGraceSecond();
                break;
            case COUNTDOWN:
                countdown--;
                if (countdown > 0) {
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
                if (inStoppageTime) {
                    tickStoppageTimeSecond();
                } else if (!shouldPauseRegulationClock()) {
                    secondsRemaining--;
                    if (secondsRemaining == 60 || secondsRemaining == 30 || secondsRemaining == 10) {
                        broadcast(plugin.messages().format("time-remaining",
                                "time", Text.formatTime(secondsRemaining)));
                    }
                    if (secondsRemaining <= 0) {
                        tryBeginStoppageTime();
                    }
                }
                break;
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
            inStoppageTime = false;
            stoppageSecondsRemaining = 0;
            stoppageSecondsAdded = 0;
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

    private boolean shouldPauseRegulationClock() {
        if (goalReplay != null || celebrating) {
            return true;
        }
        return switch (state) {
            case KICKOFF, THROW_IN, OFFSIDE, REPLAY, VAR_REVIEW, REFEREE_STOP -> true;
            default -> false;
        };
    }

    private void tickStoppageAccumulator() {
        if (!settings.stoppageTimeEnabled || inStoppageTime || ended) {
            return;
        }
        if (state == GameState.EXTRA_TIME || state == GameState.HALFTIME
                || state == GameState.WAITING || state == GameState.COUNTDOWN
                || state == GameState.INTRO
                || state == GameState.PENALTIES || state == GameState.ENDED) {
            return;
        }
        if (shouldPauseRegulationClock()) {
            halfStoppageAccumTicks++;
        }
    }

    private void tryBeginStoppageTime() {
        if (!settings.stoppageTimeEnabled || settings.stoppageTimeMaxSeconds <= 0) {
            onPhaseTimeUp();
            return;
        }
        int added = Math.min(settings.stoppageTimeMaxSeconds, halfStoppageAccumTicks / 20);
        if (added <= 0) {
            onPhaseTimeUp();
            return;
        }
        inStoppageTime = true;
        stoppageSecondsAdded = added;
        stoppageSecondsRemaining = added;
        String timeText = Text.formatTime(added);
        broadcast(plugin.messages().format("stoppage-time-added", "time", timeText));
        forEachPlayer(p -> p.sendTitle(
                Text.color("&e&l+" + timeText),
                Text.color("&7" + timeText),
                5, 35, 10));
    }

    private void tickStoppageTimeSecond() {
        stoppageSecondsRemaining--;
        if (stoppageSecondsRemaining == 30 || stoppageSecondsRemaining == 10) {
            broadcast(plugin.messages().format("stoppage-time-remaining",
                    "time", Text.formatTime(stoppageSecondsRemaining)));
        }
        if (stoppageSecondsRemaining <= 0) {
            inStoppageTime = false;
            onPhaseTimeUp();
        }
    }

    private void endRegulation() {
        if (redScore == blueScore && settings.goldenGoal) {
            secondsRemaining = settings.goldenGoalDuration;
            broadcast(plugin.messages().raw("golden-goal"));
            forEachPlayer(p -> p.sendTitle(Text.color("&6&lGOLDEN GOAL"),
                    Text.color("&7Next goal wins!"), 5, 40, 10));
            if (settings.kickoffRules) {
                kickoffTeam = ThreadLocalRandom.current().nextBoolean() ? TeamSide.RED : TeamSide.BLUE;
            }
            startKickoff(GameState.EXTRA_TIME);
        } else {
            finishMatch();
        }
    }

    /**
     * Alternating penalty shootout after golden-goal extra time ends in a draw.
     */
    private void startPenalties() {
        shootoutActive = true;
        shootoutRedGoals = 0;
        shootoutBlueGoals = 0;
        shootoutRedTaken = 0;
        shootoutBlueTaken = 0;
        shootoutTurn = TeamSide.RED;
        shootoutRedOrder = buildShootoutOrder(TeamSide.RED);
        shootoutBlueOrder = buildShootoutOrder(TeamSide.BLUE);
        if (shootoutRedOrder.isEmpty() && shootoutBlueOrder.isEmpty()) {
            finishMatch();
            return;
        }
        if (ball != null) {
            ball.setFrozen(true);
        }
        setState(GameState.PENALTIES);
        broadcast(plugin.messages().raw("penalty-shootout"));
        forEachPlayer(p -> p.sendTitle(
                Text.color(plugin.messages().raw("penalty-shootout-title")),
                Text.color(plugin.messages().raw("penalty-shootout-subtitle")),
                5, 50, 20));
        shootoutInterKickCooldown = 40;
    }

    private List<UUID> buildShootoutOrder(TeamSide team) {
        List<UUID> order = new ArrayList<>();
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee() && !sp.isSentOff() && !sp.isBot() && sp.getTeam() == team) {
                order.add(sp.getUuid());
            }
        }
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() == team) {
                order.add(bot.getUuid());
            }
        }
        return order;
    }

    private void beginShootoutKick() {
        if (!shootoutActive || ended || celebrating || shootoutAwaitingResult) {
            return;
        }
        TeamSide attacking = shootoutTurn;
        SoccerPlayer taker = findPenaltyTaker(attacking);
        if (taker == null) {
            resolveShootoutAfterKick(false);
            return;
        }
        UUID takerId = taker.getUuid();

        Location spot = penaltySpot(attacking);
        if (spot == null || ball == null) {
            resolveShootoutAfterKick(false);
            return;
        }

        penaltyKeeperDived = false;
        resumeAfterFreeKick = GameState.PENALTIES;
        freeKickTeam = attacking;
        refereePenaltyRestart = true;
        freeKickSpot = spot.clone();
        freeKickTakerId = takerId;

        ball.teleport(spot);
        ball.setFrozen(true);
        ball.clearTouchHistory();

        assignPenaltyGoalkeeper(attacking.opposite(), spot);
        placePenaltyTaker(taker, spot, attacking);
        assignPenaltyLineup(attacking, spot, taker);

        setState(GameState.OFFSIDE);
        broadcast(plugin.messages().format("penalty-shootout-kick",
                "team", teamName(attacking),
                "player", taker.getName(),
                "red", String.valueOf(shootoutRedGoals),
                "blue", String.valueOf(shootoutBlueGoals)));
        scheduleRefereeRestartKick();
    }

    private void tickShootoutKickResult() {
        if (!shootoutAwaitingResult || ball == null) {
            return;
        }
        shootoutResultTicks++;

        if (detectBallInGoal() != null) {
            resolveShootoutAfterKick(true);
            return;
        }

        boolean settled = ball.getSpeed() < 0.07 && shootoutResultTicks > 35;
        boolean timedOut = shootoutResultTicks > 140;
        if (settled || timedOut) {
            resolveShootoutAfterKick(false);
        }
    }

    private TeamSide detectBallInGoal() {
        if (ball == null || ball.getLocation() == null) {
            return null;
        }
        Location curr = ball.getLocation();
        Location prev = ball.getPreviousTickLocation();
        TeamSide goalBox = null;
        if (prev != null) {
            goalBox = arena.goalContainingAlongPath(prev, curr);
        }
        if (goalBox == null) {
            goalBox = arena.goalContaining(curr);
        }
        return goalBox != null ? goalBox.opposite() : null;
    }

    private void resolveShootoutAfterKick(boolean scored) {
        shootoutAwaitingResult = false;
        shootoutResultTicks = 0;
        TeamSide kicking = shootoutKickingTeam != null ? shootoutKickingTeam : shootoutTurn;

        if (scored) {
            if (kicking == TeamSide.RED) {
                shootoutRedGoals++;
            } else {
                shootoutBlueGoals++;
            }
            broadcast(plugin.messages().format("penalty-shootout-goal",
                    "team", teamName(kicking),
                    "red", String.valueOf(shootoutRedGoals),
                    "blue", String.valueOf(shootoutBlueGoals)));
            beginShootoutCelebration();
        } else {
            broadcast(plugin.messages().format("penalty-shootout-miss",
                    "team", teamName(kicking),
                    "red", String.valueOf(shootoutRedGoals),
                    "blue", String.valueOf(shootoutBlueGoals)));
        }

        if (kicking == TeamSide.RED) {
            shootoutRedTaken++;
        } else {
            shootoutBlueTaken++;
        }
        shootoutKickingTeam = null;
        shootoutTurn = kicking.opposite();

        TeamSide winner = evaluateShootoutWinner();
        if (winner != null) {
            finishShootout(winner);
            return;
        }

        if (scored) {
            shootoutScheduleNextKick = true;
        } else {
            shootoutInterKickCooldown = 45;
        }
        if (ball != null) {
            ball.teleport(ballSpawnLocation());
            ball.clearTouchHistory();
            ball.setFrozen(true);
        }
    }

    private TeamSide evaluateShootoutWinner() {
        int redLeft = SHOOTOUT_KICKS_PER_SIDE - shootoutRedTaken;
        int blueLeft = SHOOTOUT_KICKS_PER_SIDE - shootoutBlueTaken;

        if (shootoutRedGoals > shootoutBlueGoals + blueLeft) {
            return TeamSide.RED;
        }
        if (shootoutBlueGoals > shootoutRedGoals + redLeft) {
            return TeamSide.BLUE;
        }

        if (shootoutRedTaken >= SHOOTOUT_KICKS_PER_SIDE && shootoutBlueTaken >= SHOOTOUT_KICKS_PER_SIDE) {
            if (shootoutRedGoals > shootoutBlueGoals) {
                return TeamSide.RED;
            }
            if (shootoutBlueGoals > shootoutRedGoals) {
                return TeamSide.BLUE;
            }
        }
        return null;
    }

    private void beginShootoutCelebration() {
        celebrating = true;
        celebrationTicks = 24;
        if (ball != null) {
            ball.setFrozen(true);
        }
    }

    private void finishShootout(TeamSide winner) {
        shootoutActive = false;
        shootoutAwaitingResult = false;
        matchWinnerOverride = winner;
        broadcast(plugin.messages().format("penalty-shootout-winner",
                "team", teamName(winner),
                "red", String.valueOf(shootoutRedGoals),
                "blue", String.valueOf(shootoutBlueGoals)));
        finishMatch();
    }

    /**
     * Random goalkeeper dive (left / center / right) when the penalty is struck.
     */
    private void triggerPenaltyKeeperDive() {
        if (!refereePenaltyRestart || penaltyKeeperId == null || pitchAxis == null || freeKickTeam == null) {
            return;
        }
        TeamSide defending = freeKickTeam.opposite();
        Location base = computeGoalkeeperSpot(defending);
        if (base == null) {
            return;
        }

        Vector attack = pitchAxis.attackDirection(defending).clone().normalize();
        Vector lateral = new Vector(-attack.getZ(), 0, attack.getX()).normalize();
        double halfWidth = 1.2;
        Cuboid goal = arena.getGoal(defending);
        if (goal != null) {
            double widthX = goal.getMaxX() - goal.getMinX();
            double widthZ = goal.getMaxZ() - goal.getMinZ();
            halfWidth = Math.max(0.85, Math.max(widthX, widthZ) * 0.30);
        }

        int pick = ThreadLocalRandom.current().nextInt(3);
        Location dive = base.clone();
        double groundY = pitchGroundY();
        switch (pick) {
            case 0 -> dive.add(lateral.clone().multiply(halfWidth));
            case 1 -> dive.add(lateral.clone().multiply(-halfWidth));
            default -> { /* stay central */ }
        }
        dive.add(attack.clone().multiply(0.4));
        dive.setY(groundY + (pick == 2 ? 0.2 : 0.05));
        dive.setYaw(base.getYaw());
        dive.setPitch(0f);

        penaltyKeeperDived = true;

        MatchBot keeperBot = bots.get(penaltyKeeperId);
        if (keeperBot != null) {
            keeperBot.penaltyDive(dive);
            if (dive.getWorld() != null) {
                dive.getWorld().playSound(dive, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.95f, 0.65f);
            }
        }
    }

    /** Whether the designated keeper may attempt a save (in-match penalty or shootout). */
    public boolean isPenaltyKeeper(UUID playerId) {
        return penaltyKeeperId != null && penaltyKeeperId.equals(playerId);
    }

    public boolean isPenaltySaveWindow() {
        if (penaltyKeeperId == null || ball == null || celebrating || goalReplay != null) {
            return false;
        }
        if (penaltyKeeperReleaseTicks > 0) {
            return true;
        }
        return shootoutAwaitingResult && shootoutResultTicks > 0 && shootoutResultTicks < 90;
    }

    /**
     * Human keeper dives laterally (Shift) before or during a penalty kick.
     */
    public void penaltyKeeperDive(Player player) {
        if (!isPenaltyKeeper(player.getUniqueId())) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || sp.isBot()) {
            return;
        }
        TeamSide defending = freeKickTeam != null ? freeKickTeam.opposite() : sp.getTeam();
        Location base = player.getLocation();
        Vector lateral = player.getLocation().getDirection().setY(0);
        if (lateral.lengthSquared() < 1e-6) {
            return;
        }
        lateral.normalize();
        Location dive = base.clone().add(lateral.multiply(1.35));
        dive.setY(pitchGroundY() + 0.05);
        Location clamped = BotAi.clampKeeperPosition(this, defending, dive);
        if (clamped != null) {
            dive = clamped;
        }
        player.teleport(dive);
        player.setSneaking(true);
        player.setSwimming(true);
        penaltyKeeperDived = true;
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.95f, 0.65f);
    }

    /** Active punch-save when the keeper left-clicks during the penalty flight window. */
    public void attemptPenaltyKeeperSave(Player player) {
        if (!isPenaltyKeeper(player.getUniqueId()) || !isPenaltySaveWindow()) {
            return;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || sp.isBot()) {
            return;
        }
        tryPenaltyKeeperSave(sp, player, true);
    }

    private void processPenaltyKeeperSaves() {
        if (penaltyKeeperId == null || ball == null || ball.getLocation() == null) {
            return;
        }
        SoccerPlayer keeper = players.get(penaltyKeeperId);
        if (keeper == null || keeper.isBot()) {
            return;
        }
        Player player = keeper.getBukkitPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        tryPenaltyKeeperSave(keeper, player, false);
    }

    private boolean tryPenaltyKeeperSave(SoccerPlayer sp, Player player, boolean manual) {
        if (ball == null || ball.getLocation() == null || player.getLocation() == null) {
            return false;
        }
        if (!isPenaltyKeeperZone(sp.getTeam(), player.getLocation())) {
            return false;
        }

        Location ballLoc = ball.getLocation();
        Location feet = player.getLocation();
        Location prev = ball.getPreviousTickLocation();
        double reach = manual ? 3.2 : 2.6;
        double horiz = Math.hypot(feet.getX() - ballLoc.getX(), feet.getZ() - ballLoc.getZ());
        double dy = Math.abs((feet.getY() + 1.0) - ballLoc.getY());
        boolean nearBall = horiz <= reach && dy <= 2.6;
        boolean pathHit = segmentNearLocation(prev, ballLoc, feet, reach + 0.4, 2.8);
        if (!nearBall && !pathHit) {
            return false;
        }

        double speed = ball.getSpeed();
        if (!manual && speed < 0.12 && horiz > 1.8) {
            return false;
        }

        Vector away = player.getLocation().getDirection().clone();
        away.setY(0.12);
        if (away.lengthSquared() < 1e-6) {
            away = ballLoc.toVector().subtract(feet.toVector());
            away.setY(0.15);
        }
        away.normalize().multiply(0.95 + speed * 0.55);

        touchBall(player, sp.getTeam());
        ball.applyImpulse(away, null);
        sp.addTouch();
        sp.addSave();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.7f);
        if (crowd != null) {
            crowd.onSave(sp.getTeam());
        }
        player.getWorld().spawnParticle(Particle.CRIT, ballLoc, 10, 0.15, 0.15, 0.15, 0.06);
        return true;
    }

    private boolean isPenaltyKeeperZone(TeamSide defending, Location loc) {
        if (loc == null) {
            return false;
        }
        if (isInOwnGoalBox(defending, loc)) {
            return true;
        }
        Location clamped = BotAi.clampKeeperPosition(this, defending, loc);
        return clamped != null
                && OffsideRestartHelper.horizontalDistance(loc, clamped) < 0.55;
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

    private void finishMatch() {
        if (ended) {
            return;
        }
        stopIntro();
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

        TeamSide winner = matchWinnerOverride;
        if (winner == null) {
            if (redScore > blueScore) {
                winner = TeamSide.RED;
            } else if (blueScore > redScore) {
                winner = TeamSide.BLUE;
            }
        }

        if (matchWinnerOverride != null && (shootoutRedGoals > 0 || shootoutBlueGoals > 0)) {
            broadcast(plugin.messages().format("match-end-shootout",
                    "red", String.valueOf(redScore),
                    "blue", String.valueOf(blueScore),
                    "shootout_red", String.valueOf(shootoutRedGoals),
                    "shootout_blue", String.valueOf(shootoutBlueGoals)));
        } else {
            broadcast(plugin.messages().format("match-end",
                    "red", String.valueOf(redScore), "blue", String.valueOf(blueScore)));
        }
        if (winner != null) {
            broadcast(plugin.messages().format("winner", "team", teamName(winner)));
            celebrateWinner(winner);
        } else {
            broadcast(plugin.messages().raw("draw"));
        }
        recordMatchStats(winner);
        plugin.matchHistory().recordFinishedMatch(this, winner);
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
     * Force an immediate penalty shootout (admin command). Works at any point
     * during a live match regardless of the score or clock.
     */
    public boolean forcePenaltyShootout() {
        if (ended || state == GameState.WAITING || state == GameState.COUNTDOWN
                || state == GameState.INTRO) {
            return false;
        }
        if (buildShootoutOrder(TeamSide.RED).isEmpty() && buildShootoutOrder(TeamSide.BLUE).isEmpty()) {
            return false;
        }

        celebrating = false;
        celebrationTicks = 0;
        if (goalReplay != null) {
            goalReplay.cancel();
            goalReplay = null;
        }
        pendingCelebrationTeam = null;
        pendingScorer = null;
        pendingAssist = null;
        clearFreeKickRestart();
        refereePenaltyRestart = false;
        penaltyKeeperDived = false;
        penaltyKeeperReleaseTicks = 0;
        shootoutActive = false;
        shootoutAwaitingResult = false;
        shootoutInterKickCooldown = 0;
        shootoutScheduleNextKick = false;
        shootoutKickingTeam = null;
        matchWinnerOverride = null;
        awaitingRefereeDecision = false;
        inStoppageTime = false;

        if (ball != null) {
            ball.setFrozen(true);
        }

        startPenalties();
        return shootoutActive;
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
        stopIntro();
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
        if (intro != null) {
            intro.onParticipantLeft(player);
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

    public BotFormation getFormation(TeamSide team) {
        if (team == null) {
            return BotFormation.FOUR_FOUR_TWO;
        }
        return teamFormations.getOrDefault(team, BotFormation.FOUR_FOUR_TWO);
    }

    public void setFormation(BotFormation formation) {
        setFormation(TeamSide.RED, formation);
        setFormation(TeamSide.BLUE, formation);
    }

    public void setFormation(TeamSide team, BotFormation formation) {
        if (team == null) {
            return;
        }
        teamFormations.put(team, formation != null ? formation : BotFormation.FOUR_FOUR_TWO);
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
        if (isSquadSlotClaimed(team, index)) {
            return null;
        }
        boolean reserveStriker = shouldReserveStrikerForBots(team);
        BotFormation formation = getFormation(team);
        int formationSlot = formation.formationSlotForSquadIndex(index, reserveStriker);
        com.ballgame.soccer.fifa.FifaPlayer fifaPlayer = fifaStarter(team, index);
        BotRole role = fifaPlayer != null
                ? fifaPlayer.getPosition().toBotRole(Math.max(0, index - 1))
                : BotRole.forSquadIndex(index);
        Location spot = resolveBotSpawn(team, formationSlot);
        if (spot == null) {
            return null;
        }
        String name = fifaPlayer != null ? fifaPlayer.getName() : botDisplayName(index);
        String skin = fifaPlayer != null ? fifaPlayer.getSkin() : null;
        MatchBot bot = new MatchBot(plugin, this, name, team, index, role, formationSlot, spot, skin);
        bots.put(bot.getUuid(), bot);
        if (!suppressCountdownOnBotAdd && state == GameState.WAITING && !humanRefereeMatch && !soloMatch) {
            if (botOnlyMatch) {
                tryStartBotOnlyLobby();
            } else {
                onLobbyPresenceChanged();
            }
        }
        return bot;
    }

    /** Fills empty starting-XI slots with FIFA-named bots (skips human-claimed slots). */
    public int fillSquadBots(TeamSide team) {
        if (team == null || !settings.botsEnabled) {
            return 0;
        }
        int added = 0;
        for (int slot = 0; slot < 11 && !isTeamFull(team); slot++) {
            if (isSquadSlotClaimed(team, slot)) {
                continue;
            }
            if (hasBotAtSquadIndex(team, slot)) {
                continue;
            }
            if (addBot(team, slot) != null) {
                added++;
            }
        }
        return added;
    }

    /** Fills both squads to 11 without triggering human lobby grace. */
    public int fillFullSquadsSilently() {
        suppressCountdownOnBotAdd = true;
        try {
            return fillSquadBots(TeamSide.RED) + fillSquadBots(TeamSide.BLUE);
        } finally {
            suppressCountdownOnBotAdd = false;
        }
    }

    private boolean hasBotAtSquadIndex(TeamSide team, int index) {
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() == team && bot.getSquadIndex() == index) {
                return true;
            }
        }
        return false;
    }

    public boolean claimSquadSlot(TeamSide team, int slot, UUID playerId) {
        if (team == null || playerId == null || slot < 0 || slot > 10) {
            return false;
        }
        java.util.Map<Integer, UUID> claims = squadClaims.computeIfAbsent(team, t -> new java.util.HashMap<>());
        for (java.util.Map.Entry<Integer, UUID> entry : claims.entrySet()) {
            if (playerId.equals(entry.getValue())) {
                claims.remove(entry.getKey());
                break;
            }
        }
        UUID occupant = claims.get(slot);
        if (occupant != null && !occupant.equals(playerId)) {
            return false;
        }
        claims.put(slot, playerId);
        SoccerPlayer sp = players.get(playerId);
        if (sp != null) {
            com.ballgame.soccer.fifa.FifaPlayer fp = fifaStarter(team, slot);
            if (fp != null) {
                sp.setDisplayOverride(fp.displayLabel());
            }
            for (MatchBot bot : new ArrayList<>(bots.values())) {
                if (bot.getTeam() == team && bot.getSquadIndex() == slot) {
                    bot.remove();
                    bots.remove(bot.getUuid());
                }
            }
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                Location spot = resolveHumanFormationSpawn(sp);
                if (spot != null) {
                    player.teleport(spot);
                }
            }
        }
        return true;
    }

    /** Squad index (0 = GK) claimed by this player on the given team, or -1. */
    public int findClaimedSquadIndex(UUID playerId, TeamSide team) {
        if (playerId == null || team == null) {
            return -1;
        }
        java.util.Map<Integer, UUID> claims = squadClaims.get(team);
        if (claims == null) {
            return -1;
        }
        for (java.util.Map.Entry<Integer, UUID> entry : claims.entrySet()) {
            if (playerId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public void releaseSquadSlot(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (java.util.Map<Integer, UUID> claims : squadClaims.values()) {
            claims.entrySet().removeIf(e -> playerId.equals(e.getValue()));
        }
    }

    public UUID getSquadClaim(TeamSide team, int slot) {
        java.util.Map<Integer, UUID> claims = squadClaims.get(team);
        return claims != null ? claims.get(slot) : null;
    }

    public boolean isSquadSlotClaimed(TeamSide team, int slot) {
        return getSquadClaim(team, slot) != null;
    }

    public java.util.Map<Integer, UUID> getSquadClaims(TeamSide team) {
        java.util.Map<Integer, UUID> claims = squadClaims.get(team);
        return claims == null ? java.util.Map.of() : java.util.Map.copyOf(claims);
    }

    public com.ballgame.soccer.fifa.FifaPlayer fifaStarter(TeamSide team, int slot) {
        String code = team == TeamSide.RED ? redNationCode : blueNationCode;
        if (code == null || plugin.fifaCatalog() == null) {
            return null;
        }
        com.ballgame.soccer.fifa.FifaNation nation = plugin.fifaCatalog().get(code);
        return nation != null ? nation.starterAt(slot) : null;
    }

    public com.ballgame.soccer.fifa.FifaNation fifaNation(TeamSide team) {
        String code = team == TeamSide.RED ? redNationCode : blueNationCode;
        if (code == null || plugin.fifaCatalog() == null) {
            return null;
        }
        return plugin.fifaCatalog().get(code);
    }

    /**
     * Reserve the main striker formation slot for bots only when a human actually
     * plays there (claimed striker slot or joined without picking a squad slot).
     */
    private boolean shouldReserveStrikerForBots(TeamSide team) {
        if (team == null) {
            return false;
        }
        BotFormation formation = getFormation(team);
        int strikerSlot = formation.strikerSlotIndex();

        java.util.Map<Integer, UUID> claims = squadClaims.get(team);
        if (claims != null) {
            for (java.util.Map.Entry<Integer, UUID> entry : claims.entrySet()) {
                int formationSlot = formation.formationSlotForSquadIndex(entry.getKey(), false);
                if (formationSlot == strikerSlot) {
                    return true;
                }
            }
        }

        for (SoccerPlayer sp : players.values()) {
            if (!sp.isBot() && !sp.isReferee() && sp.getTeam() == team
                    && findClaimedSquadIndex(sp.getUuid(), team) < 0) {
                return true;
            }
        }
        return false;
    }

    public void removeAllBots() {
        for (MatchBot bot : new ArrayList<>(bots.values())) {
            bot.remove();
        }
        bots.clear();
    }

    private Location resolveBotSpawn(TeamSide team, int formationSlot) {
        BotFormation formation = getFormation(team);
        Location spot = FormationSpots.spawnForSlot(arena, team, formation, formationSlot);
        if (spot != null) {
            return spot;
        }
        if (formationSlot <= 0) {
            return BotAi.keeperSpawnSpot(this, team);
        }
        Location base = arena.getTeamSpawn(team);
        if (base == null) {
            base = arena.getBallSpawn();
        }
        return base;
    }

    private String botDisplayName(int index) {
        java.util.List<String> names = settings.botNames;
        if (names != null && index >= 0 && index < names.size()) {
            return names.get(index);
        }
        return "Bot " + (index + 1);
    }

    private boolean botsTickActive() {
        return state.isLive()
                || state == GameState.KICKOFF
                || state == GameState.THROW_IN
                || state == GameState.OFFSIDE;
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
        if (ball == null || ball.getLocation() == null || keeperHolderId != null) {
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
                    ? settings.collisionRadius + 1.05
                    : settings.collisionRadius + 0.8;
            double maxHeight = isKeeper && nearOwnGoal ? 2.65 : 1.8;
            if (horiz > catchRadius || dy > maxHeight) {
                continue;
            }

            boolean isChaser = bot.getUuid().equals(getBallChaserBotId(sp.getTeam()));
            boolean moving = bot.getSpeed() > 0.05;
            boolean trap = speed > 0.12;
            boolean dribble = isChaser && speed <= 0.12 && moving;

            if (isKeeper && nearOwnGoal) {
                continue;
            }
            if (!trap && !dribble) {
                continue;
            }
            if (isKickTrapImmune(bot.getUuid())) {
                continue;
            }

            if (settings.botFirstTouch) {
                if (bot.isStaggered(matchTick) || bot.isDown() || isOwnCarry(bot)
                        || isControlledByOpponent(sp.getTeam(), ballLoc)) {
                    continue;
                }
                if (trap) {
                    if (!botTouchBlockedByOffside(bot)) {
                        receiveBall(bot, ballLoc, speed, keeperZone);
                    }
                    return;
                }
                if (!bot.getUuid().equals(ball.getLastTouchId())) {
                    if (botTouchBlockedByOffside(bot)) {
                        return;
                    }
                    touchBall(bot.getUuid(), sp.getTeam());
                    sp.addTouch();
                    bot.markControl(matchTick);
                }
                if (!settings.botCloseControl && isChaser && moving) {
                    ball.applyImpulse(Mechanics.dribbleNudge(bot, settings), null);
                }
                continue;
            }

            if (trap) {
                double contactSpeed = speed;
                ball.haltMotion();
                if (keeperZone && contactSpeed > 0.38) {
                    sp.addSave();
                    if (crowd != null) {
                        crowd.onSave(sp.getTeam());
                    }
                    feet.getWorld().spawnParticle(Particle.CRIT, ballLoc, 6, 0.1, 0.1, 0.1, 0.05);
                }
            }

            touchBall(bot.getUuid(), sp.getTeam());
            sp.addTouch();

            if (isChaser && moving) {
                Vector nudge = Mechanics.dribbleNudge(bot, settings);
                ball.applyImpulse(nudge, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // Bot ball control: first touch, headers, carrying, duels
    // ------------------------------------------------------------------

    /** The ball this bot just knocked ahead of itself — don't stop it dead. */
    private boolean isOwnCarry(MatchBot bot) {
        return ball != null && bot.getUuid().equals(ball.getLastTouchId())
                && matchTick - bot.getLastCarryTick() <= 18;
    }

    /** An opponent has the ball at his feet: it has to be won in a duel, not just walked into. */
    private boolean isControlledByOpponent(TeamSide team, Location ballLoc) {
        if (ball == null || ballLoc == null) {
            return false;
        }
        UUID lastId = ball.getLastTouchId();
        TeamSide lastTeam = ball.getLastTouchTeam();
        if (lastId == null || lastTeam == null || lastTeam == team || ball.getSpeed() > 0.6) {
            return false;
        }
        Location carrier = participantLocation(lastId);
        return carrier != null && carrier.getWorld() == ballLoc.getWorld()
                && Math.hypot(carrier.getX() - ballLoc.getX(), carrier.getZ() - ballLoc.getZ()) < 1.35;
    }

    /**
     * A bot meets a moving ball. Good touches kill it into the player's stride
     * toward where the AI wants to go next; heavy ones bounce off and keep part of
     * the pace — hard passes, bouncing balls, pressure and tired legs all make a
     * clean first touch less likely. Balls at head height are headed on.
     */
    private void receiveBall(MatchBot bot, Location ballLoc, double speed, boolean keeperZone) {
        SoccerPlayer sp = bot.getProfile();
        Location feet = bot.getLocation();
        Vector incoming = ball.getVelocity();
        double height = ballLoc.getY() - feet.getY();
        boolean teamBall = ball.getLastTouchTeam() == sp.getTeam();
        int pressure = countOpponentsNear(sp.getTeam(), feet, 2.6);
        double quality = firstTouchQuality(bot, speed, height, pressure, incoming, teamBall);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (settings.botCloseControl && height > 1.15) {
            headBall(bot, ballLoc, speed, quality);
            return;
        }
        if (settings.botCloseControl && teamBall && height < 0.9 && firstTimeFinish(bot, ballLoc, speed, quality)) {
            return;
        }

        Vector intent = bot.getTouchDirection();
        if (intent == null || intent.lengthSquared() < 1.0e-6) {
            intent = bot.getFacing();
        }
        intent.setY(0).normalize();

        double roll = rnd.nextDouble();
        ball.haltMotion();
        if (roll < quality) {
            double cushion = 0.1 + (pressure > 0 ? 0.03 : 0.07) * rnd.nextDouble();
            ball.applyImpulse(intent.multiply(cushion), null);
            bot.markControl(matchTick);
            bot.setActionCooldown(2);
        } else {
            double severity = Math.min(1.0, (roll - quality) / Math.max(0.05, 1.0 - quality));
            Vector out = incoming.clone().setY(0);
            if (out.lengthSquared() < 1.0e-6) {
                out = bot.getFacing();
            }
            double spread = (rnd.nextDouble() * 2.0 - 1.0) * (0.35 + 0.9 * severity);
            out = rotateFlat(out.normalize(), spread)
                    .multiply(Math.max(0.14, speed * (0.22 + 0.42 * severity)));
            ball.applyImpulse(out, null);
            bot.markControl(matchTick);
            bot.setActionCooldown(3 + (int) Math.round(severity * 6.0));
        }
        bot.markCarryTouch(matchTick);
        touchBall(bot.getUuid(), sp.getTeam());
        sp.addTouch();
        if (keeperZone && speed > 0.38) {
            sp.addSave();
            if (crowd != null) {
                crowd.onSave(sp.getTeam());
            }
            ballLoc.getWorld().spawnParticle(Particle.CRIT, ballLoc, 6, 0.1, 0.1, 0.1, 0.05);
        }
    }

    private double firstTouchQuality(MatchBot bot, double speed, double height, int pressure,
                                     Vector incoming, boolean teamBall) {
        double q = switch (getBotDifficulty(bot.getTeam())) {
            case EASY -> 0.58;
            case MEDIUM -> 0.74;
            case HARD -> 0.86;
        };
        q += (bot.composure() - 0.72) * 0.35;
        q -= Math.max(0.0, speed - 0.4) * 0.42;
        if (height > 0.55) {
            q -= 0.1 + Math.min(0.12, (height - 0.55) * 0.2);
        }
        q -= Math.min(2, pressure) * 0.07;
        if (!teamBall) {
            q -= 0.1;
        }
        Vector from = incoming.clone().setY(0).multiply(-1.0);
        if (from.lengthSquared() > 1.0e-6 && bot.getFacing().dot(from.normalize()) < -0.2) {
            q -= 0.08;
        }
        q -= (1.0 - bot.getStaminaFraction()) * 0.1;
        return Math.max(0.12, Math.min(0.97, q));
    }

    /** A cut-back or low cross met first time in front of goal instead of being controlled. */
    private boolean firstTimeFinish(MatchBot bot, Location ballLoc, double speed, double quality) {
        Cuboid oppGoalBox = arena.getGoal(bot.getTeam().opposite());
        if (oppGoalBox == null) {
            return false;
        }
        Location oppGoal = oppGoalBox.getCenter();
        double goalDist = Math.hypot(ballLoc.getX() - oppGoal.getX(), ballLoc.getZ() - oppGoal.getZ());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double appetite = switch (getBotDifficulty(bot.getTeam())) {
            case EASY -> 0.3;
            case MEDIUM -> 0.5;
            case HARD -> 0.65;
        };
        if (goalDist > 11.0 || rnd.nextDouble() > appetite) {
            return false;
        }
        Location aim = BotAi.finishingAim(this, bot, ballLoc);
        if (aim == null) {
            return false;
        }
        Location adjusted = botAdjustedTarget(bot, ballLoc, aim, true);
        Vector flat = adjusted.toVector().subtract(ballLoc.toVector()).setY(0);
        if (flat.lengthSquared() < 1.0e-6) {
            return false;
        }
        // Meeting it first time is harder to keep on target than a set strike.
        flat = rotateFlat(flat, rnd.nextGaussian() * Math.toRadians(4.0) * (1.2 - quality));
        adjusted = ballLoc.clone().add(flat);
        double power = Math.min(0.85, 0.5 + quality * 0.3 + Math.min(0.15, speed * 0.1));
        double lift = 0.03 + shotLiftError(bot) + (1.0 - quality) * 0.05;
        Mechanics.Kick kick = Mechanics.computeBotShotToward(ballLoc, adjusted, settings, power, lift);

        SoccerPlayer sp = bot.getProfile();
        bot.faceLocation(aim);
        ball.haltMotion();
        ball.applyImpulse(kick.impulse, kick.spin);
        touchBall(bot.getUuid(), sp.getTeam());
        sp.addTouch();
        sp.addShot();
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.swingArm();
        bot.setActionCooldown(8);
        ballLoc.getWorld().playSound(ballLoc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.95f);
        return true;
    }

    private void headBall(MatchBot bot, Location ballLoc, double speed, double quality) {
        SoccerPlayer sp = bot.getProfile();
        Vector dir = bot.getTouchDirection();
        Cuboid oppGoalBox = arena.getGoal(sp.getTeam().opposite());
        Location oppGoal = oppGoalBox != null ? oppGoalBox.getCenter() : null;
        boolean atGoal = oppGoal != null
                && Math.hypot(ballLoc.getX() - oppGoal.getX(), ballLoc.getZ() - oppGoal.getZ()) < 14.0;
        if (atGoal && (dir == null || dir.lengthSquared() < 1.0e-6)) {
            dir = oppGoal.toVector().subtract(ballLoc.toVector());
        }
        if (dir == null || dir.lengthSquared() < 1.0e-6) {
            dir = bot.getFacing();
        }
        dir.setY(0);
        if (dir.lengthSquared() < 1.0e-6) {
            dir = bot.getFacing();
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double error = rnd.nextGaussian() * (0.12 + (1.0 - quality) * 0.45);
        Vector out = rotateFlat(dir.normalize(), error)
                .multiply(0.42 + quality * 0.25 + Math.min(0.3, speed * 0.2));
        out.setY(atGoal ? 0.05 + rnd.nextDouble() * 0.1 : 0.16 + rnd.nextDouble() * 0.1);

        ball.haltMotion();
        ball.applyImpulse(out, null);
        touchBall(bot.getUuid(), sp.getTeam());
        sp.addTouch();
        if (atGoal) {
            sp.addShot();
        }
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.setActionCooldown(6);
        ballLoc.getWorld().playSound(ballLoc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.25f);
    }

    /**
     * A dribbling touch: knocks the ball ahead so that, slowed by the turf, it
     * rolls back into the runner's stride after {@code touchTicks}. Short
     * intervals and a small lead give close control; long ones knock it on.
     */
    public boolean botCarry(MatchBot bot, Vector direction, double runSpeed, int touchTicks, double lead) {
        if ((state != GameState.PLAYING && state != GameState.EXTRA_TIME) || ball == null
                || keeperHolderId != null || direction == null || celebrating) {
            return false;
        }
        Location ballLoc = ball.getLocation();
        Location feet = bot.getLocation();
        if (ballLoc == null || feet == null || ball.getSpeed() > 0.55
                || Math.hypot(feet.getX() - ballLoc.getX(), feet.getZ() - ballLoc.getZ()) > 1.7
                || ballLoc.getY() - feet.getY() > 0.9) {
            return false;
        }
        Vector dir = direction.clone().setY(0);
        if (dir.lengthSquared() < 1.0e-6) {
            return false;
        }
        if (botTouchBlockedByOffside(bot)) {
            return false;
        }
        dir.normalize();
        int ticks = Math.max(3, touchTicks);
        double friction = Math.max(0.5, Math.min(0.99, settings.groundFriction));
        Location aim = feet.clone().add(dir.multiply(Math.max(0.0, runSpeed) * ticks + lead));
        Vector delta = aim.toVector().subtract(ballLoc.toVector()).setY(0);
        double travel = delta.length();
        if (travel < 0.05) {
            return false;
        }
        double v0 = travel * (1.0 - friction) / (1.0 - Math.pow(friction, ticks));
        v0 = Math.max(0.06, Math.min(0.62, v0));
        ball.haltMotion();
        ball.applyImpulse(delta.multiply(v0 / travel), null);
        touchBall(bot.getUuid(), bot.getTeam());
        bot.getProfile().addTouch();
        bot.markCarryTouch(matchTick);
        return true;
    }

    /**
     * Standing tackle. Against a loose ball it is a simple claim; against a
     * player in possession it is a duel decided by tackling vs. ball-control
     * attributes, how far the ball is from the carrier's feet and the angle of
     * approach. The loser is left off balance for a moment.
     */
    public boolean botStandingTackle(MatchBot tackler, Vector pokeDirection) {
        if ((state != GameState.PLAYING && state != GameState.EXTRA_TIME) || ball == null
                || keeperHolderId != null || celebrating) {
            return false;
        }
        Location ballLoc = ball.getLocation();
        Location feet = tackler.getLocation();
        if (ballLoc == null || feet == null || !tackler.canDuel(matchTick)
                || Math.hypot(feet.getX() - ballLoc.getX(), feet.getZ() - ballLoc.getZ()) > 2.0
                || Math.abs(ballLoc.getY() - feet.getY()) > 1.4) {
            return false;
        }
        tackler.setDuelCooldown(matchTick, 18);
        if (botTouchBlockedByOffside(tackler)) {
            return false;
        }

        UUID carrierId = ball.getLastTouchId();
        TeamSide carrierTeam = ball.getLastTouchTeam();
        MatchBot carrierBot = carrierId != null ? bots.get(carrierId) : null;
        Location carrierLoc = carrierId != null ? participantLocation(carrierId) : null;
        boolean contested = carrierTeam != null && carrierTeam != tackler.getTeam() && carrierLoc != null
                && carrierLoc.getWorld() == ballLoc.getWorld()
                && Math.hypot(carrierLoc.getX() - ballLoc.getX(), carrierLoc.getZ() - ballLoc.getZ()) < 1.8;

        double chance = 0.92;
        if (contested) {
            double tackling = 0.5 + tackler.aggression() * 0.12 + tackler.anticipation() * 0.18
                    + difficultyEdge(tackler.getTeam());
            double control = carrierBot != null
                    ? 0.45 + carrierBot.composure() * 0.2 + carrierBot.agility() * 0.05
                    + difficultyEdge(carrierBot.getTeam())
                    : 0.58;
            double gap = Math.hypot(carrierLoc.getX() - ballLoc.getX(), carrierLoc.getZ() - ballLoc.getZ());
            chance = 0.5 + (tackling - control) + Math.max(0.0, gap - 0.7) * 0.35;
            Vector carrierRun = carrierBot != null ? carrierBot.getVelocity() : carrierLoc.getDirection();
            carrierRun.setY(0);
            Vector fromCarrier = feet.toVector().subtract(carrierLoc.toVector()).setY(0);
            if (carrierRun.lengthSquared() > 1.0e-4 && fromCarrier.lengthSquared() > 1.0e-6
                    && carrierRun.normalize().dot(fromCarrier.normalize()) < -0.3) {
                chance -= 0.12;
            }
            if (carrierBot != null && carrierBot.isStaggered(matchTick)) {
                chance += 0.2;
            }
            chance = Math.max(0.12, Math.min(0.88, chance));
        }

        SoccerPlayer sp = tackler.getProfile();
        sp.addTackle();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() >= chance) {
            tackler.stagger(matchTick, 14);
            ballLoc.getWorld().playSound(ballLoc, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.6f, 1.0f);
            return false;
        }

        Vector poke = pokeDirection != null ? pokeDirection.clone().setY(0) : new Vector();
        if (poke.lengthSquared() < 1.0e-6) {
            poke = ballLoc.toVector().subtract((contested ? carrierLoc : feet).toVector()).setY(0);
        }
        if (poke.lengthSquared() < 1.0e-6) {
            poke = tackler.getFacing();
        }
        double power = contested ? 0.26 + rnd.nextDouble() * 0.14 : 0.16;
        ball.haltMotion();
        ball.applyImpulse(rotateFlat(poke.normalize(), (rnd.nextDouble() - 0.5) * 0.5).multiply(power), null);
        touchBall(tackler.getUuid(), tackler.getTeam());
        sp.addTouch();
        tackler.markControl(matchTick);
        tackler.markCarryTouch(matchTick);
        tackler.swingArm();
        if (carrierBot != null && contested) {
            carrierBot.stagger(matchTick, 12);
        }
        ballLoc.getWorld().playSound(ballLoc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.1f);
        return true;
    }

    private double difficultyEdge(TeamSide team) {
        return switch (getBotDifficulty(team)) {
            case EASY -> -0.12;
            case MEDIUM -> 0.0;
            case HARD -> 0.08;
        };
    }

    private int countOpponentsNear(TeamSide team, Location at, double radius) {
        if (at == null || team == null) {
            return 0;
        }
        int count = 0;
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.getTeam() == null || sp.getTeam() == team) {
                continue;
            }
            Player p = sp.getBukkitPlayer();
            if (p != null && p.isOnline() && p.getWorld() == at.getWorld()
                    && Math.hypot(p.getLocation().getX() - at.getX(), p.getLocation().getZ() - at.getZ()) <= radius) {
                count++;
            }
        }
        for (MatchBot bot : bots.values()) {
            Location loc = bot.getLocation();
            if (bot.getTeam() != team && loc != null
                    && Math.hypot(loc.getX() - at.getX(), loc.getZ() - at.getZ()) <= radius) {
                count++;
            }
        }
        return count;
    }

    private static Vector rotateFlat(Vector dir, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vector(dir.getX() * cos - dir.getZ() * sin, 0, dir.getX() * sin + dir.getZ() * cos);
    }

    private void markBotKick(MatchBot bot) {
        kickTrapImmunityUntil.put(bot.getUuid(), matchTick + KICK_TRAP_IMMUNITY_TICKS);
    }

    // ------------------------------------------------------------------
    // Goalkeeper holding the ball
    // ------------------------------------------------------------------

    public boolean isBallHeldByKeeper() {
        return keeperHolderId != null;
    }

    public UUID getKeeperHolderId() {
        return keeperHolderId;
    }

    private void beginKeeperHold(MatchBot keeper, boolean countSave) {
        SoccerPlayer sp = keeper.getProfile();
        keeperHolderId = keeper.getUuid();
        keeperHoldTicks = 36 + ThreadLocalRandom.current().nextInt(30);
        ball.haltMotion();
        touchBall(keeper.getUuid(), sp.getTeam());
        sp.addTouch();
        pendingOffsideCheck = null;
        placeBallInHands(keeper);
        ball.setFrozen(true);
        keeper.swingArm();
        if (countSave) {
            sp.addSave();
            if (crowd != null) {
                crowd.onSave(sp.getTeam());
            }
        }
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.9f, 0.65f);
            if (countSave) {
                loc.getWorld().spawnParticle(Particle.CRIT, loc, 10, 0.15, 0.15, 0.15, 0.04);
            }
        }
    }

    private void placeBallInHands(MatchBot keeper) {
        Location feet = keeper.getLocation();
        if (feet == null || ball == null) {
            return;
        }
        Location hands = feet.clone().add(keeper.getFacing().multiply(0.42));
        // Gathered at full stretch: the ball is clutched on the turf, not at chest height.
        hands.setY(feet.getY() + (keeper.isDiving() || keeper.isDown() ? 0.35 : 1.0));
        hands.setYaw(0f);
        hands.setPitch(0f);
        ball.teleport(hands);
    }

    private void tickKeeperHold() {
        if (keeperHolderId == null) {
            return;
        }
        MatchBot keeper = bots.get(keeperHolderId);
        if (keeper == null || ball == null || keeper.getLocation() == null) {
            releaseKeeperHold(true);
            return;
        }
        placeBallInHands(keeper);
        ball.setFrozen(true);
        if (--keeperHoldTicks <= 0) {
            goalkeeperDistribute(keeper);
        }
    }

    private void releaseKeeperHold(boolean unfreeze) {
        if (keeperHolderId == null) {
            return;
        }
        keeperHolderId = null;
        keeperHoldTicks = 0;
        if (unfreeze && ball != null) {
            ball.setFrozen(false);
        }
    }

    /** Rolls it out to a nearby team-mate, throws it, or punts it up the field. */
    private void goalkeeperDistribute(MatchBot keeper) {
        releaseKeeperHold(true);
        if (ball == null || keeper.getLocation() == null
                || (state != GameState.PLAYING && state != GameState.EXTRA_TIME)) {
            return;
        }
        SoccerPlayer sp = keeper.getProfile();
        Location hands = ball.getLocation();
        Location target = BotAi.keeperDistributionTarget(this, keeper);
        if (target == null) {
            target = midfieldClearanceTarget(hands);
        }
        double dist = Math.hypot(target.getX() - hands.getX(), target.getZ() - hands.getZ());
        Location from = hands;
        double power;
        double lift;
        if (dist <= 15.0) {
            from = keeper.getLocation().clone().add(keeper.getFacing().multiply(0.7));
            from.setY(keeper.getLocation().getY() + 0.3);
            ball.teleport(from);
            power = groundPassFraction(dist);
            lift = 0.0;
        } else if (dist <= 26.0) {
            power = Math.max(0.2, Math.min(0.7, groundPassFraction(dist) * 0.9));
            lift = 0.16;
        } else {
            power = 0.95;
            lift = 0.46;
        }
        keeper.faceLocation(target);
        Mechanics.Kick kick = Mechanics.computeBotShotToward(from, target, settings, power, lift);
        ball.applyImpulse(kick.impulse, kick.spin);
        touchBall(keeper.getUuid(), sp.getTeam());
        sp.addTouch();
        registerPass(sp);
        markBotKick(keeper);
        beginBotOffsidePhase(keeper);
        keeper.swingArm();
        keeper.setActionCooldown(16);
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, lift > 0.3 ? 0.8f : 1.1f);
        }
    }

    /** Power fraction for a ground ball that is still rolling when it reaches {@code distance}. */
    private double groundPassFraction(double distance) {
        double friction = Math.max(0.5, Math.min(0.99, settings.groundFriction));
        double v0 = distance * (1.0 - friction) + 0.2;
        double span = Math.max(0.1, settings.maxShotPower - settings.lightTouchPower);
        return Math.max(0.04, Math.min(0.8, (v0 - settings.lightTouchPower) / span));
    }

    // ------------------------------------------------------------------
    // Perception: what the ball looked like a moment ago
    // ------------------------------------------------------------------

    private void rememberBall() {
        if (ball == null) {
            return;
        }
        Location loc = ball.getLocation();
        if (loc == null) {
            return;
        }
        int slot = Math.floorMod(matchTick, BALL_MEMORY_TICKS);
        ballMemory[slot] = loc;
        ballVelocityMemory[slot] = ball.getVelocity();
    }

    private void forgetBallMemory() {
        java.util.Arrays.fill(ballMemory, null);
        java.util.Arrays.fill(ballVelocityMemory, null);
    }

    /** Where the ball was {@code delayTicks} ago: what a player is reacting to right now. */
    public Location perceivedBallLocation(int delayTicks) {
        int delay = Math.max(0, Math.min(BALL_MEMORY_TICKS - 1, delayTicks));
        Location seen = delay > 0 ? ballMemory[Math.floorMod(matchTick - delay, BALL_MEMORY_TICKS)] : null;
        return seen != null ? seen.clone() : getBallLocation();
    }

    public Vector perceivedBallVelocity(int delayTicks) {
        int delay = Math.max(0, Math.min(BALL_MEMORY_TICKS - 1, delayTicks));
        Vector seen = delay > 0 ? ballVelocityMemory[Math.floorMod(matchTick - delay, BALL_MEMORY_TICKS)] : null;
        if (seen != null) {
            return seen.clone();
        }
        return ball != null ? ball.getVelocity() : new Vector();
    }

    // ------------------------------------------------------------------
    // Offside for bots
    // ------------------------------------------------------------------

    private List<OffsideSnapshot.Body> botBodies() {
        List<OffsideSnapshot.Body> list = new ArrayList<>(bots.size());
        for (MatchBot bot : bots.values()) {
            Location feet = bot.getLocation();
            if (feet != null && !bot.getProfile().isSentOff()) {
                list.add(new OffsideSnapshot.Body(bot.getUuid(), bot.getTeam(), feet.clone()));
            }
        }
        return list;
    }

    private void beginBotOffsidePhase(MatchBot bot) {
        if (!settings.offsideEnabled || !settings.botOffsideAware || pitchAxis == null || ball == null) {
            return;
        }
        pendingOffsideCheck = OffsideSnapshot.capture(
                pitchAxis, bot.getTeam(), bot.getUuid(), ball.getLocation(), players, botBodies());
    }

    /** True when the touch was flagged offside (play has stopped; skip the touch). */
    private boolean botTouchBlockedByOffside(MatchBot bot) {
        if (!settings.botOffsideAware) {
            return false;
        }
        if (offsideExemptTouch) {
            offsideExemptTouch = false;
            pendingOffsideCheck = null;
            return false;
        }
        if (!settings.offsideEnabled || pendingOffsideCheck == null) {
            return false;
        }
        if (bot.getTeam() != pendingOffsideCheck.getAttackingTeam()) {
            pendingOffsideCheck = null;
            return false;
        }
        if (bot.getUuid().equals(pendingOffsideCheck.getPlayedBy())) {
            return false;
        }
        if (!pendingOffsideCheck.wasOffside(bot.getUuid())) {
            pendingOffsideCheck = null;
            return false;
        }
        Location at = bot.getLocation() != null ? bot.getLocation().clone() : ball.getLocation();
        callOffside(bot.getProfile(), at);
        return state == GameState.OFFSIDE;
    }

    public int getKickoffPendingTicks() {
        return kickoffPendingTicks;
    }

    public UUID getCelebrationScorerId() {
        return celebrationScorerId;
    }

    public TeamSide getCelebrationTeam() {
        return celebrationTeam;
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
        if (ball == null || !reachable(bot) || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Mechanics.Kick kick = Mechanics.compute(bot, settings, type, charge);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        if (type == BallKickEvent.KickType.SHOT) {
            sp.addShot();
        } else if (type == BallKickEvent.KickType.PASS || type == BallKickEvent.KickType.LOFT_PASS
                || type == BallKickEvent.KickType.CROSS_PASS || type == BallKickEvent.KickType.THROW_IN) {
            registerPass(sp);
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
        if (ball == null || !reachable(bot) || target == null || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedTarget = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), target, false);
        BotDifficulty difficulty = getBotDifficulty(bot.getTeam());
        double adjustedCharge = difficulty == BotDifficulty.EASY ? chargeFraction * 0.82 : chargeFraction;
        adjustedCharge *= kickPowerNoise(bot);
        Mechanics.Kick kick = Mechanics.computeBotPassToward(
                from != null ? from : bot.getLocation(), adjustedTarget, settings, adjustedCharge);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        registerPass(sp);
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85f, 1.05f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    public void botLoftPassAt(MatchBot bot, Location target) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot) || target == null || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedTarget = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), target, false);
        Mechanics.Kick kick = Mechanics.computeBotLoftPassToward(
                from != null ? from : bot.getLocation(), adjustedTarget, settings);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        registerPass(sp);
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 0.95f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 8, 0.1, 0.1, 0.1, 0.04);
        }
    }

    public void botCrossPassAt(MatchBot bot, Location target) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot) || target == null || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedTarget = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), target, false);
        Mechanics.Kick kick = Mechanics.computeBotCrossPassToward(
                from != null ? from : bot.getLocation(), adjustedTarget, settings);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        registerPass(sp);
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85f, 1.1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 10, 0.12, 0.08, 0.12, 0.03);
        }
    }

    public void botStrongPassAt(MatchBot bot, Location target, double powerFraction, double verticalLift) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || !reachable(bot) || target == null || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location from = ball.getLocation();
        Location adjustedTarget = botAdjustedTarget(bot, from != null ? from : bot.getLocation(), target, false);
        BotDifficulty difficulty = getBotDifficulty(bot.getTeam());
        double powerScale = difficulty == BotDifficulty.EASY ? 0.78 : 1.0;
        double passPower = Math.max(0.14, Math.min(0.55, powerFraction * powerScale * kickPowerNoise(bot)));
        double passLift = Math.max(0.0, Math.min(0.12, verticalLift));
        Mechanics.Kick kick = Mechanics.computeBotShotToward(
                from != null ? from : bot.getLocation(), adjustedTarget, settings, passPower, passLift);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        registerPass(sp);
        markBotKick(bot);
        beginBotOffsidePhase(bot);
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
        if (ball == null || !reachable(bot) || aimPoint == null || botTouchBlockedByOffside(bot)) {
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
        double lift = verticalLift * liftScale + shotLiftError(bot);
        Mechanics.Kick kick = Mechanics.computeBotShotToward(
                from != null ? from : bot.getLocation(), adjustedAim, settings,
                powerFraction * powerScale, lift);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addShot();
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    /**
     * Real kicks are never laser-perfect. The direction error grows with poor
     * technique, pressure, fatigue, kicking across the body and striking on the
     * run; shots spray more than passes.
     */
    private Location botAdjustedTarget(MatchBot bot, Location from, Location target, boolean shot) {
        if (!settings.botKickErrors) {
            return legacyAdjustedTarget(bot, from, target, shot);
        }
        if (target == null || from == null || from.getWorld() == null || target.getWorld() == null
                || !from.getWorld().equals(target.getWorld())) {
            return target;
        }
        Vector flat = target.toVector().subtract(from.toVector()).setY(0);
        if (flat.lengthSquared() < 0.25) {
            return target.clone();
        }
        double skill = kickSkill(bot);
        int pressure = countOpponentsNear(bot.getTeam(), bot.getLocation(), 2.8);
        double across = 1.0 - bot.getFacing().dot(flat.clone().normalize());
        double sigmaDeg = (shot ? 4.6 : 3.4) * (1.55 - skill)
                + Math.min(2, pressure) * (shot ? 1.6 : 1.1)
                + Math.max(0.0, across - 0.3) * 3.2
                + (1.0 - bot.getStaminaFraction()) * 2.2
                + (bot.getSpeed() > 0.24 ? 0.8 : 0.0);
        double errorDeg = ThreadLocalRandom.current().nextGaussian() * sigmaDeg;
        errorDeg = Math.max(-3.0 * sigmaDeg, Math.min(3.0 * sigmaDeg, errorDeg));
        Vector turned = rotateFlat(flat, Math.toRadians(errorDeg));
        Location adjusted = target.clone();
        adjusted.setX(from.getX() + turned.getX());
        adjusted.setZ(from.getZ() + turned.getZ());
        return adjusted;
    }

    private double kickSkill(MatchBot bot) {
        double base = switch (getBotDifficulty(bot.getTeam())) {
            case EASY -> 0.42;
            case MEDIUM -> 0.66;
            case HARD -> 0.84;
        };
        return Math.max(0.2, Math.min(0.98, base + (bot.composure() - 0.72) * 0.3));
    }

    /** Weight of pass: slightly over- or under-hit, more so for weaker players. */
    private double kickPowerNoise(MatchBot bot) {
        if (!settings.botKickErrors) {
            return 1.0;
        }
        double sigma = 0.03 + (1.0 - kickSkill(bot)) * 0.08;
        return Math.max(0.8, Math.min(1.2, 1.0 + ThreadLocalRandom.current().nextGaussian() * sigma));
    }

    /** Extra launch height when a shot is leaned back on (skied over the bar). */
    private double shotLiftError(MatchBot bot) {
        if (!settings.botKickErrors) {
            return 0.0;
        }
        int pressure = countOpponentsNear(bot.getTeam(), bot.getLocation(), 2.8);
        double sigma = 0.015 + (1.0 - kickSkill(bot)) * 0.06 + Math.min(2, pressure) * 0.015;
        return Math.abs(ThreadLocalRandom.current().nextGaussian()) * sigma;
    }

    private Location legacyAdjustedTarget(MatchBot bot, Location from, Location target, boolean shot) {
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
        if (ball == null || !reachable(bot) || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Mechanics.Kick kick = Mechanics.computeBotShot(bot, settings, powerFraction, verticalLift);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        sp.addShot();
        markBotKick(bot);
        beginBotOffsidePhase(bot);
        bot.swingArm();
        Location loc = ball.getLocation();
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 1f);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.1, 0.1, 0.1, 0.03);
        }
    }

    public void botDribble(MatchBot bot) {
        if (ball == null || bot.getLocation() == null || keeperHolderId != null
                || botTouchBlockedByOffside(bot)) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Vector nudge = Mechanics.dribbleNudge(bot, settings);
        touchBall(bot.getUuid(), sp.getTeam());
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
        if (ball == null || ball.getLocation() == null || bot.getLocation() == null
                || keeperHolderId != null) {
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
        botGoalkeeperSave(bot, false);
    }

    public void botGoalkeeperSave(MatchBot bot, boolean punchClear) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (ball == null || bot.getLocation() == null || bot.getRole() != BotRole.GOALKEEPER) {
            return;
        }
        if (!reachable(bot, Math.max(2.05, settings.kickReach * 1.15), false, 2.65)) {
            return;
        }
        goalkeeperGather(bot, ball.getSpeed() > 0.45, punchClear);
    }

    /**
     * Deflects a shot the keeper barely reached — ball is punched upfield instead of
     * slipping through for a goal.
     */
    public void botGoalkeeperParry(MatchBot bot) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return;
        }
        if (!bot.canAct()) {
            return;
        }
        if (ball == null || ball.getLocation() == null || bot.getLocation() == null
                || bot.getRole() != BotRole.GOALKEEPER) {
            return;
        }
        Location ballLoc = ball.getLocation();
        Location keeperLoc = bot.getLocation();
        double horiz = Math.hypot(keeperLoc.getX() - ballLoc.getX(), keeperLoc.getZ() - ballLoc.getZ());
        double vert = Math.abs(keeperLoc.getY() - ballLoc.getY());
        if (horiz > settings.kickReach * 1.45 + 0.6 || vert > 2.7) {
            return;
        }

        SoccerPlayer sp = bot.getProfile();
        TeamSide team = sp.getTeam();
        Location ownGoal = arena.getGoal(team) != null ? arena.getGoal(team).getCenter() : null;
        Location oppGoal = arena.getGoal(team.opposite()) != null
                ? arena.getGoal(team.opposite()).getCenter() : null;

        Vector away = new Vector(0, 0.42, 0);
        if (ownGoal != null && oppGoal != null) {
            Vector field = oppGoal.toVector().subtract(ownGoal.toVector());
            field.setY(0);
            if (field.lengthSquared() > 1e-6) {
                field.normalize();
                away = field.clone().multiply(0.72).setY(0.38);
                Vector lateral = new Vector(-field.getZ(), 0, field.getX());
                double side = ballLoc.toVector().subtract(ownGoal.toVector()).dot(lateral);
                away.add(lateral.multiply(Math.signum(side) * 0.22));
            }
        }

        ball.haltMotion();
        touchBall(bot.getUuid(), team);
        ball.applyImpulse(away, new Vector(0, 0.08, 0));
        sp.addTouch();
        sp.addSave();
        if (crowd != null) {
            crowd.onSave(team);
        }
        bot.swingArm();
        bot.setActionCooldown(4);
        if (ballLoc.getWorld() != null) {
            ballLoc.getWorld().playSound(ballLoc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.65f);
            ballLoc.getWorld().spawnParticle(Particle.CRIT, ballLoc, 12, 0.2, 0.15, 0.2, 0.06);
        }
    }

    /**
     * The keeper claims the ball and immediately distributes it: he stops the
     * incoming ball, picks the best-placed teammate up the field (or the most open
     * channel) via the AI, and launches a powerful, distance-scaled kick — a hard
     * punt for a long ball, or a firmer driven throw for a nearby option.
     *
     * @param countSave whether this counted as a save (a fast inbound ball)
     */
    private void goalkeeperGather(MatchBot bot, boolean countSave, boolean punchClear) {
        if (ball == null || ball.getLocation() == null || bot.getLocation() == null
                || bot.getRole() != BotRole.GOALKEEPER) {
            return;
        }
        SoccerPlayer sp = bot.getProfile();
        Location ballLoc = ball.getLocation();

        double depth = BotAi.keeperDepthFromGoalLine(this, sp.getTeam(), bot.getLocation());
        // Back-pass rule: a ball deliberately played to him by a team-mate is not his to pick up.
        boolean backPass = ball.getLastTouchTeam() == sp.getTeam() && !bot.getUuid().equals(ball.getLastTouchId());
        if (settings.botKeeperHoldsBall && !punchClear && !backPass && keeperHolderId == null && depth <= 11.0
                && ballLoc.getY() - bot.getLocation().getY() < 2.4) {
            beginKeeperHold(bot, countSave);
            return;
        }

        boolean cameOut = punchClear || depth >= 2.0;

        Location target;
        double power;
        double lift;
        if (cameOut) {
            target = BotAi.keeperClearanceTarget(this, bot);
            if (target == null) {
                target = midfieldClearanceTarget(ballLoc);
            }
            power = 0.94;
            lift = 0.22;
        } else {
            target = BotAi.keeperDistributionTarget(this, bot);
            if (target == null) {
                target = midfieldClearanceTarget(ballLoc);
            }
            double dist = Math.hypot(target.getX() - ballLoc.getX(), target.getZ() - ballLoc.getZ());
            if (dist > 22.0) {
                power = 0.95;
                lift = 0.48;
            } else if (dist > 13.0) {
                power = 0.80;
                lift = 0.30;
            } else {
                power = 0.60;
                lift = 0.06;
            }
        }

        Mechanics.Kick kick = Mechanics.computeBotShotToward(ballLoc, target, settings, power, lift);
        touchBall(bot.getUuid(), sp.getTeam());
        ball.haltMotion();
        ball.teleport(ballLoc);
        ball.applyImpulse(kick.impulse, kick.spin);
        sp.addTouch();
        registerPass(sp);
        markBotKick(bot);
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
        if (cameOut && !settings.botKeeperHoldsBall) {
            Location home = BotAi.clampKeeperPosition(this, sp.getTeam(),
                    BotAi.keeperSpawnSpot(this, sp.getTeam()));
            if (home != null) {
                bot.setLocation(home);
                bot.syncVisual();
            }
        }
    }

    private void repositionBotsForKickoff() {
        for (MatchBot bot : bots.values()) {
            Location spot = resolveBotSpawn(bot.getTeam(), bot.getFormationSlot());
            if (spot != null) {
                bot.setLocation(spot);
                bot.syncVisual();
            }
        }
    }

    private Location resolveHumanFormationSpawn(SoccerPlayer sp) {
        if (sp == null || sp.isBot() || sp.isReferee() || sp.getTeam() == null) {
            return null;
        }
        TeamSide team = sp.getTeam();
        BotFormation formation = getFormation(team);
        int squadIndex = findClaimedSquadIndex(sp.getUuid(), team);
        int formationSlot;
        if (squadIndex >= 0) {
            formationSlot = formation.formationSlotForSquadIndex(squadIndex, false);
        } else {
            formationSlot = formation.strikerSlotIndex();
        }
        Location spot = FormationSpots.spawnForSlot(arena, team, formation, formationSlot);
        if (spot != null) {
            return spot;
        }
        if (formationSlot <= 0) {
            return BotAi.keeperSpawnSpot(this, team);
        }
        return arena.getTeamSpawn(team);
    }

    /** Places humans and bots on formation spots (lobby / pre-kickoff). */
    public void deployFormationLineup() {
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location spot = resolveHumanFormationSpawn(sp);
            if (spot == null) {
                spot = arena.getTeamSpawn(sp.getTeam());
            }
            if (spot != null) {
                player.teleport(spot);
            }
        }
        repositionBotsForKickoff();
    }

    // ==================================================================
    // Per-tick simulation
    // ==================================================================

    private boolean shouldRecordReplay() {
        if (celebrating || goalReplay != null) {
            return false;
        }
        return state.isLive() || state == GameState.THROW_IN || state == GameState.OFFSIDE;
    }

    private boolean shouldSimulateBall() {
        return switch (state) {
            case KICKOFF, PLAYING, THROW_IN, OFFSIDE, HALFTIME, EXTRA_TIME, PENALTIES, REPLAY, ENDED -> true;
            default -> false;
        };
    }

    public void tick() {
        matchTick++;

        if (state == GameState.INTRO) {
            tickIntro();
            return;
        }

        tickStoppageAccumulator();

        GameState refState = goalReplay != null
                ? (varReviewPlayback ? GameState.VAR_REVIEW : GameState.REPLAY)
                : state;
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
            detectWoodworkContact();
        }
        rememberBall();

        if (isPenaltySaveWindow()) {
            processPenaltyKeeperSaves();
        }

        if (replayRecorder != null && shouldRecordReplay()
                && (settings.replayEnabled || humanRefereeMatch)
                && matchTick % REPLAY_CAPTURE_INTERVAL == 0) {
            if (replayAnimations != null) {
                replayAnimations.tick();
            }
            replayRecorder.capture(this);
        } else if (replayAnimations != null && shouldRecordReplay()
                && (settings.replayEnabled || humanRefereeMatch)) {
            replayAnimations.tick();
        }

        if (state == GameState.PLAYING || state == GameState.EXTRA_TIME) {
            if (kickoffPendingTicks > 0) {
                kickoffPendingTicks--;
            }
            if (!celebrating) {
                for (SoccerPlayer sp : players.values()) {
                    Player p = sp.getBukkitPlayer();
                    if (p != null && p.isOnline()) {
                        handleStamina(sp, p);
                    }
                }
                tickKeeperHold();
                processInteractions();
                processBotInteractions();
                checkGoal();
                checkBoundary();
                if (autoReferee != null) {
                    autoReferee.tick(this);
                }
            }
            if (throwInBoundaryGrace > 0) {
                throwInBoundaryGrace--;
            }
            trackPossession();
        } else if (state == GameState.OFFSIDE) {
            tickOffsideRestart();
            if (autoReferee != null) {
                autoReferee.tickRestart(this);
            }
        } else if (state == GameState.THROW_IN) {
            tickThrowInRestart();
            if (autoReferee != null) {
                autoReferee.tickRestart(this);
            }
        } else if (state == GameState.PENALTIES) {
            if (shootoutActive && shootoutAwaitingResult && !celebrating) {
                tickShootoutKickResult();
            }
            if (shootoutActive && shootoutInterKickCooldown > 0) {
                shootoutInterKickCooldown--;
                if (shootoutInterKickCooldown == 0 && !shootoutAwaitingResult && !celebrating) {
                    beginShootoutKick();
                }
            }
        }

        if (penaltyKeeperReleaseTicks > 0) {
            penaltyKeeperReleaseTicks--;
            if (penaltyKeeperReleaseTicks == 0) {
                releasePenaltyParticipants();
                penaltyKeeperDived = false;
            }
        }

        if (botsTickActive() && !bots.isEmpty()) {
            tickBots();
        }

        if (celebrating) {
            celebrationTicks--;
            if (celebrationTicks <= 0) {
                celebrating = false;
                if (shootoutActive && shootoutScheduleNextKick) {
                    shootoutScheduleNextKick = false;
                    shootoutInterKickCooldown = 45;
                } else if (!ended && (state == GameState.PLAYING || state == GameState.EXTRA_TIME)) {
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
        UUID toucher = ball.getLastTouchId();
        if (toucher != null) {
            SoccerPlayer sp = getPlayer(toucher);
            if (sp != null && !sp.isReferee()) {
                sp.addPossessionTick();
            }
        }
    }

    private void touchBall(Player player, TeamSide team) {
        touchBall(player.getUniqueId(), team);
    }

    private void touchBall(UUID id, TeamSide team) {
        if (ball != null) {
            ball.recordTouch(id, team);
        }
        onBallTouched(id, team);
    }

    private void onBallTouched(UUID id, TeamSide team) {
        kickoffPendingTicks = 0;
        if (pendingPasserId != null) {
            if (!pendingPasserId.equals(id)) {
                SoccerPlayer passer = getPlayer(pendingPasserId);
                if (passer != null && passer.getTeam() == team) {
                    passer.addPassCompleted();
                    SoccerPlayer receiver = getPlayer(id);
                    if (receiver != null) {
                        receiver.addPassReceived();
                    }
                }
            }
            pendingPasserId = null;
        }
    }

    private boolean isKickTrapImmune(UUID playerId) {
        Long until = kickTrapImmunityUntil.get(playerId);
        if (until == null) {
            return false;
        }
        if (matchTick > until) {
            kickTrapImmunityUntil.remove(playerId);
            return false;
        }
        return true;
    }

    private void registerPass(SoccerPlayer sp) {
        sp.addPass();
        pendingPasserId = sp.getUuid();
    }

    private void processInteractions() {
        if (ball == null || ball.getLocation() == null || keeperHolderId != null) {
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

            if (isKickTrapImmune(p.getUniqueId())) {
                continue;
            }

            boolean keeperZone = isInOwnGoalBox(sp.getTeam(), feet);
            boolean moving = p.getVelocity().getX() * p.getVelocity().getX()
                    + p.getVelocity().getZ() * p.getVelocity().getZ() > 0.0025;
            boolean trap = speed > 0.12;
            boolean dribble = speed <= 0.12 && moving;

            if (!trap && !dribble) {
                continue;
            }

            if (tryHandleOffsideTouch(p, sp.getTeam())) {
                continue;
            }

            if (trap) {
                double contactSpeed = speed;
                ball.haltMotion();
                if (keeperZone && contactSpeed > 0.6) {
                    sp.addSave();
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.7f);
                    if (crowd != null) {
                        crowd.onSave(sp.getTeam());
                    }
                    p.getWorld().spawnParticle(Particle.CRIT, ballLoc, 6, 0.1, 0.1, 0.1, 0.05);
                }
            }

            touchBall(p, sp.getTeam());
            sp.addTouch();

            if (moving) {
                Vector nudge = Mechanics.dribbleNudge(p, settings);
                ball.applyImpulse(nudge, null);
            }
        }
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

    public boolean isInOwnGoalBox(TeamSide team, Location loc) {
        return arena.getGoal(team) != null && arena.getGoal(team).contains(loc);
    }

    // ==================================================================
    // Goal & boundary detection
    // ==================================================================

    private void detectWoodworkContact() {
        if (ball == null || !ball.hadBlockCollisionThisTick()) {
            return;
        }
        Location loc = ball.getLocation();
        if (loc != null && arena.isNearGoalFrame(loc)) {
            ball.suppressGoalScoringUntil(matchTick + 12);
        }
    }

    private void checkGoal() {
        if (celebrating || goalReplay != null || ended || ball == null || keeperHolderId != null) {
            return;
        }
        Location curr = ball.getLocation();
        if (curr == null) {
            return;
        }

        Location prev = ball.getPreviousTickLocation();
        TeamSide goalBox = null;
        if (prev != null) {
            goalBox = arena.goalContainingAlongPath(prev, curr);
        }
        if (goalBox == null) {
            goalBox = arena.goalContaining(curr);
        }
        if (goalBox == null) {
            return;
        }
        if (ball.isGoalScoringSuppressed(matchTick) && arena.isNearGoalFrame(curr)) {
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
            SoccerPlayer last = getPlayer(lastId);
            if (lastTeam == scoringTeam) {
                scorer = last;
                UUID prevId = ball.getPrevTouchId();
                if (prevId != null && !prevId.equals(lastId)
                        && ball.getPrevTouchTeam() == scoringTeam) {
                    assist = getPlayer(prevId);
                }
            } else {
                // Last touch was the conceding team. If the ball was previously
                // played by the scoring team (a shot the keeper/defender only got
                // a touch on but could not stop), credit that shooter instead of
                // blaming the deflector with an own goal.
                UUID prevId = ball.getPrevTouchId();
                if (prevId != null && !prevId.equals(lastId)
                        && ball.getPrevTouchTeam() == scoringTeam) {
                    scorer = getPlayer(prevId);
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

        celebrationScorerId = scorer != null && !ownGoal ? scorer.getUuid() : null;
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
        boolean varPlayback = varReviewPlayback;
        varReviewPlayback = false;
        goalReplay = null;
        forEachPlayer(this::resetParticipantMovement);
        scheduleMovementReset();

        if (varPlayback) {
            setState(GameState.REFEREE_STOP);
            if (ball != null) {
                ball.setFrozen(true);
            }
            broadcast(plugin.messages().raw("referee-var-ended"));
            return;
        }

        if (stateBeforeReplay != null) {
            setState(stateBeforeReplay);
            stateBeforeReplay = null;
        }
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
        celebrationTicks = settings.botCelebrations && !bots.isEmpty() ? 110 : 20 * 4;
        celebrationTeam = team;
        if (team != null) {
            kickoffTeam = team.opposite();
        }
        if (ball != null) {
            ball.setFrozen(true);
        }
        Location center = arena.getBallSpawn();
        if (center != null) {
            launchFirework(center, team);
        }
    }

    private void checkBoundary() {
        if (celebrating || goalReplay != null || ended || ball == null || ball.getLocation() == null || !settings.resetWhenOut
                || keeperHolderId != null) {
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
        if (ball.isGoalScoringSuppressed(matchTick)) {
            return false;
        }
        TeamSide goalBox = null;
        Location prev = ball.getPreviousTickLocation();
        if (prev != null) {
            goalBox = arena.goalContainingAlongPath(prev, loc);
        }
        if (goalBox == null) {
            goalBox = arena.goalContaining(loc);
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
        touchBall(sp.getUuid(), sp.getTeam());
        ball.applyImpulse(ensureThrowInImpulse(impulse), spin);
        ball.setFrozen(false);
        sp.addTouch();
        registerPass(sp);
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
        if (type != BallKickEvent.KickType.PASS && type != BallKickEvent.KickType.LOFT_PASS
                && type != BallKickEvent.KickType.CROSS_PASS && type != BallKickEvent.KickType.SHOT) {
            return;
        }
        pendingOffsideCheck = OffsideSnapshot.capture(
                pitchAxis, team, player.getUniqueId(), ball.getLocation(), players, botBodies());
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
        penaltyKeeperId = null;
        penaltyLineupSpots.clear();
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
            // An onside team-mate has played it: the old pass no longer counts.
            pendingOffsideCheck = null;
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
        if (!teamHasHumanPlayers(defending)) {
            assignBotRestartTaker(restart, defending);
            return;
        }
        if (!settings.offsideAutoTaker) {
            return;
        }
        SoccerPlayer taker = OffsideRestartHelper.findNearestTaker(players, defending, restart);
        if (taker == null) {
            assignBotRestartTaker(restart, defending);
            return;
        }
        applyFreeKickTaker(taker, restart, defending, true);
    }

    /** All-bot side: the nearest outfield bot walks up and takes the free kick. */
    private void assignBotRestartTaker(Location restart, TeamSide team) {
        MatchBot nearest = null;
        double best = Double.MAX_VALUE;
        for (MatchBot bot : bots.values()) {
            if (bot.getTeam() != team || bot.getLocation() == null) {
                continue;
            }
            double dist = OffsideRestartHelper.horizontalDistance(bot.getLocation(), restart);
            if (bot.getRole() == BotRole.GOALKEEPER) {
                dist += 12.0;
            }
            if (dist < best) {
                best = dist;
                nearest = bot;
            }
        }
        if (nearest == null) {
            return;
        }
        freeKickTakerId = nearest.getUuid();
        scheduleRefereeRestartKick();
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
        SoccerPlayer taker = getPlayer(freeKickTakerId);
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

        if (refereePenaltyRestart) {
            tickPenaltyRestart(ballLoc, takerSpot);
        }

        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (refereePenaltyRestart) {
                if (freeKickTakerId != null && sp.getUuid().equals(freeKickTakerId)) {
                    player.setWalkSpeed(0.2f);
                    if (settings.offsideTakerGlow) {
                        player.setGlowing(true);
                    }
                    continue;
                }
                if (penaltyKeeperId != null && sp.getUuid().equals(penaltyKeeperId)) {
                    continue;
                }
                Location lineupSpot = penaltyLineupSpots.get(sp.getUuid());
                if (lineupSpot != null) {
                    if (OffsideRestartHelper.horizontalDistance(player.getLocation(), lineupSpot) > 0.75) {
                        player.teleport(lineupSpot);
                    }
                    player.setWalkSpeed(0.0f);
                    player.setGlowing(false);
                }
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
                if (refereePenaltyRestart && penaltyKeeperId != null
                        && sp.getUuid().equals(penaltyKeeperId)) {
                    continue;
                }
                OffsideRestartHelper.restrainOpponent(player, ballLoc, settings);
            } else {
                player.setWalkSpeed(0.2f);
            }
        }

        if (settings.actionBarHud && offsideRestartPulse % 4 == 0) {
            updateOffsideActionBar();
        }
    }

    private void tickPenaltyRestart(Location ballLoc, Location takerSpot) {
        if (freeKickTakerId != null) {
            MatchBot takerBot = bots.get(freeKickTakerId);
            if (takerBot != null && takerSpot != null) {
                takerBot.setLocation(takerSpot);
                Location aim = randomPenaltyTarget(freeKickTeam);
                if (aim != null) {
                    takerBot.faceLocation(aim);
                }
            }
        }
        for (Map.Entry<UUID, Location> entry : penaltyLineupSpots.entrySet()) {
            MatchBot bot = bots.get(entry.getKey());
            if (bot != null) {
                bot.setLocation(entry.getValue());
                bot.faceLocation(ballLoc);
            }
        }
        if (penaltyKeeperId != null && !penaltyKeeperDived) {
            Location keeperSpot = computeGoalkeeperSpot(freeKickTeam.opposite());
            MatchBot keeperBot = bots.get(penaltyKeeperId);
            if (keeperBot != null && keeperSpot != null) {
                keeperBot.setLocation(keeperSpot);
                keeperBot.faceLocation(ballLoc);
            } else {
                SoccerPlayer keeper = players.get(penaltyKeeperId);
                if (keeper != null && !keeper.isBot()) {
                    Player player = keeper.getBukkitPlayer();
                    if (player != null && player.isOnline()) {
                        Location feet = player.getLocation();
                        Location clamped = BotAi.clampKeeperPosition(
                                this, freeKickTeam.opposite(), feet);
                        if (clamped != null
                                && OffsideRestartHelper.horizontalDistance(feet, clamped) > 0.55) {
                            clamped.setYaw(feet.getYaw());
                            clamped.setPitch(feet.getPitch());
                            player.teleport(clamped);
                        }
                        player.setWalkSpeed(0.18f);
                    }
                }
            }
        }
    }

    private void updateOffsideActionBar() {
        String takerName = freeKickTakerName();
        String takerKey = refereePenaltyRestart ? "referee-penalty-actionbar-taker" : "offside-actionbar-taker";
        String waitKey = refereePenaltyRestart ? "referee-penalty-actionbar-wait" : "offside-actionbar-wait";
        String keeperKey = "referee-penalty-actionbar-keeper";
        forEachPlayer(p -> {
            if (refereePenaltyRestart && penaltyKeeperId != null
                    && p.getUniqueId().equals(penaltyKeeperId)) {
                ActionBar.send(p, plugin.messages().raw(keeperKey));
            } else if (freeKickTakerId != null && p.getUniqueId().equals(freeKickTakerId)) {
                ActionBar.send(p, plugin.messages().raw(takerKey));
            } else {
                ActionBar.send(p, plugin.messages().format(waitKey,
                        "player", takerName, "team", teamName(freeKickTeam)));
            }
        });
    }

    private void endFreeKick() {
        boolean wasPenalty = refereePenaltyRestart;
        boolean shootoutKick = shootoutActive && wasPenalty;
        TeamSide kickingTeam = freeKickTeam;

        if (wasPenalty) {
            triggerPenaltyKeeperDive();
            penaltyKeeperReleaseTicks = 60;
        }

        if (shootoutKick) {
            shootoutKickingTeam = kickingTeam;
            shootoutAwaitingResult = true;
            shootoutResultTicks = 0;
        }

        clearFreeKickRestart();
        refereePenaltyRestart = false;

        if (shootoutKick) {
            setState(GameState.PENALTIES);
            return;
        }

        freeKickTeam = null;
        setState(resumeAfterFreeKick);
        forEachPlayer(p -> p.sendTitle(Text.color(plugin.messages().raw("play-on")), "", 0, 20, 10));
    }

    private void releasePenaltyParticipants() {
        double groundY = pitchGroundY();
        for (MatchBot bot : bots.values()) {
            bot.resetStandingPose();
            Location loc = bot.getLocation();
            if (loc != null) {
                loc.setY(groundY);
                bot.setLocation(loc);
            }
        }
        for (SoccerPlayer sp : players.values()) {
            if (sp.isReferee() || sp.isSentOff() || sp.isBot()) {
                continue;
            }
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                player.setSneaking(false);
                player.setSwimming(false);
                Location loc = player.getLocation();
                loc.setY(groundY);
                player.teleport(loc);
                player.setWalkSpeed(0.2f);
            }
        }
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
        if (refereePenaltyRestart) {
            performPenaltyKick(player, sp, type);
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

        touchBall(player, sp.getTeam());
        ball.applyImpulse(event.getImpulse(), event.getSpin());
        ball.setFrozen(false);
        sp.addTouch();
        registerPass(sp);

        endFreeKick();

        Location loc = ball.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 6, 0.1, 0.1, 0.1, 0.05);
    }

    private void performPenaltyKick(Player player, SoccerPlayer sp, BallKickEvent.KickType type) {
        if (freeKickTakerId != null && !player.getUniqueId().equals(freeKickTakerId)) {
            plugin.messages().send(player, "offside-wrong-taker", "player", freeKickTakerName());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        if (!reachable(player, settings.kickReach * 1.6)) {
            return;
        }

        double charge = type == BallKickEvent.KickType.LOFT_PASS
                ? 0.0
                : sp.chargeFraction(matchTick, settings.chargeTime * 20.0);
        Mechanics.Kick kick = Mechanics.compute(player, settings, BallKickEvent.KickType.SHOT, charge);

        BallKickEvent event = new BallKickEvent(player, ball, BallKickEvent.KickType.SHOT, kick.impulse, kick.spin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        touchBall(player, sp.getTeam());
        ball.applyImpulse(event.getImpulse(), event.getSpin());
        ball.setFrozen(false);
        sp.addTouch();
        sp.addShot();
        sp.stopCharging();
        player.setExp(0f);
        player.setGlowing(false);
        endFreeKick();

        Location loc = ball.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 8, 0.1, 0.1, 0.1, 0.05);
    }

    private void resetBallToCenter(String message) {
        ball.teleport(ballSpawnLocation());
        ball.clearTouchHistory();
        clearOffsidePhase();
        offsideExemptTouch = true;
        if (message != null && !message.isEmpty()) {
            broadcast(message);
        }
        forEachPlayer(p -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f));
    }

    // ==================================================================
    // Player actions (invoked by listeners)
    // ==================================================================

    public void shoot(Player player) {
        if (isPenaltyKeeper(player.getUniqueId()) && isPenaltySaveWindow()) {
            attemptPenaltyKeeperSave(player);
            return;
        }
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
        pass(player, false);
    }

    public void pass(Player player, boolean loft) {
        if (state == GameState.THROW_IN) {
            performThrowIn(player, BallKickEvent.KickType.THROW_IN);
            return;
        }
        if (state == GameState.OFFSIDE) {
            performFreeKick(player, BallKickEvent.KickType.PASS);
            return;
        }
        kick(player, loft ? BallKickEvent.KickType.LOFT_PASS : BallKickEvent.KickType.PASS);
    }

    public boolean crossPass(Player player) {
        if (state == GameState.THROW_IN || state == GameState.OFFSIDE) {
            return false;
        }
        return kick(player, BallKickEvent.KickType.CROSS_PASS);
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

    private boolean kick(Player player, BallKickEvent.KickType type) {
        if (state != GameState.PLAYING && state != GameState.EXTRA_TIME) {
            return false;
        }
        if (keeperHolderId != null) {
            return false;
        }
        SoccerPlayer sp = players.get(player.getUniqueId());
        if (sp == null || ball == null || !reachable(player)) {
            return false;
        }
        double charge = type == BallKickEvent.KickType.LOFT_PASS
                || type == BallKickEvent.KickType.CROSS_PASS
                ? 0.0
                : sp.chargeFraction(matchTick, settings.chargeTime * 20.0);
        Mechanics.Kick kick = Mechanics.compute(player, settings, type, charge);

        BallKickEvent event = new BallKickEvent(player, ball, type, kick.impulse, kick.spin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }

        beginOffsidePhase(player, sp.getTeam(), type);
        touchBall(player, sp.getTeam());
        boolean dampenBounce = type == BallKickEvent.KickType.CROSS_PASS;
        if (type == BallKickEvent.KickType.PASS || type == BallKickEvent.KickType.THROW_IN) {
            ball.applyImpulse(event.getImpulse(), event.getSpin());
        } else {
            ball.applyKick(event.getImpulse(), event.getSpin(), dampenBounce);
        }
        kickTrapImmunityUntil.put(player.getUniqueId(), matchTick + KICK_TRAP_IMMUNITY_TICKS);
        markReplayKickAnimation(player.getUniqueId(), type);
        sp.addTouch();
        if (type == BallKickEvent.KickType.SHOT) {
            sp.addShot();
            if (settings.staminaEnabled) {
                sp.drainStamina(settings.chargeDrain * 4, 0);
            }
        } else {
            registerPass(sp);
        }
        sp.stopCharging();
        player.setExp(0f);

        Location loc = ball.getLocation();
        float pitch = type == BallKickEvent.KickType.LOFT_PASS
                ? 1.2f
                : type == BallKickEvent.KickType.CROSS_PASS
                ? 0.85f
                : (float) (1.0 + Math.min(1.0, charge));
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, pitch);
        int particles = type == BallKickEvent.KickType.LOFT_PASS
                || type == BallKickEvent.KickType.CROSS_PASS ? 10 : 8;
        player.getWorld().spawnParticle(Particle.CLOUD, loc, particles, 0.1, 0.1, 0.1, 0.05);
        if (type == BallKickEvent.KickType.CROSS_PASS) {
            equipCrossPassTrigger(player);
        }
        return true;
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

        if (ball != null && keeperHolderId == null && reachable(player, settings.kickReach * 1.3)) {
            Mechanics.Kick kick = Mechanics.compute(player, settings, BallKickEvent.KickType.TACKLE, 0);
            BallKickEvent event = new BallKickEvent(player, ball,
                    BallKickEvent.KickType.TACKLE, kick.impulse, kick.spin);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                touchBall(player, sp.getTeam());
                ball.applyImpulse(event.getImpulse(), event.getSpin());
            }
        }
        if (autoReferee != null) {
            autoReferee.onHumanSlideTackle(this, player);
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
                if (inStoppageTime && stoppageSecondsAdded > 0) {
                    double played = halfDuration + (stoppageSecondsAdded - stoppageSecondsRemaining);
                    return Math.min(1.0, played / (halfDuration + stoppageSecondsAdded));
                }
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
                int halfDur = halfDurationSeconds();
                int base = (currentHalf - 1) * halfDur
                        + Math.max(0, halfDur - secondsRemaining);
                if (inStoppageTime) {
                    return base + Math.max(0, stoppageSecondsAdded - stoppageSecondsRemaining);
                }
                return base;
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
        placeholders.put("red_name", teamChat(TeamSide.RED) + teamName(TeamSide.RED));
        placeholders.put("blue_name", teamChat(TeamSide.BLUE) + teamName(TeamSide.BLUE));
        placeholders.put("time", clockText());
        placeholders.put("possession", possessionText());
        placeholders.put("players", String.valueOf(players.size()));
        scoreboard.update(settings.scoreboardTitle, settings.scoreboardLines, placeholders);
    }

    private String clockText() {
        switch (state) {
            case PLAYING:
            case THROW_IN:
            case OFFSIDE:
                if (inStoppageTime) {
                    int halfDuration = halfDurationSeconds();
                    int elapsed = (currentHalf - 1) * halfDuration + halfDuration;
                    int stoppageElapsed = Math.max(0, stoppageSecondsAdded - stoppageSecondsRemaining);
                    return Text.formatTime(elapsed + stoppageElapsed)
                            + " +" + Text.formatTime(stoppageSecondsRemaining)
                            + (settings.halves > 1
                            ? plugin.messages().format("half-suffix", "half", String.valueOf(currentHalf))
                            : "");
                }
                return Text.formatTime(elapsedMatchSeconds())
                        + (settings.halves > 1
                        ? plugin.messages().format("half-suffix", "half", String.valueOf(currentHalf))
                        : "");
            case EXTRA_TIME:
                return Text.formatTime(elapsedMatchSeconds());
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
        Color color = getTeamKitColor(team);
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
            case COUNTDOWN, INTRO, KICKOFF, PLAYING, THROW_IN, OFFSIDE, HALFTIME,
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
            case INTRO -> 72;
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
        return celebrating || goalReplay != null || state == GameState.REFEREE_STOP
                || state == GameState.INTRO;
    }

    public boolean blocksPlayerInput(SoccerPlayer sp) {
        if (sp != null && sp.isReferee()) {
            return false;
        }
        if (sp != null && sp.isSentOff()) {
            return true;
        }
        return blocksPlayerInput();
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
            if (sp.isReferee() || sp.isSentOff() || sp.getTeam() != team) {
                continue;
            }
            count++;
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
        int fieldPlayers = 0;
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee()) {
                fieldPlayers++;
            }
        }
        return fieldPlayers + bots.size() >= settings.maxPlayersPerTeam * 2;
    }

    public boolean isRefereeSlotTaken() {
        return hasHumanReferee();
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
        if (redNationCode != null || blueNationCode != null) {
            return getTeamKitChatColor(team);
        }
        return team == TeamSide.RED ? settings.redChatColor : settings.blueChatColor;
    }

    public boolean contains(UUID uuid) {
        return players.containsKey(uuid);
    }

    public SoccerPlayer getPlayer(UUID uuid) {
        SoccerPlayer sp = players.get(uuid);
        if (sp != null) {
            return sp;
        }
        MatchBot bot = bots.get(uuid);
        return bot != null ? bot.getProfile() : null;
    }

    private SoccerPlayer resolveParticipant(UUID uuid) {
        return getPlayer(uuid);
    }

    private String formatMatchClock() {
        if (inStoppageTime) {
            return "+" + Text.formatTime(stoppageSecondsRemaining);
        }
        return Text.formatTime(secondsRemaining);
    }

    public Arena getArena() {
        return arena;
    }

    public int getThrowInRestartPulse() {
        return throwInRestartPulse;
    }

    public UUID getThrowInTakerId() {
        return throwInTakerId;
    }

    public int getOffsideRestartPulse() {
        return offsideRestartPulse;
    }

    public boolean isPenaltyRestart() {
        return refereePenaltyRestart;
    }

    public UUID getFreeKickTakerId() {
        return freeKickTakerId;
    }

    public TeamSide getFreeKickTeam() {
        return freeKickTeam;
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

    public void setNationCodes(String redCode, String blueCode) {
        this.redNationCode = redCode;
        this.blueNationCode = blueCode;
        refreshKitColors();
        refreshBotKitVisuals();
        refreshHumanKitVisuals();
    }

    public String getRedNationCode() {
        return redNationCode;
    }

    public String getBlueNationCode() {
        return blueNationCode;
    }

    public String getChampionshipId() {
        return championshipId;
    }

    public String getFixtureId() {
        return fixtureId;
    }

    public long getRedPossessionTicks() {
        return redPossessionTicks;
    }

    public long getBluePossessionTicks() {
        return bluePossessionTicks;
    }

    public int getRedPenaltyFouls() {
        return redPenaltyFouls;
    }

    public int getBluePenaltyFouls() {
        return bluePenaltyFouls;
    }

    /** All field players and bots (excludes referees). */
    public List<SoccerPlayer> getAllParticipants() {
        List<SoccerPlayer> list = new ArrayList<>();
        for (SoccerPlayer sp : players.values()) {
            if (!sp.isReferee()) {
                list.add(sp);
            }
        }
        for (MatchBot bot : bots.values()) {
            list.add(bot.getProfile());
        }
        return list;
    }
}
