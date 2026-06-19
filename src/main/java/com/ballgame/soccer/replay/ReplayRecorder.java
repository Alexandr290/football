package com.ballgame.soccer.replay;

import com.ballgame.soccer.ball.Ball;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rolling buffer of recent match frames used to build goal-replay clips.
 */
public final class ReplayRecorder {

    private final Deque<ReplayFrame> buffer = new ArrayDeque<>();
    private final int maxFrames;

    public ReplayRecorder(int maxFrames) {
        this.maxFrames = Math.max(20, maxFrames);
    }

    public void clear() {
        buffer.clear();
    }

    public void capture(Game game) {
        Ball ball = game.getBall();
        if (ball == null || ball.getLocation() == null) {
            return;
        }
        Location ballLoc = ball.getLocation();
        Map<UUID, PlayerPose> poses = new HashMap<>();
        ReplayAnimationTracker tracker = game.getReplayAnimations();
        for (SoccerPlayer sp : game.getParticipants()) {
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                poses.put(player.getUniqueId(),
                        PlayerPose.capture(player, tracker != null ? tracker : new ReplayAnimationTracker()));
            }
        }
        buffer.addLast(new ReplayFrame(game.getMatchTick(), ballLoc, poses));
        while (buffer.size() > maxFrames) {
            buffer.removeFirst();
        }
    }

    public boolean hasEnoughFrames() {
        return buffer.size() >= 20;
    }

    /**
     * Returns the last {@code clipSeconds} of recorded footage (at 20 ticks/sec).
     */
    public List<ReplayFrame> extractClip(int clipSeconds) {
        int clipFrames = Math.max(20, clipSeconds * 20);
        List<ReplayFrame> all = new ArrayList<>(buffer);
        if (all.size() <= clipFrames) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - clipFrames, all.size()));
    }
}
