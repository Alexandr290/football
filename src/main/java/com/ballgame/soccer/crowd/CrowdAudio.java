package com.ballgame.soccer.crowd;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.GameState;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dynamic stadium crowd audio. Uses vanilla NPC voice sounds when the crowd
 * resource pack is disabled; optional real recordings when enabled.
 */
public final class CrowdAudio {

    private static final double INTENSITY_SMOOTHING = 0.88;

    private final SoccerPlugin plugin;
    private final Game game;
    private final Arena arena;
    private final SoccerSettings settings;

    private double intensity;
    private int ambienceTick;
    private int shoutTick;
    private int dangerCooldown;
    private int saveCooldown;
    private int celebrationTicks;
    private TeamSide celebratingTeam;

    private List<Location> standPoints;
    private double lastAttackFactor;

    public CrowdAudio(SoccerPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.arena = game.getArena();
        this.settings = plugin.settings();
    }

    public void tick() {
        if (!settings.crowdEnabled) {
            return;
        }
        if (celebrationTicks > 0) {
            tickGoalCelebration();
            return;
        }

        double target = computeTargetIntensity();
        intensity = intensity * INTENSITY_SMOOTHING + target * (1.0 - INTENSITY_SMOOTHING);
        lastAttackFactor = currentAttackFactor();

        shoutTick++;
        int shoutInterval = VanillaNpcCrowd.shoutIntervalTicks(lastAttackFactor);
        if (shoutTick >= shoutInterval) {
            shoutTick = 0;
            playNpcShoutLayer(lastAttackFactor);
        }

        ambienceTick++;
        if (!useVanillaNpcCrowd()) {
            int interval = ambienceInterval();
            if (ambienceTick >= interval) {
                ambienceTick = 0;
                playAmbienceLayer();
            }
        }

        if (game.getState().isLive() && !game.isCelebrating()) {
            checkDangerousAttack();
        }
    }

    public void onMatchAnticipation() {
        if (!settings.crowdEnabled) {
            return;
        }
        playNpcShout(VanillaNpcCrowd.VOL_MIDFIELD * 0.85f);
    }

    public void onKickoff() {
        if (!settings.crowdEnabled) {
            return;
        }
        intensity = Math.max(intensity, 0.45);
        playNpcShout(VanillaNpcCrowd.VOL_ATTACK * 0.75f);
        playCrowd(CrowdSound.KICKOFF, 0.55f, 1.0f);
    }

    public void onGoal(TeamSide scoringTeam, boolean ownGoal) {
        if (!settings.crowdEnabled || !settings.crowdGoalCelebration) {
            return;
        }
        celebratingTeam = scoringTeam;
        celebrationTicks = 20 * 5;
        intensity = 1.0;

        float homeBias = ownGoal ? 0.65f : 1.0f;
        playGoalNpcBurst(homeBias);
        playCrowd(CrowdSound.GOAL_CHEER, VanillaNpcCrowd.VOL_GOAL * homeBias, teamPitch(scoringTeam));
    }

    public void onSave(TeamSide defendingTeam) {
        if (!settings.crowdEnabled || !settings.crowdReactSaves) {
            return;
        }
        if (saveCooldown > 0) {
            return;
        }
        saveCooldown = 30;
        intensity = Math.min(1.0, intensity + 0.35);

        playNpcShout(VanillaNpcCrowd.VOL_ATTACK);
        playCrowd(CrowdSound.APPLAUSE, 0.5f, 0.95f);
    }

    public void onNearMiss() {
        if (!settings.crowdEnabled || !settings.crowdReactShots) {
            return;
        }
        if (dangerCooldown > 0) {
            return;
        }
        dangerCooldown = 25;
        intensity = Math.min(1.0, intensity + 0.4);

        playNpcShout(VanillaNpcCrowd.VOL_DANGER);
        playCrowd(CrowdSound.GASP, 0.55f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.15f);
    }

    public void onFullTime() {
        if (!settings.crowdEnabled) {
            return;
        }
        playNpcShout(VanillaNpcCrowd.VOL_MIDFIELD);
        playCrowd(CrowdSound.APPLAUSE, 0.6f, 1.0f);
    }

    private void tickGoalCelebration() {
        celebrationTicks--;
        if (celebrationTicks % 10 == 0) {
            playGoalNpcBurst(1.0f);
            playCrowd(CrowdSound.GOAL_CHEER, 0.65f, teamPitch(celebratingTeam));
        }
        if (celebrationTicks <= 0) {
            celebratingTeam = null;
            intensity = 0.5;
        }
    }

    private void playNpcShoutLayer(double attackFactor) {
        if (!game.getState().isLive() && game.getState() != GameState.THROW_IN) {
            return;
        }
        float volume = VanillaNpcCrowd.volumeForAttackFactor(attackFactor);
        playNpcShout(volume);

        if (attackFactor > 0.72 && ThreadLocalRandom.current().nextInt(3) == 0) {
            playNpcShout(volume * 0.85f);
        }
    }

    private void playNpcShout(float volume) {
        List<Location> stands = stands();
        game.forEachParticipant(player -> {
            if (!player.isOnline()) {
                return;
            }
            VanillaNpcCrowd.playShout(player, stands, volume, settings.crowdVolume);
        });
    }

    private void playGoalNpcBurst(float bias) {
        List<Location> stands = stands();
        game.forEachParticipant(player -> {
            if (!player.isOnline()) {
                return;
            }
            VanillaNpcCrowd.playGoalBurst(player, stands, settings.crowdVolume * bias);
        });
    }

    private double currentAttackFactor() {
        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return 0.0;
        }
        return VanillaNpcCrowd.attackFactor(arena, ball.getLocation());
    }

    private double computeTargetIntensity() {
        GameState state = game.getState();
        if (state == GameState.REPLAY) {
            return 0.75;
        }
        if (game.isCelebrating()) {
            return 0.95;
        }
        if (state == GameState.KICKOFF || state == GameState.COUNTDOWN) {
            return 0.35;
        }
        if (state == GameState.HALFTIME) {
            return 0.2;
        }
        if (!state.isLive()) {
            return 0.05;
        }

        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return 0.15;
        }

        double attack = lastAttackFactor;
        double speed = ball.getSpeed();
        double speedFactor = Math.min(1.0, speed / settings.maxSpeed) * 0.18;
        return Math.min(1.0, 0.15 + attack * 0.65 + speedFactor);
    }

    private void checkDangerousAttack() {
        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return;
        }
        if (dangerCooldown > 0) {
            dangerCooldown--;
            return;
        }

        double attack = VanillaNpcCrowd.attackFactor(arena, ball.getLocation());
        if (attack < 0.62 || ball.getSpeed() < settings.crowdDangerMinSpeed) {
            return;
        }

        dangerCooldown = 28;
        intensity = Math.min(1.0, intensity + 0.25);
        float vol = VanillaNpcCrowd.volumeForAttackFactor(attack);
        playNpcShout(vol);
        playCrowd(CrowdSound.GASP, vol * 0.55f, 1.05f);

        if (attack > 0.85 && ball.getSpeed() > settings.crowdDangerMinSpeed * 1.3) {
            onNearMiss();
        }
    }

    private void playAmbienceLayer() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float vol = (float) (settings.crowdVolume * (0.4 + intensity * 0.6));

        if (intensity < 0.25) {
            if (rng.nextInt(3) == 0) {
                playCrowd(CrowdSound.AMBIENCE_LOW, vol * 0.55f, randomPitch(0.92f, 1.08f));
            }
            return;
        }

        if (intensity < 0.55) {
            playCrowd(CrowdSound.AMBIENCE_MEDIUM, vol * 0.5f, randomPitch(0.95f, 1.05f));
            return;
        }

        if (intensity < 0.8) {
            playCrowd(CrowdSound.AMBIENCE_HIGH, vol * 0.45f, randomPitch(0.95f, 1.08f));
            return;
        }

        playCrowd(CrowdSound.AMBIENCE_HIGH, vol * 0.65f, randomPitch(1.0f, 1.12f));
    }

    private void playCrowd(CrowdSound sound, float volume, float pitch) {
        if (useVanillaNpcCrowd() && sound != CrowdSound.GOAL_CHEER && sound != CrowdSound.GASP) {
            return;
        }
        float scaled = volume * (float) settings.crowdVolume;
        List<Location> stands = stands();
        game.forEachParticipant(player -> {
            if (!player.isOnline()) {
                return;
            }
            playFor(player, stands, sound, scaled, pitch);
        });
    }

    private void playFor(Player player, List<Location> stands, CrowdSound sound, float volume, float pitch) {
        Location at = pickNearestStand(stands, player.getLocation());
        if (plugin.crowdPack().canPlay(player)) {
            player.playSound(at, sound.key(), SoundCategory.VOICE, volume, pitch);
            return;
        }
        Sound fallback = sound.fallback();
        if (fallback != null) {
            player.playSound(at, fallback, SoundCategory.VOICE, volume, pitch);
        }
    }

    private boolean useVanillaNpcCrowd() {
        return !plugin.crowdPack().useRealSounds();
    }

    private List<Location> stands() {
        if (standPoints == null) {
            standPoints = VanillaNpcCrowd.buildStandPoints(arena);
        }
        return standPoints;
    }

    private Location pickNearestStand(List<Location> stands, Location near) {
        if (stands.isEmpty()) {
            return near;
        }
        return stands.get(ThreadLocalRandom.current().nextInt(stands.size()));
    }

    private float teamPitch(TeamSide team) {
        return team == TeamSide.RED ? 0.92f : 1.08f;
    }

    private float randomPitch(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }

    private int ambienceInterval() {
        int base = Math.max(8, settings.crowdAmbienceInterval);
        return (int) Math.max(8, base - intensity * base * 0.5);
    }
}
