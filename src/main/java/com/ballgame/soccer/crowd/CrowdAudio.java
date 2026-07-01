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
 * Dynamic stadium crowd audio for the full match (kickoff through full time).
 * Layers a continuous ambience bed with reactive shouts, chants, and event stingers.
 */
public final class CrowdAudio {

    private static final double INTENSITY_SMOOTHING = 0.74;
    private static final double MATCH_FLOOR_INTENSITY = 0.42;

    private final SoccerPlugin plugin;
    private final Game game;
    private final Arena arena;
    private final SoccerSettings settings;

    private double intensity;
    private int ambienceTick;
    private int shoutTick;
    private int chantTick;
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
        this.intensity = MATCH_FLOOR_INTENSITY;
    }

    public void tick() {
        if (!settings.crowdEnabled) {
            return;
        }
        if (celebrationTicks > 0) {
            tickGoalCelebration();
            return;
        }
        if (!game.getState().hasStadiumCrowd()) {
            return;
        }
        if (saveCooldown > 0) {
            saveCooldown--;
        }

        lastAttackFactor = currentAttackFactor();
        double target = computeTargetIntensity(lastAttackFactor);
        intensity = intensity * INTENSITY_SMOOTHING + target * (1.0 - INTENSITY_SMOOTHING);

        ambienceTick++;
        if (ambienceTick >= ambienceInterval()) {
            ambienceTick = 0;
            playAmbienceLayer();
        }

        shoutTick++;
        int shoutInterval = VanillaNpcCrowd.shoutIntervalTicks(lastAttackFactor);
        if (shoutTick >= shoutInterval) {
            shoutTick = 0;
            playNpcShoutLayer(lastAttackFactor);
        }

        chantTick++;
        if (chantTick >= chantInterval()) {
            chantTick = 0;
            playChantLayer();
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
        intensity = Math.max(intensity, 0.5);
        playNpcShout(VanillaNpcCrowd.VOL_ATTACK * 0.75f);
        playCrowd(CrowdSound.KICKOFF, 0.55f, 1.0f);
        playCrowd(CrowdSound.AMBIENCE_MEDIUM, 0.45f, 1.0f);
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
            intensity = Math.max(MATCH_FLOOR_INTENSITY, 0.5);
        }
    }

    private void playNpcShoutLayer(double attackFactor) {
        float volume = VanillaNpcCrowd.volumeForAttackFactor(attackFactor);
        playNpcShout(volume);

        if (attackFactor > 0.55 && ThreadLocalRandom.current().nextInt(3) == 0) {
            playNpcShout(volume * 0.85f);
        }
    }

    private void playChantLayer() {
        if (intensity < 0.28) {
            return;
        }
        float vol = (float) (0.25 + intensity * 0.35);
        playCrowd(CrowdSound.CHANT, vol, randomPitch(0.9f, 1.12f));
    }

    private void playNpcShout(float volume) {
        if (plugin.crowdPack().useRealSounds() && plugin.crowdPack().isReady()) {
            playCrowd(CrowdSound.CHANT, volume * 0.55f, randomPitch(0.92f, 1.08f));
            return;
        }

        List<Location> stands = stands();
        game.forEachParticipant(player -> {
            if (!player.isOnline()) {
                return;
            }
            VanillaNpcCrowd.playShout(player, stands, volume, settings.crowdVolume);
        });
    }

    private void playGoalNpcBurst(float bias) {
        if (plugin.crowdPack().useRealSounds() && plugin.crowdPack().isReady()) {
            playCrowd(CrowdSound.GOAL_CHEER, VanillaNpcCrowd.VOL_GOAL * bias, 1.0f);
            return;
        }

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
        double axisPressure = VanillaNpcCrowd.attackFactor(arena, ball.getLocation());
        double goalPressure = goalPressureFactor(ball.getLocation());
        return Math.max(axisPressure, goalPressure);
    }

    private double computeTargetIntensity(double attack) {
        GameState state = game.getState();
        if (state == GameState.REPLAY) {
            return 0.82;
        }
        if (game.isCelebrating()) {
            return 0.95;
        }

        double phaseBase = switch (state) {
            case KICKOFF -> 0.58;
            case HALFTIME -> 0.44;
            case PENALTIES -> 0.68;
            case THROW_IN, OFFSIDE -> 0.5;
            case PLAYING, EXTRA_TIME -> MATCH_FLOOR_INTENSITY;
            default -> MATCH_FLOOR_INTENSITY;
        };

        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return phaseBase;
        }

        double speed = ball.getSpeed();
        double speedFactor = Math.min(1.0, speed / settings.maxSpeed) * 0.16;
        double dangerLift = attack >= 0.62 ? (attack - 0.62) / 0.38 * 0.18 : 0.0;
        return Math.min(1.0, phaseBase + attack * 0.62 + speedFactor + dangerLift);
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

        double attack = currentAttackFactor();
        if (attack < 0.62 || ball.getSpeed() < settings.crowdDangerMinSpeed) {
            return;
        }

        dangerCooldown = attack > 0.84 ? 16 : 24;
        intensity = Math.min(1.0, intensity + 0.32);
        float vol = VanillaNpcCrowd.volumeForAttackFactor(attack);
        playNpcShout(vol);
        playCrowd(CrowdSound.GASP, vol * 0.55f, 1.05f);

        if (attack > 0.85 && ball.getSpeed() > settings.crowdDangerMinSpeed * 1.3) {
            onNearMiss();
        }
    }

    /**
     * Continuous stadium bed: always keep a low layer going, add louder layers as intensity rises.
     */
    private void playAmbienceLayer() {
        float scale = (float) (0.55 + intensity * 0.65);
        float attackScale = (float) Math.max(0.0, lastAttackFactor);

        playCrowd(CrowdSound.AMBIENCE_LOW, scale * 0.72f, randomPitch(0.94f, 1.06f));

        if (intensity >= 0.28) {
            playCrowd(CrowdSound.AMBIENCE_MEDIUM,
                    scale * (0.42f + attackScale * 0.22f),
                    randomPitch(0.95f, 1.05f));
        }
        if (intensity >= 0.55 || lastAttackFactor >= 0.68) {
            playCrowd(CrowdSound.AMBIENCE_HIGH,
                    scale * (0.25f + attackScale * 0.3f),
                    randomPitch(0.96f, 1.1f));
        }
    }

    private void playCrowd(CrowdSound sound, float volume, float pitch) {
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

        if (plugin.crowdPack().useRealSounds() && plugin.crowdPack().isReady()) {
            player.playSound(at, sound.key(), SoundCategory.RECORDS, volume, pitch);
            return;
        }

        Sound fallback = sound.fallback();
        if (fallback != null) {
            player.playSound(at, fallback, SoundCategory.NEUTRAL, volume, pitch);
        }
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
        int base = Math.max(70, settings.crowdAmbienceInterval);
        return (int) Math.max(35, base - intensity * base * 0.48);
    }

    private int chantInterval() {
        return (int) Math.max(24, 82 - intensity * 46 - lastAttackFactor * 18);
    }

    private double goalPressureFactor(Location ballLoc) {
        double radius = Math.max(8.0, settings.crowdDangerRadius);
        double best = 0.0;
        for (TeamSide side : TeamSide.values()) {
            if (arena.getGoal(side) == null) {
                continue;
            }
            Location goal = arena.getGoal(side).getCenter();
            double dx = ballLoc.getX() - goal.getX();
            double dz = ballLoc.getZ() - goal.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            best = Math.max(best, 1.0 - Math.min(1.0, horiz / radius));
        }
        return best;
    }
}
