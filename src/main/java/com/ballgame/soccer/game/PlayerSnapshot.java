package com.ballgame.soccer.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;

/**
 * Captures a player's full state when they enter a match so it can be perfectly
 * restored when they leave (inventory, location, game mode, health, hunger, xp).
 */
public final class PlayerSnapshot {

    private final ItemStack[] inventory;
    private final ItemStack[] armor;
    private final Location location;
    private final GameMode gameMode;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exp;
    private final int level;
    private final boolean allowFlight;
    private final boolean flying;
    private final Collection<PotionEffect> potionEffects;

    private PlayerSnapshot(Player player) {
        this.inventory = player.getInventory().getContents().clone();
        this.armor = player.getInventory().getArmorContents().clone();
        this.location = player.getLocation().clone();
        this.gameMode = player.getGameMode();
        double max = 20.0;
        if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            max = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        }
        this.health = Math.min(player.getHealth(), max);
        this.foodLevel = player.getFoodLevel();
        this.saturation = player.getSaturation();
        this.exp = player.getExp();
        this.level = player.getLevel();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
        this.potionEffects = player.getActivePotionEffects();
    }

    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(player);
    }

    public void restore(Player player) {
        player.getInventory().setContents(inventory);
        player.getInventory().setArmorContents(armor);
        player.setGameMode(gameMode);
        try {
            player.setHealth(Math.max(0.5, health));
        } catch (IllegalArgumentException ignored) {
            // health value out of range for current max-health; leave as-is.
        }
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExp(exp);
        player.setLevel(level);
        player.setAllowFlight(allowFlight);
        player.setFlying(flying && allowFlight);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.addPotionEffects(potionEffects);
        player.updateInventory();
        player.teleport(location);
    }

    public Location getLocation() {
        return location;
    }
}
