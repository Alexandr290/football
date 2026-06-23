package com.ballgame.soccer.network;

import com.ballgame.soccer.SoccerPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Temporary join intents keyed by player UUID until they arrive on the game server. */
final class PendingJoinStore {

    private final SoccerPlugin plugin;
    private final Map<UUID, NetworkJoinRequest> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> expiryTaskIds = new ConcurrentHashMap<>();

    PendingJoinStore(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    void store(NetworkJoinRequest request, int timeoutSeconds) {
        UUID id = request.playerId();
        cancelExpiry(id);
        pending.put(id, request);
        long ticks = Math.max(20L, timeoutSeconds * 20L);
        int taskId = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            expiryTaskIds.remove(id);
        }, ticks).getTaskId();
        expiryTaskIds.put(id, taskId);
    }

    NetworkJoinRequest take(UUID playerId) {
        cancelExpiry(playerId);
        return pending.remove(playerId);
    }

    void clear() {
        for (Integer taskId : expiryTaskIds.values()) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        expiryTaskIds.clear();
        pending.clear();
    }

    private void cancelExpiry(UUID playerId) {
        Integer taskId = expiryTaskIds.remove(playerId);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
    }
}
