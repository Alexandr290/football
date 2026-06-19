package com.ballgame.soccer.replay;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One tick of match state captured for goal replays.
 */
public final class ReplayFrame {

    public final long matchTick;
    public final double ballX;
    public final double ballY;
    public final double ballZ;
    public final Map<UUID, PlayerPose> players;

    public ReplayFrame(long matchTick, Location ball, Map<UUID, PlayerPose> players) {
        this.matchTick = matchTick;
        this.ballX = ball.getX();
        this.ballY = ball.getY();
        this.ballZ = ball.getZ();
        this.players = Collections.unmodifiableMap(new HashMap<>(players));
    }

    public Location ballLocation(World world) {
        return new Location(world, ballX, ballY, ballZ);
    }

    public ReplayFrame lerp(ReplayFrame other, double t) {
        Map<UUID, PlayerPose> blended = new HashMap<>();
        for (UUID id : players.keySet()) {
            PlayerPose a = players.get(id);
            PlayerPose b = other.players.get(id);
            if (b != null) {
                blended.put(id, a.lerp(b, t));
            } else {
                blended.put(id, a);
            }
        }
        for (Map.Entry<UUID, PlayerPose> entry : other.players.entrySet()) {
            blended.putIfAbsent(entry.getKey(), entry.getValue());
        }
        Location ball = new Location(null,
                ballX + (other.ballX - ballX) * t,
                ballY + (other.ballY - ballY) * t,
                ballZ + (other.ballZ - ballZ) * t);
        return new ReplayFrame(matchTick, ball, blended);
    }
}
