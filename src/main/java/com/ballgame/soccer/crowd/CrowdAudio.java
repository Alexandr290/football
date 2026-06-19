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

import java.util.concurrent.ThreadLocalRandom;

/**
 * Dynamic stadium crowd audio using real-life recordings (resource pack) that react to
 * match tension, saves, attacks, goals, and set-piece moments. Falls back to vanilla
 * sounds when the crowd pack is not loaded.
 */
public final class CrowdAudio {

    private static final double INTENSITY_SMOOTHING = 0.88;

    private final SoccerPlugin plugin;
    private final Game game;
    private final Arena arena;
    private final SoccerSettings settings;

    private double intensity;
    private int ambienceTick;
    private int dangerCooldown;
    private int saveCooldown;
    private int celebrationTicks;
    private TeamSide celebratingTeam;

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

        ambienceTick++;
        int interval = ambienceInterval();
        if (ambienceTick >= interval) {
            ambienceTick = 0;
            playAmbienceLayer();
        }

        if (game.getState().isLive() && !game.isCelebrating()) {
            checkDangerousAttack();
        }
    }

    public void onMatchAnticipation() {
        if (!settings.crowdEnabled) {
            return;
        }
        playCrowd(CrowdSound.AMBIENCE_LOW, 0.45f, randomPitch(0.95f, 1.05f));
    }

    public void onKickoff() {
        if (!settings.crowdEnabled) {
            return;
        }
        intensity = Math.max(intensity, 0.45);
        playCrowd(CrowdSound.KICKOFF, 0.55f, 1.0f);
        playCrowd(CrowdSound.AMBIENCE_MEDIUM, 0.35f, 1.0f);
    }

    public void onGoal(TeamSide scoringTeam, boolean ownGoal) {
        if (!settings.crowdEnabled || !settings.crowdGoalCelebration) {
            return;
        }
        celebratingTeam = scoringTeam;
        celebrationTicks = 20 * 5;
        intensity = 1.0;

        float homeBias = ownGoal ? 0.65f : 1.0f;
        playCrowd(CrowdSound.GOAL_CHEER, 0.85f * homeBias, teamPitch(scoringTeam));
        playCrowd(CrowdSound.AMBIENCE_HIGH, 0.55f * homeBias, teamPitch(scoringTeam));
        playChantBurst(scoringTeam, 2);
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

        playCrowd(CrowdSound.APPLAUSE, 0.5f, 0.95f);
        playCrowd(CrowdSound.STINGER, 0.4f, 1.1f);
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

        playCrowd(CrowdSound.GASP, 0.55f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.15f);
        playCrowd(CrowdSound.STINGER, 0.35f, 1.2f);
    }

    public void onFullTime() {
        if (!settings.crowdEnabled) {
            return;
        }
        playCrowd(CrowdSound.APPLAUSE, 0.6f, 1.0f);
        playCrowd(CrowdSound.AMBIENCE_MEDIUM, 0.4f, 0.95f);
    }

    private void tickGoalCelebration() {
        celebrationTicks--;
        if (celebrationTicks % 12 == 0) {
            playCrowd(CrowdSound.GOAL_CHEER, 0.5f, teamPitch(celebratingTeam));
            playChantBurst(celebratingTeam, 1);
        }
        if (celebrationTicks <= 0) {
            celebratingTeam = null;
            intensity = 0.5;
        }
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

        double base = 0.18;
        double speed = ball.getSpeed();
        double speedFactor = Math.min(1.0, speed / settings.maxSpeed) * 0.28;

        double goalProximity = goalThreat(ball.getLocation());
        double possessionFactor = 0.0;
        TeamSide lastTouch = ball.getLastTouchTeam();
        if (lastTouch != null && goalProximity > 0.5) {
            possessionFactor = 0.12;
        }

        return Math.min(1.0, base + speedFactor + goalProximity * 0.42 + possessionFactor);
    }

    private double goalThreat(Location ballLoc) {
        double best = 0.0;
        for (TeamSide side : TeamSide.values()) {
            Location center = goalCenter(side);
            if (center == null) {
                continue;
            }
            double dx = ballLoc.getX() - center.getX();
            double dz = ballLoc.getZ() - center.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double threat = 1.0 - Math.min(1.0, horiz / settings.crowdDangerRadius);
            best = Math.max(best, threat);
        }
        return best;
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

        Location loc = ball.getLocation();
        double threat = goalThreat(loc);
        if (threat < 0.55 || ball.getSpeed() < settings.crowdDangerMinSpeed) {
            return;
        }

        dangerCooldown = 35;
        intensity = Math.min(1.0, intensity + 0.25);
        float vol = (float) (0.25 + threat * 0.35);
        playCrowd(CrowdSound.AMBIENCE_HIGH, vol, 0.95f + (float) threat * 0.1f);
        playCrowd(CrowdSound.GASP, vol * 0.6f, 1.05f);

        if (threat > 0.82 && ball.getSpeed() > settings.crowdDangerMinSpeed * 1.4) {
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
            if (rng.nextInt(3) == 0) {
                playCrowd(CrowdSound.AMBIENCE_LOW, vol * 0.35f, randomPitch(0.9f, 1.1f));
            }
            return;
        }

        if (intensity < 0.8) {
            playCrowd(CrowdSound.AMBIENCE_HIGH, vol * 0.45f, randomPitch(0.95f, 1.08f));
            if (rng.nextInt(2) == 0) {
                playCrowd(CrowdSound.CHANT, vol * 0.4f, randomPitch(0.92f, 1.12f));
            }
            return;
        }

        playCrowd(CrowdSound.AMBIENCE_HIGH, vol * 0.65f, randomPitch(1.0f, 1.12f));
        playCrowd(CrowdSound.CHANT, vol * 0.45f, randomPitch(0.95f, 1.1f));
        if (rng.nextInt(3) == 0) {
            playCrowd(CrowdSound.GASP, vol * 0.25f, 1.15f);
        }
    }

    private void playChantBurst(TeamSide team, int beats) {
        float pitch = teamPitch(team);
        for (int i = 0; i < beats; i++) {
            final int delay = i * 4;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                playCrowd(CrowdSound.CHANT, 0.45f, pitch);
            }, delay);
        }
    }

    private void playCrowd(CrowdSound sound, float volume, float pitch) {
        float scaled = volume * (float) settings.crowdVolume;
        Location stands = standLocation();
        game.forEachParticipant(player -> {
            if (!player.isOnline()) {
                return;
            }
            playFor(player, stands, sound, scaled, pitch);
        });
    }

    private void playFor(Player player, Location stands, CrowdSound sound, float volume, float pitch) {
        Location at = stands != null ? stands : player.getLocation();
        if (plugin.crowdPack().canPlay(player)) {
            player.playSound(at, sound.key(), SoundCategory.VOICE, volume, pitch);
            return;
        }
        Sound fallback = sound.fallback();
        if (fallback != null) {
            player.playSound(at, fallback, volume, pitch);
        }
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

    private Location goalCenter(TeamSide defendedBy) {
        if (arena.getGoal(defendedBy) == null) {
            return null;
        }
        return arena.getGoal(defendedBy).getCenter();
    }

    private Location standLocation() {
        Location spec = arena.getSpectator();
        if (spec != null) {
            return spec.clone();
        }
        if (arena.getField() != null) {
            return arena.getField().getCenter().clone().add(0, 8, 0);
        }
        if (arena.getBallSpawn() != null) {
            return arena.getBallSpawn().clone().add(0, 8, 0);
        }
        return null;
    }
}
