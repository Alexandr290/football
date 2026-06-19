package com.ballgame.soccer.replay;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TV-style cinematic playback: mannequin/armor-stand actors replay on the pitch
 * while real players watch from broadcast cameras.
 */
public final class GoalReplay {

    private final SoccerPlugin plugin;
    private final SoccerSettings settings;
    private final Game game;
    private final Arena arena;
    private final List<ReplayFrame> frames;
    private final TeamSide scoringTeam;
    private final UUID scorerId;

    private final Map<UUID, ViewerState> savedStates = new HashMap<>();
    private final Map<UUID, Location> smoothedCameras = new HashMap<>();
    private final ReplayActorManager actors;

    private BossBar bossBar;
    private double framePos;
    private int introTicks;
    private boolean playing;
    private boolean finished;
    private Location lastBallLoc;

    private final ReplayCamera.Style cameraStyle;

    public GoalReplay(SoccerPlugin plugin, Game game, List<ReplayFrame> frames,
                      TeamSide scoringTeam, UUID scorerId) {
        this.plugin = plugin;
        this.settings = plugin.settings();
        this.game = game;
        this.arena = game.getArena();
        this.frames = frames;
        this.scoringTeam = scoringTeam;
        this.scorerId = scorerId;
        this.introTicks = settings.replayGoalHoldTicks;
        this.cameraStyle = parseCameraStyle(settings.replayCameraStyle);
        this.actors = new ReplayActorManager(plugin);
    }

    public void begin() {
        Ball ball = game.getBall();
        if (ball != null) {
            ball.setFrozen(true);
        }

        List<Player> viewers = collectViewers();
        hideViewers(viewers);
        actors.setViewers(viewers);

        for (Player player : viewers) {
            savedStates.put(player.getUniqueId(), ViewerState.capture(player));
            player.setGravity(false);
            player.setFlying(false);
            player.setAllowFlight(false);
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, 5, 0, false, false, false));
        }

        if (settings.replayBossBar) {
            bossBar = plugin.getServer().createBossBar(
                    Text.color(plugin.messages().raw("replay-boss-bar")),
                    BarColor.YELLOW, BarStyle.SEGMENTED_10);
            bossBar.setProgress(0);
            for (Player player : viewers) {
                bossBar.addPlayer(player);
            }
        }

        org.bukkit.World world = replayWorld();
        ReplayFrame introFrame = frames.get(frames.size() - 1);
        if (world != null) {
            actors.spawn(game, introFrame, world);
        }
        applyPitchFrame(introFrame);

        game.forEachParticipant(player -> {
            player.sendTitle(
                    Text.color(plugin.messages().raw("title-replay")),
                    Text.color(plugin.messages().raw("subtitle-replay")),
                    5, 30, 10);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.6f);
        });
    }

    public boolean tick() {
        if (finished) {
            return true;
        }
        if (!playing) {
            introTicks--;
            ReplayFrame holdFrame = frames.get(frames.size() - 1);
            applyPitchFrame(holdFrame);
            updateCameras(1.0, holdFrame);
            if (introTicks <= 0) {
                playing = true;
                framePos = 0;
                game.forEachParticipant(player -> {
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.5f);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                });
            }
            return false;
        }

        framePos += settings.replayPlaybackSpeed;
        double progress = framePos / Math.max(1, frames.size() - 1);
        if (framePos >= frames.size() - 1) {
            ReplayFrame last = frames.get(frames.size() - 1);
            applyPitchFrame(last);
            updateCameras(1.0, last);
            finishPlayback();
            return true;
        }

        int index = (int) framePos;
        double blend = framePos - index;
        ReplayFrame blended = frames.get(index).lerp(frames.get(index + 1), blend);
        applyPitchFrame(blended);
        updateCameras(progress, blended);
        updateBossBar(progress);
        maybeBallTrail(blended);
        maybeScorerHighlight(progress);
        maybeDramaticSound(progress);
        return false;
    }

    public void cancel() {
        if (!finished) {
            finishPlayback();
        }
    }

    public boolean isFinished() {
        return finished;
    }

    private org.bukkit.World replayWorld() {
        Ball ball = game.getBall();
        if (ball != null && ball.getLocation() != null && ball.getLocation().getWorld() != null) {
            return ball.getLocation().getWorld();
        }
        Location spawn = arena.getBallSpawn();
        return spawn != null ? spawn.getWorld() : null;
    }

    /** Updates ball and on-pitch replay actors only — real players stay on cameras. */
    private void applyPitchFrame(ReplayFrame frame) {
        Ball ball = game.getBall();
        org.bukkit.World world = replayWorld();
        if (ball == null || world == null) {
            return;
        }
        Location ballLoc = frame.ballLocation(world);
        ball.teleport(ballLoc);
        lastBallLoc = ballLoc.clone();
        actors.applyFrame(frame, world);
    }

    private void updateCameras(double progress, ReplayFrame frame) {
        org.bukkit.World world = replayWorld();
        if (world == null) {
            return;
        }
        Location ball = frame.ballLocation(world);
        Location prev = lastBallLoc != null ? lastBallLoc : ball;
        PlayerPose scorerPose = scorerId != null ? frame.players.get(scorerId) : null;

        for (SoccerPlayer sp : game.getParticipants()) {
            Player viewer = sp.getBukkitPlayer();
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            Location target = ReplayCamera.compute(
                    cameraStyle, progress, ball, prev, arena, scoringTeam, scorerPose);
            UUID id = viewer.getUniqueId();
            Location smoothed = smoothedCameras.get(id);
            if (smoothed == null) {
                smoothed = target.clone();
            } else {
                smoothed = lerpLocation(smoothed, target, 0.28);
            }
            smoothed.setDirection(ball.toVector().subtract(smoothed.toVector()).normalize());
            viewer.teleport(smoothed);
            smoothedCameras.put(id, smoothed.clone());
        }
    }

    private void updateBossBar(double progress) {
        if (bossBar != null) {
            bossBar.setProgress(Math.min(1.0, progress));
        }
    }

    private void maybeBallTrail(ReplayFrame frame) {
        if (!settings.replayBallTrail) {
            return;
        }
        org.bukkit.World world = replayWorld();
        if (world == null) {
            return;
        }
        Location ball = frame.ballLocation(world);
        ball.getWorld().spawnParticle(Particle.CRIT, ball, 2, 0.05, 0.05, 0.05, 0.01);
        ball.getWorld().spawnParticle(Particle.END_ROD, ball, 1, 0, 0, 0, 0);
    }

    private void maybeScorerHighlight(double progress) {
        if (!settings.replayScorerGlow || scorerId == null) {
            return;
        }
        boolean glow = progress >= 0.78;
        actors.setScorerGlow(scorerId, glow);
        if (glow) {
            PlayerPose pose = null;
            for (ReplayFrame frame : frames) {
                pose = frame.players.get(scorerId);
            }
            org.bukkit.World world = replayWorld();
            if (pose != null && world != null) {
                world.spawnParticle(
                        Particle.TOTEM_OF_UNDYING,
                        pose.toLocation(world).add(0, 1.2, 0),
                        1, 0.2, 0.4, 0.2, 0.01);
            }
        }
    }

    private boolean dramaticSoundPlayed;

    private void maybeDramaticSound(double progress) {
        if (dramaticSoundPlayed || progress < 0.88) {
            return;
        }
        dramaticSoundPlayed = true;
        game.forEachParticipant(player ->
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.35f, 1.4f));
    }

    private void finishPlayback() {
        finished = true;
        actors.removeAll();

        List<Player> viewers = collectViewers();
        for (Player player : viewers) {
            ViewerState state = savedStates.remove(player.getUniqueId());
            if (state != null) {
                state.restore(player);
            }
            game.resetParticipantMovement(player);
        }
        showViewers(viewers);

        smoothedCameras.clear();
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        game.scheduleMovementReset();
    }

    private List<Player> collectViewers() {
        List<Player> viewers = new ArrayList<>();
        game.forEachParticipant(viewers::add);
        return viewers;
    }

    private void hideViewers(List<Player> viewers) {
        for (Player viewer : viewers) {
            viewer.setInvisible(true);
            viewer.setCollidable(false);
            for (Player other : viewers) {
                if (!viewer.equals(other)) {
                    viewer.hidePlayer(plugin, other);
                }
            }
        }
    }

    private void showViewers(List<Player> viewers) {
        for (Player viewer : viewers) {
            viewer.setInvisible(false);
            viewer.setCollidable(true);
            for (Player other : viewers) {
                if (!viewer.equals(other)) {
                    viewer.showPlayer(plugin, other);
                }
            }
        }
    }

    private static Location lerpLocation(Location a, Location b, double t) {
        return new Location(a.getWorld(),
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t,
                a.getYaw(), a.getPitch());
    }

    private static ReplayCamera.Style parseCameraStyle(String value) {
        if (value == null) {
            return ReplayCamera.Style.CINEMATIC;
        }
        return switch (value.toLowerCase()) {
            case "chase" -> ReplayCamera.Style.CHASE;
            case "broadcast" -> ReplayCamera.Style.BROADCAST;
            default -> ReplayCamera.Style.CINEMATIC;
        };
    }

    private static final class ViewerState {
        private final Location location;
        private final GameMode gameMode;
        private final boolean allowFlight;
        private final boolean flying;
        private final boolean gravity;
        private final boolean invisible;
        private final boolean collidable;

        private ViewerState(Location location, GameMode gameMode,
                            boolean allowFlight, boolean flying, boolean gravity,
                            boolean invisible, boolean collidable) {
            this.location = location;
            this.gameMode = gameMode;
            this.allowFlight = allowFlight;
            this.flying = flying;
            this.gravity = gravity;
            this.invisible = invisible;
            this.collidable = collidable;
        }

        static ViewerState capture(Player player) {
            return new ViewerState(
                    player.getLocation().clone(),
                    player.getGameMode(),
                    player.getAllowFlight(),
                    player.isFlying(),
                    player.hasGravity(),
                    player.isInvisible(),
                    player.isCollidable());
        }

        void restore(Player player) {
            player.teleport(location);
            player.setGameMode(gameMode);
            player.setGravity(gravity);
            player.setAllowFlight(allowFlight);
            player.setFlying(flying && allowFlight);
            player.setInvisible(invisible);
            player.setCollidable(collidable);
            player.setFallDistance(0f);
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }
}
