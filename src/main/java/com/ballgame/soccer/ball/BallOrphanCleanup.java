package com.ballgame.soccer.ball;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Removes leftover ball {@link ItemDisplay} entities that were not cleaned up
 * when a match aborted (e.g. before disconnect-handling fixes).
 */
public final class BallOrphanCleanup {

    private BallOrphanCleanup() {
    }

    public static int cleanupAllArenas(SoccerPlugin plugin) {
        Set<UUID> active = activeBallEntityIds(plugin);
        int removed = 0;
        for (Arena arena : plugin.arenas().getArenas()) {
            removed += cleanupArena(plugin, arena, active);
        }
        return removed;
    }

    public static int cleanupArena(SoccerPlugin plugin, Arena arena) {
        return cleanupArena(plugin, arena, activeBallEntityIds(plugin));
    }

    private static int cleanupArena(SoccerPlugin plugin, Arena arena, Set<UUID> activeBallIds) {
        Cuboid field = arena.getField();
        if (field == null) {
            return 0;
        }
        World world = field.getWorld();
        if (world == null) {
            return 0;
        }
        Location center = field.getCenter();
        double halfX = field.getSizeX() / 2.0 + 4;
        double halfY = field.getSizeY() / 2.0 + 8;
        double halfZ = field.getSizeZ() / 2.0 + 4;

        int removed = 0;
        for (Entity entity : world.getNearbyEntities(center, halfX, halfY, halfZ)) {
            if (activeBallIds.contains(entity.getUniqueId())) {
                continue;
            }
            if (!Ball.isBallEntity(entity, plugin)) {
                continue;
            }
            Location loc = entity.getLocation();
            if (!isInPlayArea(field, loc)) {
                continue;
            }
            entity.remove();
            removed++;
        }
        return removed;
    }

    private static boolean isInPlayArea(Cuboid field, Location loc) {
        if (!field.containsHorizontally(loc.getX(), loc.getZ())) {
            return false;
        }
        double y = loc.getY();
        return y >= field.getMinY() - 2 && y <= field.getMaxY() + 12;
    }

    private static Set<UUID> activeBallEntityIds(SoccerPlugin plugin) {
        Set<UUID> ids = new HashSet<>();
        for (Game game : plugin.games().getActiveGames()) {
            Ball ball = game.getBall();
            if (ball == null || !ball.isSpawned()) {
                continue;
            }
            ItemDisplay entity = ball.getEntity();
            if (entity != null) {
                ids.add(entity.getUniqueId());
            }
        }
        return ids;
    }
}
