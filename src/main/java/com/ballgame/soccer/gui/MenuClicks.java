package com.ballgame.soccer.gui;

import java.util.HashMap;
import java.util.Map;

public final class MenuClicks {

    private static final Map<String, Map<Integer, Runnable>> REGISTRY = new HashMap<>();

    private MenuClicks() {
    }

    public static void register(String inventoryKey, int slot, Runnable action) {
        REGISTRY.computeIfAbsent(inventoryKey, k -> new HashMap<>()).put(slot, action);
    }

    public static Runnable get(String inventoryKey, int slot) {
        Map<Integer, Runnable> map = REGISTRY.get(inventoryKey);
        return map != null ? map.get(slot) : null;
    }

    public static void clear(String inventoryKey) {
        REGISTRY.remove(inventoryKey);
    }
}
