package com.ballgame.soccer.championship;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ChampTeam {

    private final String id;
    private String name;
    private UUID captain;
    private final List<UUID> members = new ArrayList<>();
    private boolean eliminated;
    private String groupId;

    public ChampTeam(String id, String name, UUID captain) {
        this.id = id;
        this.name = name;
        this.captain = captain;
        if (captain != null) {
            members.add(captain);
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getCaptain() {
        return captain;
    }

    public void setCaptain(UUID captain) {
        this.captain = captain;
    }

    public List<UUID> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public boolean addMember(UUID uuid) {
        return members.add(uuid);
    }

    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    public boolean contains(UUID uuid) {
        return members.contains(uuid);
    }

    public int size() {
        return members.size();
    }

    public boolean isEliminated() {
        return eliminated;
    }

    public void setEliminated(boolean eliminated) {
        this.eliminated = eliminated;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}
