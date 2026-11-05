package com.ballgame.soccer.ball;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * The physical soccer ball.
 *
 * <p>The ball is rendered with an {@link ItemDisplay} but does <strong>not</strong>
 * use vanilla entity physics. Instead {@link #tick()} runs a custom simulation
 * every server tick that models gravity, air drag, rolling friction, restitution
 * (bouncing) and the Magnus effect (spin-induced curve). Collisions with the
 * world are resolved per-axis with sub-stepping so fast shots don't tunnel
 * through walls.</p>
 */
public final class Ball {

    private static final String ENTITY_KEY = "ball";

    /** Physical collision radius used against world blocks. */
    private static final double RADIUS = 0.30;
    /** Visual size of the displayed ball model (diameter in blocks). */
    private static final float VISUAL_SCALE = 0.6f;
    /**
     * Fine-tuning nudge for the rendered ball's vertical position. The physics
     * already centers the ball at RADIUS above the ground; this only compensates
     * for the item model's own pivot. 0 keeps the visual centered on the physics.
     */
    private static final double RENDER_Y_OFFSET = 0.0;

    private final SoccerPlugin plugin;
    private final SoccerSettings settings;

    private World world;
    private ItemDisplay display;
    private Location location;
    private Location previousTickLocation;
    private final Vector velocity = new Vector(0, 0, 0);
    private final Vector spin = new Vector(0, 0, 0);
    private final Quaternionf rotation = new Quaternionf();

    private boolean frozen = false;
    private boolean onGround = false;

    // touch tracking for goals / assists / own-goals
    private UUID lastTouchId;
    private TeamSide lastTouchTeam;
    private UUID prevTouchId;
    private TeamSide prevTouchTeam;
    private long lastTouchTick = -1;
    private boolean dampenNextGroundBounce;
    private boolean blockCollisionThisTick;
    private long suppressGoalUntilTick = -1L;

    public Ball(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.settings();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void spawn(Location spawn) {
        remove();
        this.world = spawn.getWorld();
        this.location = spawn.clone();
        this.velocity.zero();
        this.spin.zero();

        ItemStack item = buildItem();
        this.display = (ItemDisplay) world.spawnEntity(renderLocation(), EntityType.ITEM_DISPLAY);
        display.setItemStack(item);
        display.setBillboard(Display.Billboard.FIXED);
        display.setTransformation(buildTransformation());
        display.setViewRange(2.0f);
        display.setShadowRadius(0.4f);
        display.setShadowStrength(0.7f);
        try {
            display.setTeleportDuration(1);
            display.setInterpolationDuration(1);
        } catch (Throwable ignored) {
            // Older servers: harmless to skip movement smoothing.
        }
        display.getPersistentDataContainer().set(
                entityKey(plugin), PersistentDataType.BYTE, (byte) 1);
    }

    public static NamespacedKey entityKey(SoccerPlugin plugin) {
        return new NamespacedKey(plugin, ENTITY_KEY);
    }

    /** Whether an entity is (or was) a plugin soccer ball display. */
    public static boolean isBallEntity(Entity entity, SoccerPlugin plugin) {
        if (!(entity instanceof ItemDisplay display) || display.isDead()) {
            return false;
        }
        if (display.getPersistentDataContainer().has(entityKey(plugin), PersistentDataType.BYTE)) {
            return true;
        }
        return looksLikeLegacyBall(display);
    }

    private static boolean looksLikeLegacyBall(ItemDisplay display) {
        if (display.getBillboard() != Display.Billboard.FIXED) {
            return false;
        }
        ItemStack stack = display.getItemStack();
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Material mat = stack.getType();
        if (mat != Material.SNOWBALL && mat != Material.FIREWORK_STAR && mat != Material.PLAYER_HEAD) {
            return false;
        }
        try {
            float range = display.getViewRange();
            if (range < 1.5f || range > 2.5f) {
                return false;
            }
        } catch (Throwable ignored) {
            // Older servers may not expose view range.
        }
        try {
            float scale = display.getTransformation().getScale().x();
            if (scale < 0.5f || scale > 0.7f) {
                return false;
            }
        } catch (Throwable ignored) {
            // If transformation is unavailable, rely on item + billboard only.
        }
        return true;
    }

    public void remove() {
        if (display != null && !display.isDead()) {
            display.remove();
        }
        display = null;
    }

    public boolean isSpawned() {
        return display != null && !display.isDead();
    }

    private ItemStack buildItem() {
        switch (settings.ballDisplay) {
            case "SNOWBALL":
                return Items.of(Material.SNOWBALL);
            case "FIREWORK_STAR":
                return Items.of(Material.FIREWORK_STAR);
            case "HEAD":
            default:
                return Items.headFromBase64(settings.ballHeadTexture);
        }
    }

    // ------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------

    /**
     * Advance the ball one tick. Returns silently if the ball is frozen
     * (e.g. during kickoff countdown) or not spawned.
     */
    public void tick() {
        if (!isSpawned() || location == null) {
            return;
        }
        if (frozen) {
            render();
            return;
        }

        previousTickLocation = location.clone();
        blockCollisionThisTick = false;

        boolean airborne = !detectGround();

        // 1. Gravity (constant downward acceleration) -> the ball always wants
        //    to fall, which is what produces the parabolic arc of a struck ball.
        velocity.setY(velocity.getY() - settings.gravity);

        if (airborne) {
            // 2. Magnus effect: spin crossed with velocity produces a force
            //    perpendicular to travel. Side-spin curves the ball left/right;
            //    back-spin lifts it (a floaty, dipping shot) -- both feel real.
            if (spin.lengthSquared() > 1.0e-6) {
                Vector magnus = spin.clone().crossProduct(velocity)
                        .multiply(settings.magnusStrength);
                velocity.add(magnus);
            }

            // 3. Aerodynamic drag. Real air resistance grows with the square of
            //    speed, so we combine a light linear retention term with a
            //    quadratic term: fast balls shed speed quickly, slow balls glide.
            double speed = velocity.length();
            if (speed > 1.0e-6) {
                double factor = settings.airResistance
                        * (1.0 - settings.dragCoefficient * speed);
                if (factor < 0.0) {
                    factor = 0.0;
                }
                velocity.multiply(factor);
            }
        }

        // 4. Safety clamp on top speed.
        double speed = velocity.length();
        if (speed > settings.maxSpeed) {
            velocity.multiply(settings.maxSpeed / speed);
        }

        // 5. Integrate position, resolving wall and ground collisions (bouncing).
        moveWithCollision();

        // 6. Rolling phase: once the ball is on the ground it loses energy to
        //    rolling resistance and eventually comes to rest.
        onGround = detectGround();
        if (onGround) {
            if (velocity.getY() < 0) {
                velocity.setY(0);
            }
            // Snap the ball to rest exactly on the surface it's sitting on, so it
            // never ends a tick partially embedded in (or hovering above) a block.
            snapToSurface();
            velocity.setX(velocity.getX() * settings.groundFriction);
            velocity.setZ(velocity.getZ() * settings.groundFriction);
            double horiz = Math.hypot(velocity.getX(), velocity.getZ());
            if (horiz < settings.restThreshold) {
                velocity.setX(0);
                velocity.setZ(0);
            }
        }

        // 7. Spin bleeds off over time.
        spin.multiply(settings.spinDecay);

        render();
    }

    /**
     * Move the ball by its velocity this tick, resolving block collisions one
     * axis at a time. Movement is broken into sub-steps so high-speed shots
     * cannot pass through thin walls.
     */
    private void moveWithCollision() {
        if (velocity.getY() <= 0.001 && detectGround()) {
            snapToSurface();
        }

        double speed = velocity.length();
        if (speed <= 1.0e-6) {
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(speed / 0.2));
        double invSteps = 1.0 / steps;

        for (int i = 0; i < steps; i++) {
            // Horizontal walls reflect with restitution.
            if (tryAxis(0, velocity.getX() * invSteps)) {
                velocity.setX(velocity.getX() * -settings.restitution);
                dampSpinOnBounce();
            }
            if (tryAxis(2, velocity.getZ() * invSteps)) {
                velocity.setZ(velocity.getZ() * -settings.restitution);
                dampSpinOnBounce();
            }
            // Vertical: floor/ceiling bounce.
            double vy = velocity.getY();
            if (tryAxis(1, vy * invSteps)) {
                if (vy < 0) {
                    // Landed on the ground.
                    if (Math.abs(vy) < settings.bounceThreshold) {
                        // Too slow to bounce again -> settle and start rolling.
                        velocity.setY(0);
                    } else {
                        double restitution = dampenNextGroundBounce
                                ? Math.min(settings.restitution, 0.28)
                                : settings.restitution;
                        dampenNextGroundBounce = false;
                        // Bounce: keep a fraction of the impact speed, and scrub
                        // some horizontal speed off against the turf each bounce
                        // so successive bounces are lower and shorter (realistic).
                        velocity.setY(-vy * restitution);
                        velocity.setX(velocity.getX() * settings.bounceFriction);
                        velocity.setZ(velocity.getZ() * settings.bounceFriction);
                    }
                } else {
                    // Hit a ceiling.
                    velocity.setY(-vy * settings.restitution);
                }
                dampSpinOnBounce();
            }
        }
    }

    /**
     * Attempt to move along a single axis. Returns true if a solid block blocked
     * the movement (the caller then reflects that velocity component).
     *
     * @param axis 0 = X, 1 = Y, 2 = Z
     */
    private boolean tryAxis(int axis, double amount) {
        if (amount == 0) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double leadOffset = amount + Math.signum(amount) * RADIUS;

        double px = x, py = y, pz = z;
        switch (axis) {
            case 0: px = x + leadOffset; break;
            case 1: py = y + leadOffset; break;
            default: pz = z + leadOffset; break;
        }

        if (isBlockingAt(px, py, pz, axis)) {
            blockCollisionThisTick = true;
            return true;
        }

        switch (axis) {
            case 0: location.setX(x + amount); break;
            case 1: location.setY(y + amount); break;
            default: location.setZ(z + amount); break;
        }
        return false;
    }

    private void dampSpinOnBounce() {
        spin.multiply(0.6);
    }

    private boolean detectGround() {
        Block below = blockAt(location.getX(), location.getY() - RADIUS - 0.04, location.getZ());
        return isSolid(below);
    }

    /**
     * Rest the ball's center exactly one radius above the top of the solid block
     * it's standing on, but only when it's already at or below that height (so we
     * pull it out of the floor, never lift it off a ledge while moving).
     */
    private void snapToSurface() {
        int blockY = (int) Math.floor(location.getY() - RADIUS - 0.04);
        double surfaceTop = blockY + 1.0;
        double restY = surfaceTop + RADIUS;
        if (location.getY() < restY) {
            location.setY(restY);
        }
    }

    private Block blockAt(double x, double y, double z) {
        return world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private boolean isBlockingAt(double x, double y, double z, int axis) {
        Block block = blockAt(x, y, z);
        if (isSolid(block) && occupiesBlock(block, y)) {
            return true;
        }
        // Horizontal moves: also check mid-height so low servers don't treat turf as a wall.
        if (axis != 1) {
            Block mid = blockAt(x, y + RADIUS * 0.35, z);
            if (isSolid(mid) && occupiesBlock(mid, y + RADIUS * 0.35)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code y} is still inside the block's 1-block-tall column (not
     * standing in the air above its top face). Prevents the turf under the ball
     * from blocking horizontal rolls on older servers.
     */
    private boolean occupiesBlock(Block block, double y) {
        double top = block.getY() + 1.0;
        return y < top - 0.02;
    }

    private boolean isSolid(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (!type.isSolid()) {
            return false;
        }
        return !block.isPassable();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render() {
        if (display == null) {
            return;
        }
        // Accumulate a rolling rotation proportional to horizontal travel.
        double dx = velocity.getX();
        double dz = velocity.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz > 1.0e-4) {
            Vector flat = new Vector(dx, 0, dz);
            Vector axis = new Vector(0, 1, 0).crossProduct(flat);
            if (axis.lengthSquared() > 1.0e-9) {
                axis.normalize();
                float angle = (float) (horiz / RADIUS);
                Quaternionf delta = new Quaternionf(new AxisAngle4f(
                        angle, (float) axis.getX(), (float) axis.getY(), (float) axis.getZ()));
                delta.mul(rotation);
                rotation.set(delta);
                display.setTransformation(buildTransformation());
            }
        }
        display.teleport(renderLocation());
    }

    private Transformation buildTransformation() {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new Quaternionf(rotation),
                new Vector3f(VISUAL_SCALE, VISUAL_SCALE, VISUAL_SCALE),
                new Quaternionf());
    }

    private Location renderLocation() {
        // An ItemDisplay renders the item's visual center at its entity location.
        // The physics keeps the ball's center at RADIUS above the ground, and the
        // visual ball's half-height is VISUAL_SCALE/2, which equals RADIUS, so the
        // display location should match the physical center directly. RENDER_Y_OFFSET
        // is a small nudge to fine-tune the player-head pivot if needed.
        return new Location(world,
                location.getX(),
                location.getY() + RENDER_Y_OFFSET,
                location.getZ());
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    /**
     * Apply an impulse to the ball (used by kicks, passes, tackles, deflections).
     *
     * @param impulse the velocity to add (blocks/tick)
     * @param spinAdd additional spin to impart (curve), may be null
     */
    public void applyImpulse(Vector impulse, Vector spinAdd) {
        if (impulse != null) {
            velocity.add(impulse);
            // Ground passes (vertical ~ 0): lift onto the turf so horizontal motion
            // is not blocked by the block below on older versions / hitboxes.
            if (Math.abs(impulse.getY()) < 1.0e-4) {
                double horiz = Math.hypot(impulse.getX(), impulse.getZ());
                if (horiz > 1.0e-4) {
                    snapToSurface();
                    location.setY(location.getY() + 0.04);
                    velocity.setY(0);
                }
            }
        }
        if (spinAdd != null) {
            spin.add(spinAdd);
        }
        frozen = false;
    }

    /**
     * Replaces the ball's velocity for a clean strike (pass / cross / loft / shot).
     */
    public void applyKick(Vector impulse, Vector spinAdd, boolean dampenGroundBounce) {
        dampenNextGroundBounce = dampenGroundBounce;
        if (impulse != null) {
            velocity.setX(impulse.getX());
            velocity.setY(impulse.getY());
            velocity.setZ(impulse.getZ());
        }
        if (spinAdd != null) {
            spin.setX(spinAdd.getX());
            spin.setY(spinAdd.getY());
            spin.setZ(spinAdd.getZ());
        } else {
            spin.setX(0);
            spin.setY(0);
            spin.setZ(0);
        }
        frozen = false;
    }

    public void recordTouch(Player player, TeamSide team) {
        recordTouch(player.getUniqueId(), team);
    }

    public void recordTouch(UUID id, TeamSide team) {
        if (lastTouchId != null && !lastTouchId.equals(id)) {
            prevTouchId = lastTouchId;
            prevTouchTeam = lastTouchTeam;
        }
        lastTouchId = id;
        lastTouchTeam = team;
        lastTouchTick = world != null ? world.getFullTime() : 0;
    }

    public void teleport(Location loc) {
        this.location = loc.clone();
        this.previousTickLocation = loc.clone();
        this.velocity.zero();
        this.spin.zero();
        rotation.identity();
        if (display != null) {
            display.setTransformation(buildTransformation());
            display.teleport(renderLocation());
        }
    }

    public void clearTouchHistory() {
        lastTouchId = null;
        lastTouchTeam = null;
        prevTouchId = null;
        prevTouchTeam = null;
        lastTouchTick = -1;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    public Location getPreviousTickLocation() {
        return previousTickLocation == null ? null : previousTickLocation.clone();
    }

    public Vector getVelocity() {
        return velocity.clone();
    }

    public double getSpeed() {
        return velocity.length();
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        if (frozen) {
            velocity.zero();
            spin.zero();
        }
    }

    /** Stops all linear and spin motion without freezing simulation (trap at feet). */
    public void haltMotion() {
        velocity.zero();
        spin.zero();
        frozen = false;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public UUID getLastTouchId() {
        return lastTouchId;
    }

    public TeamSide getLastTouchTeam() {
        return lastTouchTeam;
    }

    public UUID getPrevTouchId() {
        return prevTouchId;
    }

    public TeamSide getPrevTouchTeam() {
        return prevTouchTeam;
    }

    public ItemDisplay getEntity() {
        return display;
    }

    public boolean hadBlockCollisionThisTick() {
        return blockCollisionThisTick;
    }

    public void suppressGoalScoringUntil(long untilTick) {
        suppressGoalUntilTick = Math.max(suppressGoalUntilTick, untilTick);
    }

    public boolean isGoalScoringSuppressed(long currentTick) {
        return suppressGoalUntilTick >= currentTick;
    }
}
