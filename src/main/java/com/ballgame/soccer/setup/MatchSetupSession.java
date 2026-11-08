package com.ballgame.soccer.setup;

import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.TeamSide;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable pre-match lobby owned by the player who pressed "Start match". */
public final class MatchSetupSession {

    public enum Mode {
        BOTS_VS_BOTS("Боты против ботов"),
        MIXED("Игроки с ботами"),
        PLAYERS_VS_PLAYERS("Игроки против игроков");

        private final String title;
        Mode(String title) { this.title = title; }
        public String title() { return title; }
    }

    public enum Weather {
        CLEAR("Солнечно"), RAIN("Дождь");
        private final String title;
        Weather(String title) { this.title = title; }
        public String title() { return title; }
        public Weather next() { return this == CLEAR ? RAIN : CLEAR; }
    }

    public enum TimeOfDay {
        DAY("День", 6000L), EVENING("Вечер", 12000L), NIGHT("Ночь", 18000L);
        private final String title;
        private final long ticks;
        TimeOfDay(String title, long ticks) { this.title = title; this.ticks = ticks; }
        public String title() { return title; }
        public long ticks() { return ticks; }
        public TimeOfDay next() {
            return switch (this) {
                case DAY -> EVENING;
                case EVENING -> NIGHT;
                case NIGHT -> DAY;
            };
        }
    }

    public record Claim(TeamSide team, int slot) {
    }

    private final UUID id = UUID.randomUUID();
    private final UUID ownerId;
    private final long createdAt = System.currentTimeMillis();
    private Mode mode;
    private String redClubCode;
    private String blueClubCode;
    private int redSize = 11;
    private int blueSize = 11;
    private String arenaName;
    private Weather weather = Weather.CLEAR;
    private TimeOfDay timeOfDay = TimeOfDay.DAY;
    private int durationSeconds = 300;
    private BotDifficulty redDifficulty = BotDifficulty.MEDIUM;
    private BotDifficulty blueDifficulty = BotDifficulty.MEDIUM;
    private final Set<UUID> invited = new LinkedHashSet<>();
    private final Set<UUID> accepted = new LinkedHashSet<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();

    public MatchSetupSession(UUID ownerId) {
        this.ownerId = ownerId;
        accepted.add(ownerId);
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public long getCreatedAt() { return createdAt; }
    public boolean isOwner(UUID playerId) { return ownerId.equals(playerId); }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getRedClubCode() { return redClubCode; }
    public void setRedClubCode(String code) { redClubCode = code; }
    public String getBlueClubCode() { return blueClubCode; }
    public void setBlueClubCode(String code) { blueClubCode = code; }
    public int getRedSize() { return redSize; }
    public void setRedSize(int value) { redSize = clampSize(value); removeOutOfRange(TeamSide.RED); }
    public int getBlueSize() { return blueSize; }
    public void setBlueSize(int value) { blueSize = clampSize(value); removeOutOfRange(TeamSide.BLUE); }
    public int size(TeamSide side) { return side == TeamSide.RED ? redSize : blueSize; }
    public String getArenaName() { return arenaName; }
    public void setArenaName(String arenaName) { this.arenaName = arenaName; }
    public Weather getWeather() { return weather; }
    public void setWeather(Weather weather) { this.weather = weather; }
    public TimeOfDay getTimeOfDay() { return timeOfDay; }
    public void setTimeOfDay(TimeOfDay timeOfDay) { this.timeOfDay = timeOfDay; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int seconds) { durationSeconds = Math.max(60, Math.min(1800, seconds)); }
    public BotDifficulty getRedDifficulty() { return redDifficulty; }
    public void setRedDifficulty(BotDifficulty difficulty) { redDifficulty = difficulty; }
    public BotDifficulty getBlueDifficulty() { return blueDifficulty; }
    public void setBlueDifficulty(BotDifficulty difficulty) { blueDifficulty = difficulty; }
    public Set<UUID> getInvited() { return Set.copyOf(invited); }
    public Set<UUID> getAccepted() { return Set.copyOf(accepted); }
    public Map<UUID, Claim> getClaims() { return Map.copyOf(claims); }
    public Claim claimOf(UUID playerId) { return claims.get(playerId); }

    public void invite(UUID playerId) {
        if (playerId != null && !ownerId.equals(playerId)) {
            invited.add(playerId);
        }
    }

    public boolean accept(UUID playerId) {
        if (!invited.contains(playerId)) {
            return false;
        }
        accepted.add(playerId);
        return true;
    }

    public void removePlayer(UUID playerId) {
        if (!ownerId.equals(playerId)) {
            invited.remove(playerId);
            accepted.remove(playerId);
            claims.remove(playerId);
        }
    }

    public boolean claim(UUID playerId, TeamSide side, int slot) {
        if (!accepted.contains(playerId) || side == null || slot < 0 || slot >= size(side)) {
            return false;
        }
        for (Map.Entry<UUID, Claim> entry : claims.entrySet()) {
            if (!entry.getKey().equals(playerId)
                    && entry.getValue().team() == side && entry.getValue().slot() == slot) {
                return false;
            }
        }
        claims.put(playerId, new Claim(side, slot));
        return true;
    }

    public UUID occupant(TeamSide side, int slot) {
        for (Map.Entry<UUID, Claim> entry : claims.entrySet()) {
            if (entry.getValue().team() == side && entry.getValue().slot() == slot) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean readyToConfigure() {
        return mode != null && redClubCode != null && blueClubCode != null;
    }

    public boolean readyToLaunch() {
        return readyToConfigure() && arenaName != null;
    }

    private void removeOutOfRange(TeamSide side) {
        int size = size(side);
        claims.entrySet().removeIf(e -> e.getValue().team() == side && e.getValue().slot() >= size);
    }

    private static int clampSize(int value) {
        return Math.max(1, Math.min(11, value));
    }
}
