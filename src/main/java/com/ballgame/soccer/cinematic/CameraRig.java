package com.ballgame.soccer.cinematic;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Broadcast camera for cutscenes. Viewers are put in spectator mode and attached
 * to an invisible display entity; every move of that entity is interpolated by
 * the client (display teleport smoothing), so camera motion is fluid instead of
 * the stutter of teleporting the player each tick.
 *
 * <p>Two camera bodies alternate: for a hard cut the idle body is parked on the
 * new shot first and viewers are switched to it a couple of ticks later, so the
 * cut is instant rather than a smeared fly-through.</p>
 */
public final class CameraRig {

    /** Ticks between parking the standby body and switching viewers to it. */
    private static final int CUT_LEAD_TICKS = 2;
    /** Ticks after a teleport before the client reliably knows the camera entity. */
    private static final int ATTACH_DELAY_TICKS = 3;
    /** Viewers further than this from the camera are moved next to it before attaching. */
    private static final double REATTACH_TELEPORT_DIST_SQ = 64.0 * 64.0;
    /** Beyond this the standby body may not be tracked by clients — cut with one body. */
    private static final double TWO_BODY_CUT_MAX_DIST_SQ = 64.0 * 64.0;

    private final SoccerPlugin plugin;
    private final NamespacedKey cameraKey;
    private final int smoothingTicks;
    private final ItemDisplay[] bodies = new ItemDisplay[2];
    private final Map<UUID, Viewer> viewers = new LinkedHashMap<>();

    private int active;
    private long tick;
    private boolean smoothingSupported = true;

    private boolean cutting;
    private boolean singleBodyCut;
    private long switchAtTick;
    private Location pendingPose;
    private long restoreSmoothingAt = -1;
    private Location lastPose;

    public CameraRig(SoccerPlugin plugin, int smoothingTicks) {
        this.plugin = plugin;
        this.cameraKey = key(plugin);
        this.smoothingTicks = Math.max(1, Math.min(6, smoothingTicks));
    }

    public static NamespacedKey key(SoccerPlugin plugin) {
        return new NamespacedKey(plugin, "cinematic_camera");
    }

    /** Removes camera bodies left behind by a crash or an unclean reload. */
    public static int cleanupOrphans(SoccerPlugin plugin) {
        NamespacedKey key = key(plugin);
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                    display.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public boolean start(Location pose) {
        if (pose == null || pose.getWorld() == null) {
            return false;
        }
        Location safe = clearOfBlocks(pose);
        bodies[0] = spawnBody(safe);
        bodies[1] = spawnBody(safe);
        if (bodies[0] == null || bodies[1] == null) {
            stop();
            return false;
        }
        active = 0;
        applySmoothing(bodies[0], smoothingTicks);
        applySmoothing(bodies[1], 0);
        lastPose = safe.clone();
        return true;
    }

    private ItemDisplay spawnBody(Location at) {
        try {
            return at.getWorld().spawn(at, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setInvulnerable(true);
                display.setGravity(false);
                display.setSilent(true);
                display.getPersistentDataContainer().set(cameraKey, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not spawn cinematic camera: " + ex.getMessage());
            return null;
        }
    }

    private void applySmoothing(ItemDisplay body, int ticks) {
        if (body == null || !smoothingSupported) {
            return;
        }
        try {
            body.setTeleportDuration(ticks);
        } catch (NoSuchMethodError | UnsupportedOperationException ex) {
            // Pre-1.20.2 servers: camera still works, just without client smoothing.
            smoothingSupported = false;
        }
    }

    // ------------------------------------------------------------------
    // Viewers
    // ------------------------------------------------------------------

    /**
     * @param locked locked viewers are re-attached if they slip off the camera
     *               (sneak); unlocked ones (server spectators) are released instead
     */
    public void addViewer(Player player, boolean locked) {
        if (player == null || !player.isOnline() || viewers.containsKey(player.getUniqueId())) {
            return;
        }
        ItemDisplay body = activeBody();
        if (body == null) {
            return;
        }
        Viewer viewer = new Viewer(player, locked);
        viewers.put(player.getUniqueId(), viewer);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(body.getLocation());
        viewer.attachAt = tick + ATTACH_DELAY_TICKS;
    }

    public void removeViewer(Player player, boolean restore) {
        if (player == null) {
            return;
        }
        Viewer viewer = viewers.remove(player.getUniqueId());
        if (viewer != null) {
            release(player, viewer, restore);
        }
    }

    public boolean hasViewer(UUID id) {
        return viewers.containsKey(id);
    }

    public boolean isLockedViewer(UUID id) {
        Viewer viewer = viewers.get(id);
        return viewer != null && viewer.locked;
    }

    public int lockedViewerCount() {
        int count = 0;
        for (Viewer viewer : viewers.values()) {
            if (viewer.locked) {
                count++;
            }
        }
        return count;
    }

    public List<Player> onlineViewers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : viewers.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                list.add(player);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Camera motion
    // ------------------------------------------------------------------

    /**
     * Feeds the camera pose for this tick. {@code cut = true} starts a new shot:
     * the switch becomes visible {@value #CUT_LEAD_TICKS} ticks later.
     */
    public void frame(Location pose, boolean cut) {
        if (pose == null || activeBody() == null) {
            return;
        }
        Location safe = clearOfBlocks(pose);
        if (cut && !cutting) {
            beginCut(safe);
        } else if (cutting) {
            pendingPose = safe;
            if (!singleBodyCut) {
                teleport(bodies[1 - active], safe);
            }
        } else {
            teleport(bodies[active], safe);
        }
        lastPose = safe.clone();
    }

    private void beginCut(Location pose) {
        cutting = true;
        pendingPose = pose;
        switchAtTick = tick + CUT_LEAD_TICKS;
        ItemDisplay current = bodies[active];
        singleBodyCut = !pose.getWorld().equals(current.getWorld())
                || current.getLocation().distanceSquared(pose) > TWO_BODY_CUT_MAX_DIST_SQ;
        if (singleBodyCut) {
            applySmoothing(current, 0);
        } else {
            teleport(bodies[1 - active], pose);
        }
    }

    private void finishCut() {
        cutting = false;
        if (singleBodyCut) {
            teleport(bodies[active], pendingPose);
            restoreSmoothingAt = tick + 1;
            return;
        }
        int next = 1 - active;
        applySmoothing(bodies[next], smoothingTicks);
        applySmoothing(bodies[active], 0);
        active = next;
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline() && player.getGameMode() == GameMode.SPECTATOR
                    && tick >= entry.getValue().attachAt) {
                player.setSpectatorTarget(bodies[active]);
                entry.getValue().attached = true;
            }
        }
    }

    public Location currentPose() {
        return lastPose != null ? lastPose.clone() : null;
    }

    public void tick() {
        tick++;
        if (cutting && tick >= switchAtTick) {
            finishCut();
        }
        if (restoreSmoothingAt >= 0 && tick >= restoreSmoothingAt) {
            restoreSmoothingAt = -1;
            applySmoothing(bodies[active], smoothingTicks);
        }
        ItemDisplay body = activeBody();
        if (body == null) {
            return;
        }
        for (Viewer viewer : new ArrayList<>(viewers.values())) {
            Player player = Bukkit.getPlayer(viewer.id);
            if (player == null || !player.isOnline()) {
                viewers.remove(viewer.id);
                continue;
            }
            if (tick < viewer.attachAt) {
                continue;
            }
            Entity target = player.getSpectatorTarget();
            if (target != null && target.getUniqueId().equals(body.getUniqueId())) {
                viewer.attached = true;
                continue;
            }
            if (viewer.attached && !viewer.locked) {
                viewers.remove(viewer.id);
                release(player, viewer, true);
                continue;
            }
            if (player.getGameMode() != GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            Location camera = body.getLocation();
            if (player.isSneaking()) {
                // Held sneak detaches every tick; re-attach once released.
                if (camera.getWorld().equals(player.getWorld())
                        && player.getLocation().distanceSquared(camera) > 36.0) {
                    player.teleport(camera);
                }
                continue;
            }
            if (!camera.getWorld().equals(player.getWorld())
                    || player.getLocation().distanceSquared(camera) > REATTACH_TELEPORT_DIST_SQ) {
                player.teleport(camera);
                viewer.attachAt = tick + ATTACH_DELAY_TICKS;
                continue;
            }
            player.setSpectatorTarget(body);
            viewer.attached = true;
        }
    }

    /** Releases every viewer (restoring their pre-show state) and removes the camera. */
    public void stop() {
        for (Viewer viewer : new ArrayList<>(viewers.values())) {
            Player player = Bukkit.getPlayer(viewer.id);
            if (player != null && player.isOnline()) {
                release(player, viewer, true);
            }
        }
        viewers.clear();
        for (int i = 0; i < bodies.length; i++) {
            if (bodies[i] != null && bodies[i].isValid()) {
                bodies[i].remove();
            }
            bodies[i] = null;
        }
    }

    private ItemDisplay activeBody() {
        ItemDisplay body = bodies[active];
        return body != null && body.isValid() ? body : null;
    }

    private static void teleport(ItemDisplay body, Location pose) {
        if (body != null && body.isValid() && pose != null) {
            body.teleport(pose);
        }
    }

    private static void release(Player player, Viewer viewer, boolean restore) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }
        if (!restore) {
            return;
        }
        if (viewer.returnLocation != null && viewer.returnLocation.getWorld() != null) {
            player.teleport(viewer.returnLocation);
        }
        player.setGameMode(viewer.gameMode);
        player.setAllowFlight(viewer.allowFlight);
        player.setFlying(viewer.flying && viewer.allowFlight);
        player.setFallDistance(0f);
    }

    /** Never park the lens inside a wall or the stands: lift it until the block is clear. */
    private static Location clearOfBlocks(Location pose) {
        Location safe = pose.clone();
        World world = safe.getWorld();
        if (world == null) {
            return safe;
        }
        for (int i = 0; i < 40; i++) {
            Block block = world.getBlockAt(safe);
            if (!block.getType().isOccluding()) {
                return safe;
            }
            safe.setY(Math.floor(safe.getY()) + 1.2);
        }
        return safe;
    }

    private static final class Viewer {
        private final UUID id;
        private final boolean locked;
        private final Location returnLocation;
        private final GameMode gameMode;
        private final boolean allowFlight;
        private final boolean flying;
        private long attachAt;
        private boolean attached;

        private Viewer(Player player, boolean locked) {
            this.id = player.getUniqueId();
            this.locked = locked;
            this.returnLocation = player.getLocation().clone();
            this.gameMode = player.getGameMode();
            this.allowFlight = player.getAllowFlight();
            this.flying = player.isFlying();
        }
    }
}
