package com.ballgame.soccer.util;

import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Cross-version helpers for {@link PlayerInteractEvent} (1.8 through 1.21+).
 */
public final class InteractUtil {

    private InteractUtil() {
    }

    /**
     * True when the event is from the main hand (or when the server has no hand
     * API, as on very old versions).
     */
    public static boolean isMainHand(PlayerInteractEvent event) {
        try {
            EquipmentSlot hand = event.getHand();
            return hand == null || hand == EquipmentSlot.HAND;
        } catch (NoSuchMethodError ignored) {
            return true;
        }
    }
}
