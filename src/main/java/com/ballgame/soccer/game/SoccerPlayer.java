package com.ballgame.soccer.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Per-match state for a participating player: their team, live stamina, shot
 * charge progress, tackle cooldown and in-match statistics.
 */
public final class SoccerPlayer {

    private final UUID uuid;
    private final String name;
    private TeamSide team;

    private double stamina;
    private boolean charging;
    private long chargeStartTick = -1;
    private long tackleReadyTick = 0;

    // in-match stats
    private int goals;
    private int assists;
    private int ownGoals;
    private int touches;
    private int tackles;
    private int shots;
    private int passes;
    private int saves;

    public SoccerPlayer(Player player, TeamSide team, double startingStamina) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.team = team;
        this.stamina = startingStamina;
    }

    public Player getBukkitPlayer() {
        return Bukkit.getPlayer(uuid);
    }

    public boolean isOnline() {
        Player p = getBukkitPlayer();
        return p != null && p.isOnline();
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public TeamSide getTeam() {
        return team;
    }

    public void setTeam(TeamSide team) {
        this.team = team;
    }

    // --- stamina ---

    public double getStamina() {
        return stamina;
    }

    public void setStamina(double stamina) {
        this.stamina = stamina;
    }

    public void drainStamina(double amount, double min) {
        this.stamina = Math.max(min, this.stamina - amount);
    }

    public void regenStamina(double amount, double max) {
        this.stamina = Math.min(max, this.stamina + amount);
    }

    // --- charging ---

    public boolean isCharging() {
        return charging;
    }

    public void startCharging(long currentTick) {
        this.charging = true;
        this.chargeStartTick = currentTick;
    }

    public void stopCharging() {
        this.charging = false;
        this.chargeStartTick = -1;
    }

    public long getChargeStartTick() {
        return chargeStartTick;
    }

    /**
     * Fraction (0..1) of charge accumulated given the current tick and the
     * configured charge time in ticks.
     */
    public double chargeFraction(long currentTick, double chargeTicks) {
        if (!charging || chargeStartTick < 0 || chargeTicks <= 0) {
            return 0.0;
        }
        double held = currentTick - chargeStartTick;
        return Math.max(0.0, Math.min(1.0, held / chargeTicks));
    }

    // --- tackle cooldown ---

    public boolean canTackle(long currentTick) {
        return currentTick >= tackleReadyTick;
    }

    public void setTackleCooldown(long currentTick, long cooldownTicks) {
        this.tackleReadyTick = currentTick + cooldownTicks;
    }

    public long getTackleReadyTick() {
        return tackleReadyTick;
    }

    // --- stats ---

    public int getGoals() { return goals; }
    public void addGoal() { this.goals++; }

    public int getAssists() { return assists; }
    public void addAssist() { this.assists++; }

    public int getOwnGoals() { return ownGoals; }
    public void addOwnGoal() { this.ownGoals++; }

    public int getTouches() { return touches; }
    public void addTouch() { this.touches++; }

    public int getTackles() { return tackles; }
    public void addTackle() { this.tackles++; }

    public int getShots() { return shots; }
    public void addShot() { this.shots++; }

    public int getPasses() { return passes; }
    public void addPass() { this.passes++; }

    public int getSaves() { return saves; }
    public void addSave() { this.saves++; }
}
