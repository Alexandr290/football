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
    private String displayOverride;
    private TeamSide team;
    private MatchRole role = MatchRole.PLAYER;
    private int yellowCards;
    private boolean sentOff;

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
    private int passesCompleted;
    private int passesReceived;
    private int saves;
    private int fouls;
    private long possessionTicks;

    public static SoccerPlayer createReferee(Player player, double startingStamina) {
        SoccerPlayer sp = new SoccerPlayer(player, null, startingStamina);
        sp.role = MatchRole.REFEREE;
        return sp;
    }

    public SoccerPlayer(Player player, TeamSide team, double startingStamina) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.team = team;
        this.stamina = startingStamina;
        this.bot = false;
    }

    public static SoccerPlayer createBot(UUID uuid, String name, TeamSide team, double startingStamina) {
        return new SoccerPlayer(uuid, name, team, startingStamina, true);
    }

    private SoccerPlayer(UUID uuid, String name, TeamSide team, double startingStamina, boolean bot) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
        this.stamina = startingStamina;
        this.bot = bot;
    }

    private final boolean bot;

    public Player getBukkitPlayer() {
        return Bukkit.getPlayer(uuid);
    }

    public boolean isOnline() {
        Player p = getBukkitPlayer();
        return p != null && p.isOnline();
    }

    public boolean isBot() {
        return bot;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return displayOverride != null && !displayOverride.isBlank() ? displayOverride : name;
    }

    public String getAccountName() {
        return name;
    }

    public void setDisplayOverride(String displayOverride) {
        this.displayOverride = displayOverride;
    }

    public TeamSide getTeam() {
        return team;
    }

    public MatchRole getRole() {
        return role;
    }

    public boolean isReferee() {
        return role == MatchRole.REFEREE;
    }

    public boolean isPlayer() {
        return role == MatchRole.PLAYER && !sentOff;
    }

    public boolean isSentOff() {
        return sentOff;
    }

    public void setSentOff(boolean sentOff) {
        this.sentOff = sentOff;
    }

    public int getYellowCards() {
        return yellowCards;
    }

    public void addYellowCard() {
        yellowCards++;
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

    public int getPassesCompleted() { return passesCompleted; }
    public void addPassCompleted() { this.passesCompleted++; }

    public int getPassesReceived() { return passesReceived; }
    public void addPassReceived() { this.passesReceived++; }

    public int getSaves() { return saves; }
    public void addSave() { this.saves++; }

    public int getFouls() { return fouls; }
    public void addFoul() { this.fouls++; }

    public long getPossessionTicks() { return possessionTicks; }
    public void addPossessionTick() { this.possessionTicks++; }

    /** Completed passes / attempted passes, 0–100. */
    public int passAccuracyPercent() {
        if (passes <= 0) {
            return 0;
        }
        return (int) Math.round((double) passesCompleted / passes * 100.0);
    }

    /** Match rating from 1.0 to 10.0 based on in-match performance. */
    public double matchRating(long totalPossessionTicks) {
        double rating = 5.0;
        rating += goals * 1.4;
        rating += assists * 0.7;
        rating += saves * 0.45;
        rating += shots * 0.15;
        rating += passAccuracyPercent() * 0.015;
        rating += passesReceived * 0.04;
        if (totalPossessionTicks > 0) {
            rating += (double) possessionTicks / totalPossessionTicks * 2.5;
        }
        rating -= fouls * 0.9;
        return Math.max(1.0, Math.min(10.0, rating));
    }
}
