package com.ballgame.soccer.championship;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Championship {

    private final String id;
    private String displayName;
    private ChampionshipType type;
    private ChampionshipState state;
    private String arenaName;
    private int pointsWin;
    private int pointsDraw;
    private int pointsLoss;
    private long createdAt;
    private String championTeamId;
    private UUID creatorId;
    private boolean playoffsActive;
    /** Ordered FIFA nation codes for R16 World Cup bracket slots (size 16). */
    private final List<String> bracketNationCodes = new ArrayList<>();

    private final Map<String, ChampTeam> teams = new HashMap<>();
    private final Map<String, ChampStanding> standings = new HashMap<>();
    private final List<ChampFixture> fixtures = new ArrayList<>();

    public Championship(String id, String displayName, ChampionshipType type, String arenaName,
                        int pointsWin, int pointsDraw, int pointsLoss) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.state = ChampionshipState.REGISTRATION;
        this.arenaName = arenaName;
        this.pointsWin = pointsWin;
        this.pointsDraw = pointsDraw;
        this.pointsLoss = pointsLoss;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public ChampionshipType getType() {
        return type;
    }

    public ChampionshipState getState() {
        return state;
    }

    public void setState(ChampionshipState state) {
        this.state = state;
    }

    public String getArenaName() {
        return arenaName;
    }

    public void setArenaName(String arenaName) {
        this.arenaName = arenaName;
    }

    public int getPointsWin() {
        return pointsWin;
    }

    public int getPointsDraw() {
        return pointsDraw;
    }

    public int getPointsLoss() {
        return pointsLoss;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public boolean isCreator(UUID playerId) {
        return creatorId != null && playerId != null && creatorId.equals(playerId);
    }

    public String getChampionTeamId() {
        return championTeamId;
    }

    public void setChampionTeamId(String championTeamId) {
        this.championTeamId = championTeamId;
    }

    public boolean isPlayoffsActive() {
        return playoffsActive;
    }

    public void setPlayoffsActive(boolean playoffsActive) {
        this.playoffsActive = playoffsActive;
    }

    public List<String> getBracketNationCodes() {
        return Collections.unmodifiableList(bracketNationCodes);
    }

    public void setBracketNationCodes(List<String> codes) {
        bracketNationCodes.clear();
        if (codes != null) {
            bracketNationCodes.addAll(codes);
        }
    }

    public String getBracketNation(int slot) {
        if (slot < 0 || slot >= bracketNationCodes.size()) {
            return null;
        }
        return bracketNationCodes.get(slot);
    }

    public void setBracketNation(int slot, String nationCode) {
        while (bracketNationCodes.size() <= slot) {
            bracketNationCodes.add(null);
        }
        bracketNationCodes.set(slot, nationCode);
    }

    public void clearBracketNations() {
        bracketNationCodes.clear();
    }

    public int filledBracketSlots() {
        int count = 0;
        for (String code : bracketNationCodes) {
            if (code != null && !code.isBlank()) {
                count++;
            }
        }
        return count;
    }

    public boolean hasPlayoffFixtures() {
        for (ChampFixture fixture : fixtures) {
            if (fixture.getPlayoffStage() != null) {
                return true;
            }
        }
        return playoffsActive;
    }

    public List<ChampFixture> getPlayoffFixtures() {
        List<ChampFixture> list = new ArrayList<>();
        for (ChampFixture fixture : fixtures) {
            if (fixture.getPlayoffStage() != null) {
                list.add(fixture);
            }
        }
        return list;
    }

    public List<ChampFixture> getLeagueFixtures() {
        List<ChampFixture> list = new ArrayList<>();
        for (ChampFixture fixture : fixtures) {
            if (fixture.getPlayoffStage() == null) {
                list.add(fixture);
            }
        }
        return list;
    }

    public List<ChampFixture> getGroupFixtures() {
        List<ChampFixture> list = new ArrayList<>();
        for (ChampFixture fixture : fixtures) {
            if (fixture.getPlayoffStage() == PlayoffStage.GROUP) {
                list.add(fixture);
            }
        }
        return list;
    }

    public List<ChampFixture> getKnockoutFixtures() {
        List<ChampFixture> list = new ArrayList<>();
        for (ChampFixture fixture : fixtures) {
            if (fixture.getPlayoffStage() != null && fixture.getPlayoffStage().isKnockout()) {
                list.add(fixture);
            }
        }
        return list;
    }

    public boolean hasGroupStage() {
        return getFixtures().stream().anyMatch(f -> f.getPlayoffStage() == PlayoffStage.GROUP);
    }

    public boolean isGroupPhaseComplete() {
        for (ChampFixture fixture : fixtures) {
            if (fixture.getPlayoffStage() == PlayoffStage.GROUP
                    && fixture.hasBothSides()
                    && fixture.getState() != FixtureState.COMPLETED) {
                return false;
            }
        }
        return hasGroupStage();
    }

    public List<String> getGroupIds() {
        List<String> groups = new ArrayList<>();
        for (ChampTeam team : teams.values()) {
            if (team.getGroupId() != null && !groups.contains(team.getGroupId())) {
                groups.add(team.getGroupId());
            }
        }
        groups.sort(String::compareTo);
        return groups;
    }

    public List<ChampTeam> getTeamsInGroup(String groupId) {
        List<ChampTeam> list = new ArrayList<>();
        for (ChampTeam team : teams.values()) {
            if (groupId != null && groupId.equals(team.getGroupId())) {
                list.add(team);
            }
        }
        return list;
    }

    public Map<String, ChampTeam> getTeams() {
        return Collections.unmodifiableMap(teams);
    }

    public ChampTeam getTeam(String teamId) {
        return teams.get(teamId);
    }

    public void addTeam(ChampTeam team) {
        teams.put(team.getId(), team);
        if (type == ChampionshipType.LEAGUE || type == ChampionshipType.KNOCKOUT) {
            standings.putIfAbsent(team.getId(), new ChampStanding(team.getId()));
        }
    }

    public void removeTeam(String teamId) {
        teams.remove(teamId);
        standings.remove(teamId);
    }

    public ChampTeam findTeamOf(UUID playerId) {
        for (ChampTeam team : teams.values()) {
            if (team.contains(playerId)) {
                return team;
            }
        }
        return null;
    }

    public Map<String, ChampStanding> getStandings() {
        return Collections.unmodifiableMap(standings);
    }

    public ChampStanding getStanding(String teamId) {
        return standings.get(teamId);
    }

    public List<ChampFixture> getFixtures() {
        return Collections.unmodifiableList(fixtures);
    }

    public void addFixture(ChampFixture fixture) {
        fixtures.add(fixture);
    }

    public void clearFixtures() {
        fixtures.clear();
    }

    public void removeBracketFixtures() {
        fixtures.removeIf(fixture -> fixture.getPlayoffStage() != null || fixture.getRound() > 0);
    }

    public void putStanding(ChampStanding standing) {
        standings.put(standing.getTeamId(), standing);
    }

    public ChampFixture getFixture(String fixtureId) {
        for (ChampFixture fixture : fixtures) {
            if (fixture.getId().equals(fixtureId)) {
                return fixture;
            }
        }
        return null;
    }

    public List<ChampFixture> pendingFixturesFor(UUID playerId) {
        List<ChampFixture> list = new ArrayList<>();
        for (ChampFixture fixture : fixtures) {
            if (fixture.getState() == FixtureState.PENDING
                    && fixture.involvesPlayer(this, playerId)) {
                list.add(fixture);
            }
        }
        return list;
    }

    public int teamCount() {
        return teams.size();
    }

    public boolean isRegistrationOpen() {
        return state == ChampionshipState.REGISTRATION;
    }
}
