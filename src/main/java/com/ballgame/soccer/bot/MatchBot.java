package com.ballgame.soccer.bot;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.mechanics.KickActor;
import org.bukkit.Location;
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
    private final int squadIndex;
    private final BotRole role;
    private final BotMovement movement = new BotMovement();

    private Location location;
    private Vector velocity = new Vector(0, 0, 0);
    private float bodyYaw;
    private boolean sprinting;
    private long actionCooldown;
    private long thinkCooldown;
    private Location moveTarget;
    private boolean requestedMove;

    public MatchBot(SoccerPlugin plugin, Game game, String name, TeamSide team, int squadIndex, Location spawn) {
        this.uuid = UUID.randomUUID();
        this.game = game;
        this.squadIndex = squadIndex;
        this.role = BotRole.fromIndex(squadIndex);
        this.profile = SoccerPlayer.createBot(uuid, name, team, plugin.settings().maxStamina);
        this.visual = new BotVisual(plugin, plugin.settings(), name, team, squadIndex);
        this.location = spawn != null ? spawn.clone() : game.getArena().getBallSpawn();
        if (this.location != null) {
            this.location.setPitch(0f);
            this.bodyYaw = this.location.getYaw();
            movement.seedFromYaw(bodyYaw);
            visual.spawn(this.location);
        }
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
        if (thinkCooldown > 0) {
            thinkCooldown--;
            syncVisual();
            return;
        }
        thinkCooldown = 1;
        requestedMove = false;
        BotAi.think(game, this, matchTick);
        syncVisual();
    }

    void moveToward(Location target, boolean sprint, SoccerSettings settings) {
        if (target == null || location == null) {
            return;
        }
        requestedMove = true;
        moveTarget = target.clone();
        moveTarget.setY(resolveGroundY(target.getY()));

        if (visual.usesCitizensNavigation()) {
            Location navTarget = moveTarget.clone();
            boolean shouldSprint = sprint && horizontalDistance(location, navTarget) > 1.2;
            visual.navigate(navTarget, shouldSprint, velocity);
            syncFromVisual();
            sprinting = shouldSprint && velocity.lengthSquared() > 0.004;
            return;
        }

        BotMovement.Result result = movement.advance(this, moveTarget, sprint, settings);
        velocity = result.velocity();
        sprinting = result.sprinting();
        bodyYaw = location.getYaw();
        location.setY(resolveGroundY(moveTarget.getY()));
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

    public void setLocation(Location location) {
        this.location = location != null ? location.clone() : null;
        if (this.location != null) {
            this.bodyYaw = this.location.getYaw();
            movement.seedFromYaw(bodyYaw);
            this.velocity = new Vector(0, 0, 0);
            this.sprinting = false;
            this.moveTarget = null;
            this.requestedMove = false;
        }
        visual.move(this.location, false, velocity);
    }

    public void syncVisual() {
        if (visual.usesCitizensNavigation()) {
            if (requestedMove && moveTarget != null) {
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
