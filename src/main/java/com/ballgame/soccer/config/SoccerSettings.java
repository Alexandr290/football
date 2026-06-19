package com.ballgame.soccer.config;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.crowd.CrowdPackMode;
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
    public int matchDuration;
    public int halves;
    public int halftimeBreak;
    public boolean goldenGoal;
    public int goldenGoalDuration;
    public boolean penaltyShootout;
    public boolean allowRejoin;
    public int mercyRuleLead;
    public boolean throwInEnabled;

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

    public SoccerSettings(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();

        actionBarHud = c.getBoolean("settings.action-bar-hud", true);
        autoSaveInterval = c.getInt("settings.auto-save-interval", 300);

        minPlayersPerTeam = c.getInt("game.min-players-per-team", 1);
        maxPlayersPerTeam = c.getInt("game.max-players-per-team", 5);
        lobbyCountdown = c.getInt("game.lobby-countdown", 15);
        kickoffCountdown = c.getInt("game.kickoff-countdown", 3);
        matchDuration = c.getInt("game.match-duration", 300);
        halves = Math.max(1, c.getInt("game.halves", 2));
        halftimeBreak = c.getInt("game.halftime-break", 10);
        goldenGoal = c.getBoolean("game.golden-goal", true);
        goldenGoalDuration = c.getInt("game.golden-goal-duration", 120);
        penaltyShootout = c.getBoolean("game.penalty-shootout", true);
        allowRejoin = c.getBoolean("game.allow-rejoin", true);
        mercyRuleLead = c.getInt("game.mercy-rule-lead", 0);
        throwInEnabled = c.getBoolean("game.throw-in-enabled", true);

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
        crowdAmbienceInterval = c.getInt("crowd.ambience-interval-ticks", 14);
        crowdDangerRadius = c.getDouble("crowd.danger-radius", 18.0);
        crowdDangerMinSpeed = c.getDouble("crowd.danger-min-speed", 0.75);
        crowdGoalCelebration = c.getBoolean("crowd.goal-celebration", true);
        crowdReactSaves = c.getBoolean("crowd.react-to-saves", true);
        crowdReactShots = c.getBoolean("crowd.react-to-shots", true);
        crowdRealSounds = c.getBoolean("crowd.real-sounds", true);
        crowdPackMode = CrowdPackMode.parse(c.getString("crowd.resource-pack.mode", "manual"));
        crowdPackAutoServe = c.getBoolean("crowd.resource-pack.auto-serve", false);
        crowdPackPort = c.getInt("crowd.resource-pack.port", 8123);
        crowdPackPublicHost = c.getString("crowd.resource-pack.public-host", "");
        crowdPackUrl = c.getString("crowd.resource-pack.url", "");
        crowdPackUseHash = c.getBoolean("crowd.resource-pack.use-hash", true);

        championshipsEnabled = c.getBoolean("championships.enabled", true);
        champMinTeamsToStart = c.getInt("championships.min-teams-to-start", 2);
        champMaxTeams = c.getInt("championships.max-teams", 16);
        champPointsWin = c.getInt("championships.points-win", 3);
        champPointsDraw = c.getInt("championships.points-draw", 1);
        champPointsLoss = c.getInt("championships.points-loss", 0);

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
}
