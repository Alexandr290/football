package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

/**
 * Keeps matches fair and tidy: stops participants from taking damage, getting
 * hungry, breaking/placing blocks, dropping their kit, or interacting with the
 * ball display entity.
 */
public final class BallProtectionListener implements Listener {

    private final SoccerPlugin plugin;

    public BallProtectionListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.games().isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.games().isPlaying(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.games().isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.games().isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.games().isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        // Prevent rotating/equipping the ball's display entity.
        if (event.getRightClicked() instanceof org.bukkit.entity.ItemDisplay) {
            event.setCancelled(true);
        }
    }
}
