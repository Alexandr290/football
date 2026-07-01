package com.ballgame.soccer.bot;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.mechanics.KickActor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * AI-controlled match participant with a visible body and full kick physics.
 */
public final class MatchBot implements KickActor {

    private final UUID uuid;
    private final SoccerPlayer profile;
    private final BotVisual visual;
    private final Game game;
    private final SoccerSettings settings;
    private final int squadIndex;
    private final BotRole role;
    private final BotAttributes attributes;
    private final BotMovement movement = new BotMovement();

    private Location location;
    private Vector velocity = new Vector(0, 0, 0);
    private float bodyYaw;
    private boolean sprinting;
    private long actionCooldown;
    private long thinkCooldown;
    private long slideTackleReadyTick;

    // Persistent locomotion intent — refreshed by the AI on its reaction cadence
    // but acted on every tick so movement stays smooth and runs at full speed.
    private Location moveTarget;
    private boolean sprintIntent;
    private boolean hasMoveIntent;

    // Stamina drives sprinting: drains while sprinting, recovers otherwise. Once
    // exhausted the bot must recover before it can sprint again.
    private double stamina;
    private boolean recovering;

    public MatchBot(SoccerPlugin plugin, Game game, String name, TeamSide team, int squadIndex, Location spawn) {
        this.uuid = UUID.randomUUID();
        this.game = game;
        this.settings = plugin.settings();
        this.squadIndex = squadIndex;
        this.role = BotRole.forSquadIndex(squadIndex);
        this.attributes = BotAttributes.generate(uuid, role, settings);
        this.profile = SoccerPlayer.createBot(uuid, name, team, attributes.maxStamina);
        this.stamina = attributes.maxStamina;
        this.visual = new BotVisual(plugin, settings, name, team, squadIndex);
        this.location = spawn != null ? spawn.clone() : game.getArena().getBallSpawn();
        if (this.location != null) {
            this.location.setPitch(0f);
            this.bodyYaw = this.location.getYaw();
            movement.seedFromYaw(bodyYaw);
            visual.spawn(this.location);
        }
    }

    BotAttributes getAttributes() {
        return attributes;
    }

    /** Fraction (0..1) of stamina remaining — used by the AI to pace its sprints. */
    double staminaFraction() {
        double max = attributes.maxStamina;
        return max > 0 ? Math.max(0.0, Math.min(1.0, stamina / max)) : 1.0;
    }

    /** Whether the bot is physically able to sprint right now. */
    boolean canSprint() {
        if (settings == null || !settings.botStaminaEnabled) {
            return true;
        }
        return !recovering && stamina > 1.0;
    }

    public UUID getUuid() {
        return uuid;
    }

    public SoccerPlayer getProfile() {
        return profile;
    }

    public TeamSide getTeam() {
        return profile.getTeam();
    }

    public String getName() {
        return profile.getName();
    }

    public int getSquadIndex() {
        return squadIndex;
    }

    public BotRole getRole() {
        return role;
    }

    public void remove() {
        visual.remove();
    }

    public void tick(long matchTick) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }
        if (visual.usesCitizensNavigation()) {
            syncFromVisual();
        }

        // Re-think on the bot's reaction cadence (a small human-like decision lag),
        // but keep moving toward the latest intent on every single tick so that
        // locomotion is smooth and reaches the full configured speed.
        if (thinkCooldown > 0) {
            thinkCooldown--;
        } else {
            // Keepers re-read the ball every tick; outfield bots decide almost every tick.
            thinkCooldown = switch (game.getBotDifficulty(getTeam())) {
                case EASY -> role == BotRole.GOALKEEPER ? 1 : 4;
                case MEDIUM -> role == BotRole.GOALKEEPER ? 0 : 1;
                case HARD -> 0;
            };
            hasMoveIntent = false;
            BotAi.think(game, this, matchTick);
        }

        applyMovement();
        updateStamina();
        syncVisual();
    }

    /**
     * Records where the AI wants to go this think. The actual stepping happens in
     * {@link #applyMovement()} every tick.
     */
    void moveToward(Location target, boolean sprint, SoccerSettings ignored) {
        if (target == null || location == null) {
            return;
        }
        hasMoveIntent = true;
        moveTarget = target.clone();
        moveTarget.setY(resolveGroundY(target.getY()));
        sprintIntent = sprint;
    }

    private void applyMovement() {
        if (moveTarget == null) {
            // No active target: bleed off momentum like a player coasting to a stop.
            sprinting = false;
            if (velocity.lengthSquared() > 1.0e-4 && !visual.usesCitizensNavigation()) {
                velocity = velocity.clone().multiply(0.6);
                location.add(velocity);
                location.setY(resolveGroundY(location.getY()));
            }
            return;
        }

        boolean wantSprint = sprintIntent && canSprint();

        if (visual.usesCitizensNavigation()) {
            // Citizens' navigator does the actual stepping; syncVisual() issues the
            // navigate call. Here we only resolve whether the run should be a sprint.
            boolean shouldSprint = wantSprint && horizontalDistance(location, moveTarget) > 1.2;
            sprinting = shouldSprint;
            return;
        }

        double difficultySpeed = switch (game.getBotDifficulty(getTeam())) {
            case EASY -> 0.84;
            case MEDIUM -> 1.0;
            case HARD -> 1.12;
        };
        double difficultyAgility = switch (game.getBotDifficulty(getTeam())) {
            case EASY -> 0.75;
            case MEDIUM -> 1.0;
            case HARD -> 1.18;
        };
        double speedMul = attributes.topSpeedMul * difficultySpeed * fatigueFactor(wantSprint);
        BotMovement.Result result = movement.advance(this, moveTarget, wantSprint, settings,
                speedMul, attributes.accelMul * difficultyAgility, attributes.agility * difficultyAgility);
        velocity = result.velocity();
        sprinting = result.sprinting();
        bodyYaw = location.getYaw();
        location.setY(resolveGroundY(location.getY()));
    }

    /** Tired legs run slower; full stamina runs at the bot's natural pace. */
    private double fatigueFactor(boolean sprinting) {
        if (settings == null || !settings.botStaminaEnabled) {
            return 1.0;
        }
        double frac = staminaFraction();
        // Below ~40% stamina the bot visibly slows; the effect is strongest when
        // trying to sprint on an empty tank.
        double floor = sprinting ? 0.80 : 0.90;
        return floor + (1.0 - floor) * Math.min(1.0, frac / 0.4);
    }

    private void updateStamina() {
        if (settings == null || !settings.botStaminaEnabled) {
            return;
        }
        if (sprinting) {
            stamina = Math.max(0.0, stamina - attributes.sprintDrain);
            if (stamina <= 0.5) {
                recovering = true;
            }
        } else {
            stamina = Math.min(attributes.maxStamina, stamina + attributes.staminaRegen);
            if (recovering && stamina >= settings.botStaminaRecoverThreshold) {
                recovering = false;
            }
        }
        profile.setStamina(stamina);
    }

    void holdPosition() {
        hasMoveIntent = false;
        moveTarget = null;
        sprintIntent = false;
    }

    void faceLocation(Location target) {
        if (target == null || location == null) {
            return;
        }
        Vector flat = target.toVector().subtract(location.toVector());
        flat.setY(0);
        if (flat.lengthSquared() > 1e-6) {
            applyFacing(yawToward(flat.normalize()));
        }
    }

    void faceDirection(Vector direction) {
        if (direction == null || location == null) {
            return;
        }
        Vector flat = direction.clone();
        flat.setY(0);
        if (flat.lengthSquared() > 1e-6) {
            applyFacing(yawToward(flat.normalize()));
        }
    }

    private void applyFacing(float yaw) {
        bodyYaw = yaw;
        location.setYaw(yaw);
        location.setPitch(0f);
        if (visual.usesCitizensNavigation() && velocity.lengthSquared() < 0.0025) {
            visual.applyFacing(yaw, false, velocity);
        }
    }

    void setActionCooldown(long ticks) {
        this.actionCooldown = ticks;
    }

    boolean canAct() {
        return actionCooldown <= 0;
    }

    boolean canSlideTackle(long matchTick) {
        return settings != null
                && settings.tackleEnabled
                && matchTick >= slideTackleReadyTick
                && canSprint()
                && staminaFraction() > 0.22;
    }

    boolean slideTackleToward(Location target, long matchTick) {
        if (!canSlideTackle(matchTick) || target == null || location == null) {
            return false;
        }
        Vector dir = target.toVector().subtract(location.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1.0e-6) {
            return false;
        }
        dir.normalize();

        double burst = 0.72 + attributes.agility * 0.16;
        Vector dash = dir.clone().multiply(burst);
        location.add(dash);
        location.setY(resolveGroundY(location.getY()));
        applyFacing(yawToward(dir));
        velocity = dash;
        sprinting = true;
        hasMoveIntent = false;
        moveTarget = null;
        sprintIntent = false;

        slideTackleReadyTick = matchTick + Math.max(20L, (long) (settings.tackleCooldown * 20.0));
        if (settings.botStaminaEnabled) {
            stamina = Math.max(0.0, stamina - Math.max(8.0, settings.tackleDrain * 0.55));
            if (stamina <= 0.5) {
                recovering = true;
            }
            profile.setStamina(stamina);
        }

        if (location.getWorld() != null) {
            location.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                    location, 8, 0.25, 0.04, 0.25, 0.01);
            location.getWorld().playSound(location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.65f, 0.9f);
        }
        return true;
    }

    public void setLocation(Location location) {
        this.location = location != null ? location.clone() : null;
        if (this.location != null) {
            this.bodyYaw = this.location.getYaw();
            movement.seedFromYaw(bodyYaw);
            this.velocity = new Vector(0, 0, 0);
            this.sprinting = false;
            this.moveTarget = null;
            this.hasMoveIntent = false;
            this.sprintIntent = false;
            this.slideTackleReadyTick = 0L;
            this.stamina = attributes.maxStamina;
            this.recovering = false;
        }
        visual.move(this.location, false, velocity);
    }

    public void syncVisual() {
        if (visual.usesCitizensNavigation()) {
            if (hasMoveIntent && moveTarget != null) {
                visual.navigate(moveTarget, sprinting, velocity);
            } else {
                visual.applyFacing(bodyYaw, sprinting, velocity);
            }
            syncFromVisual();
            return;
        }
        visual.move(location, sprinting, velocity);
    }

    private void syncFromVisual() {
        Location synced = visual.readSyncedLocation();
        if (synced == null) {
            return;
        }
        Vector delta = synced.toVector().subtract(location.toVector());
        delta.setY(0);
        if (delta.lengthSquared() > 1.0e-8) {
            velocity = delta;
        } else if (!visual.usesCitizensNavigation()) {
            velocity = new Vector(0, 0, 0);
        }
        location.setX(synced.getX());
        location.setY(synced.getY());
        location.setZ(synced.getZ());
        location.setYaw(synced.getYaw());
        location.setPitch(0f);
        bodyYaw = synced.getYaw();
    }

    public void swingArm() {
        visual.swingArm();
    }

    @Override
    public Location getEyeLocation() {
        return location != null ? location.clone().add(0, 1.62, 0) : null;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public Vector getVelocity() {
        return velocity;
    }

    @Override
    public boolean isSprinting() {
        return sprinting;
    }

    private static float yawToward(Vector flat) {
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    private double resolveGroundY(double fallback) {
        if (game.getArena().getField() != null) {
            return Math.max(game.getArena().getField().getMinY() + 0.05, fallback);
        }
        return fallback;
    }

    private static double horizontalDistance(Location a, Location b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
