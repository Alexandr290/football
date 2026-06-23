package com.ballgame.soccer.network;

import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.game.TeamSide;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Cross-server join payload sent from a lobby backend to the game backend. */
public final class NetworkJoinRequest {

    public static final String CHANNEL = "advancedsoccer:join";

    private final UUID playerId;
    private final NetworkJoinAction action;
    private final QuickJoinMode mode;
    private final TeamSide team;
    private final int botCount;
    private final String arenaName;

    private NetworkJoinRequest(UUID playerId, NetworkJoinAction action,
                               QuickJoinMode mode, TeamSide team, int botCount,
                               String arenaName) {
        this.playerId = playerId;
        this.action = action;
        this.mode = mode;
        this.team = team;
        this.botCount = botCount;
        this.arenaName = arenaName;
    }

    public static NetworkJoinRequest openModeMenu(UUID playerId) {
        return new NetworkJoinRequest(playerId, NetworkJoinAction.OPEN_MODE_MENU,
                QuickJoinMode.TEAMS, null, 0, null);
    }

    public static NetworkJoinRequest joinTeams(UUID playerId, TeamSide team) {
        return new NetworkJoinRequest(playerId, NetworkJoinAction.JOIN,
                QuickJoinMode.TEAMS, team, 0, null);
    }

    public static NetworkJoinRequest joinSolo(UUID playerId, TeamSide team, int botCount) {
        return new NetworkJoinRequest(playerId, NetworkJoinAction.JOIN,
                QuickJoinMode.SOLO, team, botCount, null);
    }

    public static NetworkJoinRequest joinArena(UUID playerId, String arenaName, TeamSide team) {
        return new NetworkJoinRequest(playerId, NetworkJoinAction.JOIN_ARENA,
                QuickJoinMode.TEAMS, team, 0, arenaName);
    }

    public UUID playerId() {
        return playerId;
    }

    public NetworkJoinAction action() {
        return action;
    }

    public QuickJoinMode mode() {
        return mode;
    }

    public TeamSide team() {
        return team;
    }

    public int botCount() {
        return botCount;
    }

    public String arenaName() {
        return arenaName;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(playerId.toString());
        out.writeUTF(action.name());
        out.writeUTF(mode.name());
        out.writeUTF(team != null ? team.name() : "");
        out.writeInt(botCount);
        out.writeUTF(arenaName != null ? arenaName : "");
        return bytes.toByteArray();
    }

    public static NetworkJoinRequest decode(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        UUID playerId = UUID.fromString(in.readUTF());
        NetworkJoinAction action = NetworkJoinAction.valueOf(in.readUTF());
        QuickJoinMode mode = QuickJoinMode.valueOf(in.readUTF());
        String teamRaw = in.readUTF();
        TeamSide team = teamRaw.isEmpty() ? null : TeamSide.valueOf(teamRaw);
        int botCount = in.readInt();
        String arenaRaw = in.available() > 0 ? in.readUTF() : "";
        String arenaName = arenaRaw.isEmpty() ? null : arenaRaw;
        return new NetworkJoinRequest(playerId, action, mode, team, botCount, arenaName);
    }
}
