package com.ballgame.soccer.bot;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.mechanics.KickActor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * AI-controlled match participant with a visible body and full kick physics.
 */
public final class MatchBot implements KickActor {

    /** Head turn rate (degrees per tick) — quick, but never an instant snap. */
    private static final float HEAD_TURN_PER_TICK = 20f;
    private static final float HEAD_PITCH_PER_TICK = 11f;
    private static final int DIVE_TICKS = 9;

    private final UUID uuid;
    private final SoccerPlayer profile;
    private final BotVisual visual;
    private final Game game;
    private final SoccerSettings settings;
    private final int squadIndex;
    private final int formationSlot;
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
    private double paceScale = 1.0;

    // Stamina drives sprinting: drains while sprinting, recovers otherwise. Once
    // exhausted the bot must recover before it can sprint again.
    private double stamina;
    private boolean recovering;

    // Eyes: the head tracks the AI's focus (usually the ball) separately from the
    // direction of travel, with short glances when the player scans the pitch.
    private Location lookTarget;
    private Location glanceTarget;
    private long glanceUntil;
    private long nextScanTick;
    private float headYaw;
    private float headPitch;
    private float pitchBias;

    // Duels and ball control bookkeeping (read by the AI and the match physics).
    private long staggerUntil;
    private long duelReadyTick;
    private long lastCarryTick = -10_000L;
    private long controlStartTick = -10_000L;
    private Vector touchDirection;
    private long jockeySince = -1L;
    private long runUntilTick;
    private long skillMoveUntil;
    private Vector skillMoveDirection;
    private Location squareTarget;
    private long squareUntil;

    // Goalkeeper dive (short lateral leap with a small hop), then a moment on the
    // ground before getting back up.
    private int diveTicks;
    private int downTicks;
    private Vector diveVelocity;
    private double diveBaseY;
    private boolean crouched;

    // Celebration hop.
    private int hopTicks;
    private double hopBaseY;

    public MatchBot(SoccerPlugin plugin, Game game, String name, TeamSide team,
                    int squadIndex, BotRole role, int formationSlot, Location spawn) {
        this(plugin, game, name, team, squadIndex, role, formationSlot, spawn, null);
    }

    public MatchBot(SoccerPlugin plugin, Game game, String name, TeamSide team,
                    int squadIndex, BotRole role, int formationSlot, Location spawn,
                    String skinOverride) {
        this.uuid = UUID.randomUUID();
        this.game = game;
        this.settings = plugin.settings();
        this.squadIndex = squadIndex;
        this.formationSlot = formationSlot;
        this.role = role != null ? role : BotRole.forSquadIndex(squadIndex);
        this.attributes = BotAttributes.generate(uuid, role, settings);
        this.profile = SoccerPlayer.createBot(uuid, name, team, attributes.maxStamina);
        this.stamina = attributes.maxStamina;
        this.visual = new BotVisual(plugin, settings, name, team, squadIndex, skinOverride, game);
        this.location = spawn != null ? spawn.clone() : game.getArena().getBallSpawn();
        if (this.location != null) {
            this.location.setPitch(0f);
            this.bodyYaw = this.location.getYaw();
            this.headYaw = bodyYaw;
            movement.seedFromYaw(bodyYaw);
            visual.spawn(this.location);
        }
    }

    BotAttributes getAttributes() {
        return attributes;
    }

    /** 0..1 technique under pressure: first touch, finishing, passing accuracy. */
    public double composure() {
        return attributes.composure;
    }

    public double aggression() {
        return attributes.aggression;
    }

    public double anticipation() {
        return attributes.anticipation;
    }

    public double agility() {
        return attributes.agility;
    }

    public double getStaminaFraction() {
        return staminaFraction();
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

    public int getFormationSlot() {
        return formationSlot;
    }

    public BotRole getRole() {
        return role;
    }

    public void refreshKitVisual() {
        visual.refreshArmorKit();
    }

    public void remove() {
        visual.remove();
    }

    public boolean ownsEntity(Entity entity) {
        return visual.ownsEntity(entity);
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
                case EASY -> role == BotRole.GOALKEEPER ? 1 : 3;
                case MEDIUM -> role == BotRole.GOALKEEPER ? 1 : 2;
                case HARD -> role == BotRole.GOALKEEPER ? 0 : 1;
            };
            BotAi.think(game, this, matchTick);
        }

        if (diveTicks > 0) {
            applyDive();
        } else if (downTicks > 0) {
            velocity = new Vector(0, 0, 0);
            sprinting = false;
            if (--downTicks == 0) {
                visual.clearDivePose();
            }
        } else {
            applyMovement(matchTick);
            applyHop();
        }
        updateStamina();
        updateHead(matchTick);
        syncVisual();
    }

    /**
     * Records where the AI wants to go this think. The actual stepping happens in
     * {@link #applyMovement(long)} every tick.
     */
    void moveToward(Location target, boolean sprint, SoccerSettings ignored) {
        moveToward(target, sprint, 1.0);
    }

    /** As above, with a pace scale (&lt;1 = jockeying/backpedal shuffle, carrying the ball). */
    void moveToward(Location target, boolean sprint, double pace) {
        if (target == null || location == null) {
            return;
        }
        hasMoveIntent = true;
        moveTarget = target.clone();
        moveTarget.setY(resolveGroundY(target.getY()));
        sprintIntent = sprint;
        paceScale = Math.max(0.3, Math.min(1.0, pace));
    }

    private void applyMovement(long matchTick) {
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

        boolean staggered = isStaggered(matchTick);
        boolean wantSprint = sprintIntent && canSprint() && !staggered;

        if (visual.usesCitizensNavigation()) {
            // Citizens' navigator does the actual stepping; syncVisual() issues the
            // navigate call. Here we only resolve whether the run should be a sprint.
            boolean shouldSprint = wantSprint && horizontalDistance(location, moveTarget) > 1.2;
            sprinting = shouldSprint;
            return;
        }

        double difficultySpeed = switch (game.getBotDifficulty(getTeam())) {
            case EASY -> 0.88;
            case MEDIUM -> 1.0;
            case HARD -> 1.05;
        };
        double difficultyAgility = switch (game.getBotDifficulty(getTeam())) {
            case EASY -> 0.75;
            case MEDIUM -> 1.0;
            case HARD -> 1.18;
        };
        double speedMul = attributes.topSpeedMul * difficultySpeed * fatigueFactor(wantSprint) * paceScale;
        if (staggered) {
            // Beaten in a duel: off balance, a couple of slow steps before recovering.
            speedMul *= 0.42;
        }
        BotMovement.Result result = movement.advance(this, moveTarget, wantSprint, settings,
                speedMul, attributes.accelMul * difficultyAgility, attributes.agility * difficultyAgility);
        velocity = result.velocity();
        sprinting = result.sprinting();
        bodyYaw = location.getYaw();
        if (squareTarget != null && matchTick < squareUntil) {
            // Shuffling sideways or backwards while staying square to the man on the ball.
            Vector flat = squareTarget.toVector().subtract(location.toVector());
            flat.setY(0);
            if (flat.lengthSquared() > 1.0e-4) {
                bodyYaw = yawToward(flat.normalize());
                location.setYaw(bodyYaw);
            }
        }
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

    public void faceLocation(Location target) {
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

    public void setActionCooldown(long ticks) {
        this.actionCooldown = ticks;
    }

    public boolean canAct() {
        return actionCooldown <= 0;
    }

    // ------------------------------------------------------------------
    // Eyes
    // ------------------------------------------------------------------

    /** Primary focus for the head (ball, carrier, pass target...). */
    void lookAt(Location target) {
        lookTarget = target != null ? target.clone() : null;
    }

    /** Short look somewhere else — a shoulder check or a glance at a pass target. */
    void glance(Location target, int ticks, long matchTick) {
        if (target == null) {
            return;
        }
        glanceTarget = target.clone();
        glanceUntil = matchTick + Math.max(1, ticks);
    }

    boolean isGlancing(long matchTick) {
        return glanceTarget != null && matchTick < glanceUntil;
    }

    long nextScanTick() {
        return nextScanTick;
    }

    void scheduleNextScan(long tick) {
        nextScanTick = tick;
    }

    /** Extra downward head tilt (dejected after conceding, eyes on the ball at feet). */
    void setHeadPitchBias(float bias) {
        pitchBias = bias;
    }

    private void updateHead(long matchTick) {
        Location focus = isGlancing(matchTick) ? glanceTarget : lookTarget;
        if (!isGlancing(matchTick)) {
            glanceTarget = null;
        }
        if (focus == null) {
            focus = game.getBallLocation();
        }
        float[] look = HeadLook.toward(location, focus, bodyYaw);
        float desiredYaw = look[0];
        float desiredPitch = HeadLook.clampPitch(look[1] + pitchBias);

        // At pace the neck can only twist so far before the shoulders follow.
        double speed = Math.hypot(velocity.getX(), velocity.getZ());
        float maxTwist = speed > 0.2 ? 100f : 160f;
        float rel = HeadLook.wrap(desiredYaw - bodyYaw);
        rel = Math.max(-maxTwist, Math.min(maxTwist, rel));
        desiredYaw = bodyYaw + rel;

        headYaw = HeadLook.approachYaw(headYaw, desiredYaw, HEAD_TURN_PER_TICK);
        headPitch = HeadLook.approach(headPitch, desiredPitch, HEAD_PITCH_PER_TICK);
    }

    // ------------------------------------------------------------------
    // Duels, control and runs
    // ------------------------------------------------------------------

    public boolean isStaggered(long matchTick) {
        return matchTick < staggerUntil;
    }

    /** Lost a duel: off balance for a moment, can't touch the ball or sprint. */
    public void stagger(long matchTick, int ticks) {
        staggerUntil = Math.max(staggerUntil, matchTick + ticks);
        actionCooldown = Math.max(actionCooldown, ticks);
        jockeySince = -1L;
    }

    public boolean canDuel(long matchTick) {
        return matchTick >= duelReadyTick && !isStaggered(matchTick);
    }

    public void setDuelCooldown(long matchTick, int ticks) {
        duelReadyTick = matchTick + ticks;
    }

    public void markCarryTouch(long matchTick) {
        lastCarryTick = matchTick;
    }

    public long getLastCarryTick() {
        return lastCarryTick;
    }

    /** The bot just brought the ball under control (first touch, interception, tackle). */
    public void markControl(long matchTick) {
        controlStartTick = matchTick;
    }

    public long getControlStartTick() {
        return controlStartTick;
    }

    /** Direction the AI would like its next first touch to push the ball into. */
    public Vector getTouchDirection() {
        return touchDirection != null ? touchDirection.clone() : null;
    }

    void setTouchDirection(Vector direction) {
        touchDirection = direction != null ? direction.clone() : null;
    }

    /** Match ticks spent jockeying the current carrier (independent of the think cadence). */
    int jockeyTicks() {
        return jockeySince < 0L ? 0 : (int) Math.max(0L, game.getMatchTick() - jockeySince);
    }

    void tickJockey(boolean jockeying) {
        if (!jockeying) {
            jockeySince = -1L;
        } else if (jockeySince < 0L) {
            jockeySince = game.getMatchTick();
        }
    }

    boolean isMakingRun(long matchTick) {
        return matchTick < runUntilTick;
    }

    void startRun(long matchTick, int ticks) {
        runUntilTick = matchTick + ticks;
    }

    /** A breather between runs: nobody sprints in behind on every phase. */
    boolean canStartRun(long matchTick) {
        return matchTick >= runUntilTick + 40;
    }

    Vector activeSkillMove(long matchTick) {
        return matchTick < skillMoveUntil && skillMoveDirection != null ? skillMoveDirection.clone() : null;
    }

    void startSkillMove(Vector direction, long matchTick, int ticks) {
        skillMoveDirection = direction != null ? direction.clone() : null;
        skillMoveUntil = matchTick + ticks;
    }

    /** Keep the chest facing {@code target} while moving (jockeying, backpedalling). */
    void squareUpTo(Location target, long matchTick, int ticks) {
        squareTarget = target != null ? target.clone() : null;
        squareUntil = target != null ? matchTick + ticks : 0L;
    }

    /**
     * Ticks of lag between the ball moving and this player reacting to it: sharp
     * readers of the game see it sooner; tired legs and easy bots react later.
     */
    int perceptionDelayTicks() {
        int delay = 2 + (int) Math.round((1.0 - attributes.anticipation) * 3.0);
        BotDifficulty difficulty = game.getBotDifficulty(getTeam());
        if (difficulty == BotDifficulty.EASY) {
            delay += 2;
        } else if (difficulty == BotDifficulty.HARD) {
            delay -= 1;
        }
        if (role == BotRole.GOALKEEPER) {
            delay -= 1;
        }
        if (staminaFraction() < 0.25) {
            delay += 1;
        }
        return Math.max(1, Math.min(7, delay));
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

    /** Goalkeeper launches sideways at the ball (visual leap; the save is resolved by the match). */
    public void diveToward(Location target) {
        if (target == null || location == null || diveTicks > 0 || downTicks > 0) {
            return;
        }
        Vector dir = target.toVector().subtract(location.toVector());
        dir.setY(0);
        double dist = dir.length();
        if (dist < 0.25) {
            return;
        }
        dir.multiply(1.0 / dist);
        diveVelocity = dir.clone().multiply(Math.min(0.42, 0.12 + dist * 0.11));
        diveTicks = DIVE_TICKS;
        endHop();
        diveBaseY = resolveGroundY(location.getY());
        holdPosition();
        setCrouched(false);
        bodyYaw = yawToward(dir);
        location.setYaw(bodyYaw);
        visual.applyDivePose();
        if (location.getWorld() != null) {
            location.getWorld().playSound(location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.75f);
        }
    }

    private void applyDive() {
        int step = DIVE_TICKS - diveTicks;
        double lift = Math.sin(Math.PI * (step + 1) / (DIVE_TICKS + 1)) * 0.55;
        location.add(diveVelocity);
        diveVelocity.multiply(0.82);
        location.setY(diveBaseY + lift);
        velocity = diveVelocity.clone();
        sprinting = false;
        diveTicks--;
        if (diveTicks == 0) {
            location.setY(diveBaseY);
            downTicks = 10;
            if (location.getWorld() != null) {
                location.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                        location, 6, 0.3, 0.04, 0.3, 0.01);
            }
        }
    }

    public boolean isDiving() {
        return diveTicks > 0;
    }

    /** On the turf after a dive, getting back up. */
    public boolean isDown() {
        return downTicks > 0;
    }

    void setCrouched(boolean crouch) {
        if (crouched == crouch) {
            return;
        }
        crouched = crouch;
        if (diveTicks == 0 && downTicks == 0) {
            visual.applyCrouch(crouch);
        }
    }

    /** Small jump on the spot (celebrations). */
    void hop() {
        if (hopTicks > 0 || diveTicks > 0 || downTicks > 0 || location == null) {
            return;
        }
        hopTicks = 7;
        hopBaseY = resolveGroundY(location.getY());
    }

    private void applyHop() {
        if (hopTicks <= 0 || location == null) {
            return;
        }
        hopTicks--;
        double lift = Math.sin(Math.PI * (7 - hopTicks) / 7.0) * 0.5;
        location.setY(hopTicks == 0 ? hopBaseY : hopBaseY + lift);
    }

    private void endHop() {
        if (hopTicks > 0 && location != null) {
            location.setY(hopBaseY);
        }
        hopTicks = 0;
    }

    public void setLocation(Location location) {
        this.location = location != null ? location.clone() : null;
        if (this.location != null) {
            this.bodyYaw = this.location.getYaw();
            this.headYaw = bodyYaw;
            this.headPitch = 0f;
            movement.seedFromYaw(bodyYaw);
            this.velocity = new Vector(0, 0, 0);
            this.sprinting = false;
            this.moveTarget = null;
            this.hasMoveIntent = false;
            this.sprintIntent = false;
            this.slideTackleReadyTick = 0L;
            this.stamina = attributes.maxStamina;
            this.recovering = false;
            if (this.diveTicks > 0 || this.downTicks > 0) {
                visual.clearDivePose();
            }
            this.diveTicks = 0;
            this.downTicks = 0;
            this.hopTicks = 0;
            if (this.crouched) {
                this.crouched = false;
                visual.applyCrouch(false);
            }
            this.staggerUntil = 0L;
            this.jockeySince = -1L;
            this.runUntilTick = 0L;
            this.skillMoveUntil = 0L;
            this.squareTarget = null;
            this.pitchBias = 0f;
            this.glanceTarget = null;
        }
        visual.move(this.location, false, velocity, headYaw, headPitch);
    }

    /** Goalkeeper dive animation during a penalty kick only. */
    public void penaltyDive(Location diveSpot) {
        holdPosition();
        if (diveSpot != null) {
            setLocation(diveSpot);
        }
        visual.applyDivePose();
    }

    public void resetStandingPose() {
        visual.clearDivePose();
    }

    // ------------------------------------------------------------------
    // Cutscenes
    // ------------------------------------------------------------------

    /** Scripted walk used by the pre-match show: fixed pace, head on {@code lookAt}. */
    public void cinematicStep(Location target, double blocksPerTick, Location lookAt) {
        if (location == null || target == null) {
            return;
        }
        Vector delta = target.toVector().subtract(location.toVector());
        delta.setY(0);
        double dist = delta.length();
        if (dist > 1.0e-3) {
            velocity = delta.multiply(Math.min(dist, blocksPerTick) / dist);
            location.add(velocity);
            bodyYaw = yawToward(velocity.clone().normalize());
            location.setYaw(bodyYaw);
        } else {
            velocity = new Vector(0, 0, 0);
        }
        location.setY(resolveGroundY(location.getY()));
        sprinting = blocksPerTick > BotMovement.configuredWalkSpeed(settings) * 1.08;
        float[] look = HeadLook.toward(location, lookAt, bodyYaw);
        headYaw = HeadLook.approachYaw(headYaw, look[0], 14f);
        headPitch = HeadLook.approach(headPitch, look[1], 8f);
        visual.move(location, sprinting, velocity, headYaw, headPitch);
    }

    /** Puts the body on a spot instantly (cutscene staging). */
    public void cinematicPlace(Location at, Location lookAt) {
        if (at == null) {
            return;
        }
        setLocation(at);
        float[] look = HeadLook.toward(location, lookAt, bodyYaw);
        headYaw = look[0];
        headPitch = look[1];
        visual.move(location, false, velocity, headYaw, headPitch);
    }

    public void syncVisual() {
        if (visual.usesCitizensNavigation()) {
            if (moveTarget != null) {
                visual.navigate(moveTarget, sprinting, velocity);
            } else {
                visual.applyFacing(bodyYaw, sprinting, velocity);
            }
            syncFromVisual();
            return;
        }
        visual.move(location, sprinting, velocity, headYaw, headPitch);
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
        return velocity.clone();
    }

    /** Horizontal running speed in blocks per tick. */
    public double getSpeed() {
        return Math.hypot(velocity.getX(), velocity.getZ());
    }

    /** Flat unit vector the body is facing. */
    public Vector getFacing() {
        double rad = Math.toRadians(bodyYaw);
        return new Vector(-Math.sin(rad), 0, Math.cos(rad));
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
