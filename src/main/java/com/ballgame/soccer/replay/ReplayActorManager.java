package com.ballgame.soccer.replay;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns and drives on-pitch replay bodies while real players watch from cameras.
 */
public final class ReplayActorManager {

    private final SoccerPlugin plugin;
    private final Map<UUID, ReplayActor> actors = new HashMap<>();
    private Collection<Player> viewers = List.of();

    public ReplayActorManager(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void setViewers(Collection<Player> viewers) {
        this.viewers = viewers;
    }

    public void spawn(Game game, ReplayFrame initialFrame, World world) {
        removeAll();
        for (SoccerPlayer sp : game.getParticipants()) {
            ReplayActor actor = ReplayActorFactory.create(plugin, sp, plugin.settings());
            PlayerPose pose = initialFrame.players.get(sp.getUuid());
            Location spawn = pose != null
                    ? pose.toLocation(world)
                    : sp.getBukkitPlayer().getLocation();
            actor.spawn(spawn);
            actors.put(sp.getUuid(), actor);
        }
    }

    public void applyFrame(ReplayFrame frame, World world) {
        for (Map.Entry<UUID, PlayerPose> entry : frame.players.entrySet()) {
            ReplayActor actor = actors.get(entry.getKey());
            if (actor != null) {
                actor.applyPose(entry.getValue(), world);
                if (entry.getValue().armSwing) {
                    actor.playSwingAnimation(viewers);
                }
            }
        }
    }

    public void setScorerGlow(UUID scorerId, boolean glowing) {
        ReplayActor actor = actors.get(scorerId);
        if (actor != null) {
            actor.setGlowing(glowing);
        }
    }

    public void removeAll() {
        for (ReplayActor actor : actors.values()) {
            actor.remove();
        }
        actors.clear();
    }
}
