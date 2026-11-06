package com.ballgame.soccer.config;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.crowd.CrowdPackMode;
import com.ballgame.soccer.network.NetworkRole;
import com.ballgame.soccer.stats.LeaderboardCategory;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Strongly-typed snapshot of every tunable value in config.yml. Loaded once on
 * enable and on {@code /soccer reload} so hot paths (the per-tick physics loop)
 * never touch the slower configuration API.
 */
public final class SoccerSettings {

    private final SoccerPlugin plugin;

    // settings
    public boolean actionBarHud;
    public int autoSaveInterval;

    // game
    public int minPlayersPerTeam;
    public int maxPlayersPerTeam;
    public int lobbyCountdown;
    public int kickoffCountdown;
    /** Seconds to wait for humans before auto-filling empty squad slots with bots. */
    public int lobbyBotFillGrace;
    public int matchDuration;
    public int halves;
    public int halftimeBreak;
    public boolean goldenGoal;
    public int goldenGoalDuration;
    public boolean penaltyShootout;
    public boolean allowRejoin;
    public boolean quickjoinTeamPick;
    public boolean stoppageTimeEnabled;
    public int stoppageTimeMaxSeconds;
    public boolean botsEnabled;
    public int botMinSoloCount;
    public int botMaxSoloCount;
    public int botDefaultSoloCount;
    public int botDefaultTeammateCount;
    public double botWalkSpeed;
    public double botSprintSpeed;
    public boolean botStaminaEnabled;
    public double botMaxStamina;
    public double botStaminaRegen;
    public double botSprintDrain;
    public double botStaminaRecoverThreshold;
    public java.util.List<String> botNames;
    public String botVisualType;
    /** When true, Citizens NPCs pathfind; when false, plugin physics moves them (recommended). */
    public boolean botCitizensPathfind;
    public java.util.List<String> botSkinNames;
    public boolean botReactionDelay;
    public boolean botFirstTouch;
    public boolean botTackleDuels;
    public boolean botKeeperHoldsBall;
    public boolean botCelebrations;
    public boolean kickoffRules;
    public boolean botKickErrors;
    public boolean botCloseControl;
    public boolean botOffsideAware;
    public boolean botMarking;
    public int mercyRuleLead;
    public boolean throwInEnabled;
    public boolean throwInAutoTaker;
    public boolean throwInTakerOnlyKick;
    public boolean throwInTakerGlow;
    public boolean throwInTakerParticles;
    public boolean throwInTakerSnapBack;
    public double throwInTakerWanderBlocks;
    public boolean offsideEnabled;
    public boolean offsideAutoTaker;
    public boolean offsideTakerOnlyKick;
    public boolean offsideTakerGlow;
    public boolean offsideTakerParticles;
    public boolean offsideTakerSnapBack;
    public double offsideTakerWanderBlocks;
    public boolean offsideRestrainOpponents;
    public double offsideOpponentRestrainRadius;
    public double offsideOpponentMinGap;
    public boolean broadcastHud;

    // leaderboard
    public int leaderboardBroadcastInterval;
    public int leaderboardEntries;
    public int leaderboardOverviewEntries;
    public java.util.List<LeaderboardCategory> leaderboardBroadcastCategories;

    // network (BungeeCord)
    public boolean networkEnabled;
    public NetworkRole networkRole;
    public String networkGameServer;
    public String networkLobbyServer;
    public int networkPendingJoinTimeoutSeconds;
    public boolean networkReturnToLobbyOnLeave;
    public boolean networkReturnToLobbyOnMatchEnd;

    // pre-match show
    public boolean introEnabled;
    public double introDurationScale;
    public boolean introSkipBySneak;
    public double introSkipVoteRatio;
    public boolean introIncludeSpectators;
    public boolean introLineupChat;
    public int introCameraSmoothing;

    // referee
    public boolean refereeEnabled;
    public String refereeName;
    public java.util.List<String> refereeAssistantNames;
    public double refereeFollowDistance;
    public double refereeTrailOffset;
    public double refereeWalkSpeed;
    public double refereeSprintSpeed;
    public double refereeSprintThreshold;
    public boolean refereeWhistleEnabled;
    public boolean autoRefereeEnabled;
    public int autoRefereeKeeperHoldSeconds;
    public int autoRefereePlayerHoldSeconds;
    public int autoRefereeTimeWasteSeconds;
    public int autoRefereeHoldContestSeconds;
    public int autoRefereeDissentWindowTicks;
    public String refereeEntityType;
    public String refereeSkinTexture;

    // ball
    public String ballDisplay;
    public String ballHeadTexture;
    public double gravity;
    public double airResistance;
    public double dragCoefficient;
    public double groundFriction;
    public double rollingSpinTransfer;
    public double restitution;
    public double bounceFriction;
    public double bounceThreshold;
    public double restThreshold;
    public double magnusStrength;
    public double spinDecay;
    public double maxSpeed;
    public double collisionRadius;
    public double mass;
    public boolean resetWhenOut;

    // mechanics
    public double kickReach;
    public double lightTouchPower;
    public double maxShotPower;
    public double chargeTime;
    public double shotLift;
    public double passPowerMultiplier;
    public double loftPassHorizontalPower;
    public double sprintBonus;
    public double curveStrength;
    public boolean tackleEnabled;
    public double tacklePower;
    public double tackleCooldown;
    public boolean staminaEnabled;
    public double maxStamina;
    public double staminaRegen;
    public double sprintDrain;
    public double chargeDrain;
    public double tackleDrain;
    public double keeperCatchBonus;

    // scoreboard
    public boolean scoreboardEnabled;
    public String scoreboardTitle;
    public List<String> scoreboardLines;

    // teams
    public String redName;
    public String redColor;
    public String redChatColor;
    public String blueName;
    public String blueColor;
    public String blueChatColor;

    // goal replay
    public boolean replayEnabled;
    public int replayBufferSeconds;
    public int replayClipSeconds;
    public double replayPlaybackSpeed;
    public int replayGoalHoldTicks;
    public boolean replayBallTrail;
    public boolean replayScorerGlow;
    public boolean replayBossBar;
    public String replayCameraStyle;
    public String replayActorType;

    // championships
    public boolean championshipsEnabled;
    public int champMinTeamsToStart;
    public int champMaxTeams;
    public int champPointsWin;
    public int champPointsDraw;
    public int champPointsLoss;
    public boolean champPlayoffsEnabled;
    public int champPlayoffQualifiers;
    public int champPlayoffMinTeams;
    public int champGroupCount;
    public int champTeamsPerGroup;
    public int champAdvancePerGroup;
    public boolean worldCupR16;
    public int worldCupBracketSize;

    public int worldCupTeamSlots() {
        if (worldCupR16) {
            return Math.max(2, worldCupBracketSize);
        }
        return champGroupCount * champTeamsPerGroup;
    }

    // crowd atmosphere
    public boolean crowdEnabled;
    public double crowdVolume;
    public int crowdAmbienceInterval;
    public double crowdDangerRadius;
    public double crowdDangerMinSpeed;
    public boolean crowdGoalCelebration;
    public boolean crowdReactSaves;
    public boolean crowdReactShots;
    public boolean crowdRealSounds;
    public CrowdPackMode crowdPackMode;
    public boolean crowdPackAutoServe;
    public int crowdPackPort;
    public String crowdPackPublicHost;
    public String crowdPackUrl;
    public boolean crowdPackUseHash;
    public boolean crowdPackPromptOnJoin;

    public SoccerSettings(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();

        actionBarHud = c.getBoolean("settings.action-bar-hud", true);
        autoSaveInterval = c.getInt("settings.auto-save-interval", 300);

        minPlayersPerTeam = c.getInt("game.min-players-per-team", 1);
        maxPlayersPerTeam = c.getInt("game.max-players-per-team", 11);
        lobbyCountdown = c.getInt("game.lobby-countdown", 15);
        kickoffCountdown = c.getInt("game.kickoff-countdown", 3);
        lobbyBotFillGrace = Math.max(0, c.getInt("game.lobby-bot-fill-grace", 15));
        matchDuration = c.getInt("game.match-duration", 300);
        halves = Math.max(1, c.getInt("game.halves", 2));
        halftimeBreak = c.getInt("game.halftime-break", 30);
        goldenGoal = c.getBoolean("game.golden-goal", true);
        goldenGoalDuration = c.getInt("game.golden-goal-duration", 120);
        penaltyShootout = c.getBoolean("game.penalty-shootout", true);
        allowRejoin = c.getBoolean("game.allow-rejoin", true);
        quickjoinTeamPick = c.getBoolean("game.quickjoin-team-pick", true);
        stoppageTimeEnabled = c.getBoolean("game.stoppage-time-enabled", true);
        stoppageTimeMaxSeconds = Math.max(0, c.getInt("game.stoppage-time-max-seconds", 180));
        botsEnabled = c.getBoolean("bots.enabled", true);
        botMinSoloCount = Math.max(1, c.getInt("bots.min-solo-count", 1));
        botMaxSoloCount = Math.max(botMinSoloCount, c.getInt("bots.max-solo-count", 11));
        botDefaultSoloCount = Math.max(botMinSoloCount,
                Math.min(botMaxSoloCount, c.getInt("bots.default-solo-count", 4)));
        botDefaultTeammateCount = Math.max(0,
                Math.min(botMaxSoloCount, c.getInt("bots.default-teammate-count", 0)));
        botWalkSpeed = c.getDouble("bots.walk-speed", 0.216);
        botSprintSpeed = c.getDouble("bots.sprint-speed", 0.28);
        botStaminaEnabled = c.getBoolean("bots.stamina-enabled", true);
        botMaxStamina = c.getDouble("bots.max-stamina", 100.0);
        botStaminaRegen = c.getDouble("bots.stamina-regen", 0.65);
        botSprintDrain = c.getDouble("bots.sprint-drain", 0.5);
        botStaminaRecoverThreshold = c.getDouble("bots.stamina-recover-threshold", 35.0);
        botNames = c.getStringList("bots.names");
        if (botNames.isEmpty()) {
            botNames = java.util.List.of(
                    "Striker", "Midfielder", "Winger", "Playmaker", "Defender");
        }
        botVisualType = c.getString("bots.visual", "auto");
        botCitizensPathfind = c.getBoolean("bots.citizens-pathfind", false);
        botSkinNames = c.getStringList("bots.skin-names");
        if (botSkinNames.isEmpty()) {
            botSkinNames = java.util.List.of(
                    "Notch", "jeb_", "Dinnerbone", "Grumm", "MHF_Steve");
        }
        botReactionDelay = c.getBoolean("bots.realism.reaction-delay", true);
        botFirstTouch = c.getBoolean("bots.realism.first-touch", true);
        botTackleDuels = c.getBoolean("bots.realism.tackle-duels", true);
        botKeeperHoldsBall = c.getBoolean("bots.realism.keeper-holds-ball", true);
        botCelebrations = c.getBoolean("bots.realism.celebrations", true);
        kickoffRules = c.getBoolean("bots.realism.kickoff-rules", true);
        botKickErrors = c.getBoolean("bots.realism.kick-errors", true);
        botCloseControl = c.getBoolean("bots.realism.close-control", true);
        botOffsideAware = c.getBoolean("bots.realism.offside-aware", true);
        botMarking = c.getBoolean("bots.realism.marking", true);
        introEnabled = c.getBoolean("intro.enabled", true);
        introDurationScale = Math.max(0.3, Math.min(3.0, c.getDouble("intro.duration-scale", 1.0)));
        introSkipBySneak = c.getBoolean("intro.skip-by-sneak", true);
        introSkipVoteRatio = Math.max(0.0, Math.min(1.0, c.getDouble("intro.skip-vote-ratio", 0.5)));
        introIncludeSpectators = c.getBoolean("intro.include-spectators", true);
        introLineupChat = c.getBoolean("intro.lineup-chat", true);
        introCameraSmoothing = Math.max(1, Math.min(6, c.getInt("intro.camera-smoothing-ticks", 3)));
        mercyRuleLead = c.getInt("game.mercy-rule-lead", 0);
        throwInEnabled = c.getBoolean("game.throw-in-enabled", true);
        throwInAutoTaker = c.getBoolean("game.throw-in-auto-taker", true);
        throwInTakerOnlyKick = c.getBoolean("game.throw-in-taker-only-kick", true);
        throwInTakerGlow = c.getBoolean("game.throw-in-taker-glow", true);
        throwInTakerParticles = c.getBoolean("game.throw-in-taker-particles", true);
        throwInTakerSnapBack = c.getBoolean("game.throw-in-taker-snap-back", true);
        throwInTakerWanderBlocks = c.getDouble("game.throw-in-taker-wander-blocks", 2.0);
        offsideEnabled = c.getBoolean("game.offside-enabled", true);
        offsideAutoTaker = c.getBoolean("game.offside-auto-taker", true);
        offsideTakerOnlyKick = c.getBoolean("game.offside-taker-only-kick", true);
        offsideTakerGlow = c.getBoolean("game.offside-taker-glow", true);
        offsideTakerParticles = c.getBoolean("game.offside-taker-particles", true);
        offsideTakerSnapBack = c.getBoolean("game.offside-taker-snap-back", true);
        offsideTakerWanderBlocks = c.getDouble("game.offside-taker-wander-blocks", 2.0);
        offsideRestrainOpponents = c.getBoolean("game.offside-restrain-opponents", true);
        offsideOpponentRestrainRadius = c.getDouble("game.offside-opponent-restrain-radius", 8.0);
        offsideOpponentMinGap = c.getDouble("game.offside-opponent-min-gap", 3.0);
        broadcastHud = c.getBoolean("game.broadcast-hud", true);

        leaderboardBroadcastInterval = c.getInt("leaderboard.broadcast-interval", 1800);
        leaderboardEntries = Math.max(1, c.getInt("leaderboard.entries", 10));
        leaderboardOverviewEntries = Math.max(1, c.getInt("leaderboard.overview-entries", 3));
        leaderboardBroadcastCategories = parseLeaderboardCategories(
                c.getStringList("leaderboard.broadcast-categories"));

        networkEnabled = c.getBoolean("network.enabled", false);
        networkRole = NetworkRole.fromConfig(c.getString("network.role", "standalone"));
        networkGameServer = c.getString("network.game-server", "soccer");
        networkLobbyServer = c.getString("network.lobby-server", "lobby");
        networkPendingJoinTimeoutSeconds = Math.max(10, c.getInt("network.pending-join-timeout-seconds", 45));
        networkReturnToLobbyOnLeave = c.getBoolean("network.return-to-lobby-on-leave", true);
        networkReturnToLobbyOnMatchEnd = c.getBoolean("network.return-to-lobby-on-match-end", true);

        refereeEnabled = c.getBoolean("referee.enabled", true);
        refereeName = c.getString("referee.name", "Referee");
        refereeAssistantNames = c.getStringList("referee.assistant-names");
        if (refereeAssistantNames.isEmpty()) {
            refereeAssistantNames = java.util.List.of("AR 1", "AR 2");
        }
        refereeFollowDistance = c.getDouble("referee.follow-distance", 5.5);
        refereeTrailOffset = c.getDouble("referee.trail-offset", 0.045);
        refereeWalkSpeed = c.getDouble("referee.walk-speed", 0.16);
        refereeSprintSpeed = c.getDouble("referee.sprint-speed", 0.30);
        refereeSprintThreshold = c.getDouble("referee.sprint-threshold", 4.0);
        refereeWhistleEnabled = c.getBoolean("referee.whistle-enabled", true);
        autoRefereeEnabled = c.getBoolean("referee.auto-referee-enabled", false);
        autoRefereeKeeperHoldSeconds = Math.max(1, c.getInt("referee.auto-referee-keeper-hold-seconds", 6));
        autoRefereePlayerHoldSeconds = Math.max(1, c.getInt("referee.auto-referee-player-hold-seconds", 5));
        autoRefereeTimeWasteSeconds = Math.max(3, c.getInt("referee.auto-referee-time-waste-seconds", 12));
        autoRefereeHoldContestSeconds = Math.max(2, c.getInt("referee.auto-referee-hold-contest-seconds", 4));
        autoRefereeDissentWindowTicks = Math.max(20, c.getInt("referee.auto-referee-dissent-window-ticks", 80));
        refereeEntityType = c.getString("referee.entity-type", "auto");
        refereeSkinTexture = c.getString("referee.skin-texture", "");

        replayEnabled = c.getBoolean("replay.enabled", true);
        replayBufferSeconds = c.getInt("replay.buffer-seconds", 15);
        replayClipSeconds = c.getInt("replay.clip-seconds", 10);
        replayPlaybackSpeed = c.getDouble("replay.playback-speed", 0.55);
        replayGoalHoldTicks = c.getInt("replay.goal-hold-ticks", 25);
        replayBallTrail = c.getBoolean("replay.ball-trail", true);
        replayScorerGlow = c.getBoolean("replay.scorer-glow", true);
        replayBossBar = c.getBoolean("replay.boss-bar", true);
        replayCameraStyle = c.getString("replay.camera-style", "cinematic");
        replayActorType = c.getString("replay.actor-type", "auto");

        crowdEnabled = c.getBoolean("crowd.enabled", true);
        crowdVolume = c.getDouble("crowd.volume", 0.85);
        crowdAmbienceInterval = c.getInt("crowd.ambience-interval-ticks", 70);
        crowdDangerRadius = c.getDouble("crowd.danger-radius", 18.0);
        crowdDangerMinSpeed = c.getDouble("crowd.danger-min-speed", 0.75);
        crowdGoalCelebration = c.getBoolean("crowd.goal-celebration", true);
        crowdReactSaves = c.getBoolean("crowd.react-to-saves", true);
        crowdReactShots = c.getBoolean("crowd.react-to-shots", true);
        crowdRealSounds = c.getBoolean("crowd.real-sounds", true);
        crowdPackMode = CrowdPackMode.parse(c.getString("crowd.resource-pack.mode", "download"));
        crowdPackAutoServe = c.getBoolean("crowd.resource-pack.auto-serve", true);
        crowdPackPort = c.getInt("crowd.resource-pack.port", 8123);
        crowdPackPublicHost = c.getString("crowd.resource-pack.public-host", "");
        crowdPackUrl = c.getString("crowd.resource-pack.url", "");
        crowdPackUseHash = c.getBoolean("crowd.resource-pack.use-hash", true);
        crowdPackPromptOnJoin = c.getBoolean("crowd.resource-pack.prompt-on-join", true);

        championshipsEnabled = c.getBoolean("championships.enabled", true);
        champMinTeamsToStart = c.getInt("championships.min-teams-to-start", 2);
        champMaxTeams = c.getInt("championships.max-teams", 16);
        champPointsWin = c.getInt("championships.points-win", 3);
        champPointsDraw = c.getInt("championships.points-draw", 1);
        champPointsLoss = c.getInt("championships.points-loss", 0);
        champPlayoffsEnabled = c.getBoolean("championships.playoffs-enabled", true);
        champPlayoffQualifiers = c.getInt("championships.playoff-qualifiers", 8);
        champPlayoffMinTeams = c.getInt("championships.playoff-min-teams", 4);
        champGroupCount = c.getInt("championships.group-count", 8);
        champTeamsPerGroup = c.getInt("championships.teams-per-group", 2);
        champAdvancePerGroup = c.getInt("championships.advance-per-group", 1);
        worldCupR16 = c.getBoolean("championships.world-cup-r16", true);
        worldCupBracketSize = c.getInt("championships.world-cup-bracket-size", 16);
        if (worldCupR16) {
            champMaxTeams = Math.max(champMaxTeams, worldCupBracketSize);
        }

        ballDisplay = c.getString("ball.display", "HEAD").toUpperCase();
        ballHeadTexture = c.getString("ball.head-texture", "");
        gravity = c.getDouble("ball.gravity", 0.048);
        airResistance = c.getDouble("ball.air-resistance", 0.992);
        dragCoefficient = c.getDouble("ball.drag-coefficient", 0.006);
        groundFriction = c.getDouble("ball.ground-friction", 0.93);
        rollingSpinTransfer = c.getDouble("ball.rolling-spin-transfer", 0.12);
        restitution = c.getDouble("ball.restitution", 0.62);
        bounceFriction = c.getDouble("ball.bounce-friction", 0.78);
        bounceThreshold = c.getDouble("ball.bounce-threshold", 0.12);
        restThreshold = c.getDouble("ball.rest-threshold", 0.035);
        magnusStrength = c.getDouble("ball.magnus-strength", 0.022);
        spinDecay = c.getDouble("ball.spin-decay", 0.97);
        maxSpeed = c.getDouble("ball.max-speed", 4.5);
        collisionRadius = c.getDouble("ball.collision-radius", 0.65);
        mass = c.getDouble("ball.mass", 1.0);
        resetWhenOut = c.getBoolean("ball.reset-when-out", true);

        kickReach = c.getDouble("mechanics.kick-reach", 3.0);
        lightTouchPower = c.getDouble("mechanics.light-touch-power", 0.45);
        maxShotPower = c.getDouble("mechanics.max-shot-power", 2.8);
        chargeTime = c.getDouble("mechanics.charge-time", 1.2);
        shotLift = c.getDouble("mechanics.shot-lift", 0.32);
        passPowerMultiplier = c.getDouble("mechanics.pass-power-multiplier", 0.6);
        loftPassHorizontalPower = c.getDouble("mechanics.loft-pass-horizontal-power", 0.30);
        sprintBonus = c.getDouble("mechanics.sprint-bonus", 0.35);
        curveStrength = c.getDouble("mechanics.curve-strength", 0.9);
        tackleEnabled = c.getBoolean("mechanics.tackle-enabled", true);
        tacklePower = c.getDouble("mechanics.tackle-power", 1.1);
        tackleCooldown = c.getDouble("mechanics.tackle-cooldown", 4.0);
        staminaEnabled = c.getBoolean("mechanics.stamina-enabled", true);
        maxStamina = c.getDouble("mechanics.max-stamina", 100.0);
        staminaRegen = c.getDouble("mechanics.stamina-regen", 1.2);
        sprintDrain = c.getDouble("mechanics.sprint-drain", 0.6);
        chargeDrain = c.getDouble("mechanics.charge-drain", 0.8);
        tackleDrain = c.getDouble("mechanics.tackle-drain", 18.0);
        keeperCatchBonus = c.getDouble("mechanics.keeper-catch-bonus", 1.5);

        scoreboardEnabled = c.getBoolean("scoreboard.enabled", true);
        Messages msg = plugin.messages();
        if (c.contains("scoreboard.title")) {
            scoreboardTitle = c.getString("scoreboard.title", msg.scoreboardTitle());
        } else {
            scoreboardTitle = msg.scoreboardTitle();
        }
        scoreboardLines = c.getStringList("scoreboard.lines");
        if (scoreboardLines.isEmpty()) {
            scoreboardLines = msg.scoreboardLines();
        }

        redName = c.getString("teams.red.name", "Red");
        redColor = c.getString("teams.red.color", "RED");
        redChatColor = c.getString("teams.red.chat-color", "&c");
        blueName = c.getString("teams.blue.name", "Blue");
        blueColor = c.getString("teams.blue.color", "BLUE");
        blueChatColor = c.getString("teams.blue.chat-color", "&9");
    }

    private static java.util.List<LeaderboardCategory> parseLeaderboardCategories(
            java.util.List<String> raw) {
        java.util.List<LeaderboardCategory> categories = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            categories.add(LeaderboardCategory.GOALS);
            categories.add(LeaderboardCategory.WINS);
            categories.add(LeaderboardCategory.ASSISTS);
            return categories;
        }
        for (String entry : raw) {
            LeaderboardCategory category = LeaderboardCategory.parse(entry);
            if (!categories.contains(category)) {
                categories.add(category);
            }
        }
        if (categories.isEmpty()) {
            categories.add(LeaderboardCategory.GOALS);
        }
        return categories;
    }
}
