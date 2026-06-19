package com.ballgame.soccer.replay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks short-lived player animations (arm swings, passes) so replay frames
 * can reproduce kick/pass poses on pitch actors.
 */
public final class ReplayAnimationTracker {

    public static final int ARM_SWING_WINDOW = 4;
    public static final int INTERACT_WINDOW = 3;

    private final Map<UUID, Integer> armSwingAge = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> interactAge = new ConcurrentHashMap<>();

    public void markArmSwing(UUID playerId) {
        armSwingAge.put(playerId, 0);
    }

    public void markInteract(UUID playerId) {
        interactAge.put(playerId, 0);
    }

    public void tick() {
        ageMap(armSwingAge, ARM_SWING_WINDOW);
        ageMap(interactAge, INTERACT_WINDOW);
    }

    public boolean isArmSwinging(UUID playerId) {
        return isActive(armSwingAge, playerId, ARM_SWING_WINDOW);
    }

    public boolean isInteracting(UUID playerId) {
        return isActive(interactAge, playerId, INTERACT_WINDOW);
    }

    public void clear() {
        armSwingAge.clear();
        interactAge.clear();
    }

    private static void ageMap(Map<UUID, Integer> map, int maxAge) {
        map.replaceAll((id, age) -> age + 1);
        map.entrySet().removeIf(entry -> entry.getValue() > maxAge);
    }

    private static boolean isActive(Map<UUID, Integer> map, UUID playerId, int maxAge) {
        Integer age = map.get(playerId);
        return age != null && age <= maxAge;
    }
}
