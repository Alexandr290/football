package com.ballgame.soccer.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/**
 * Sends action-bar (above hotbar) messages using the bundled BungeeCord chat
 * components shipped with the Spigot API, plus a helper for rendering progress
 * bars used by the stamina / shot-power HUD.
 */
public final class ActionBar {

    private ActionBar() {
    }

    public static void send(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(Text.color(message)));
    }

    /**
     * Render a textual progress bar, e.g. {@code ||||||----} sized to {@code length}.
     *
     * @param fraction   0..1 fill level
     * @param length     number of segments
     * @param filledColor color code for filled portion (e.g. "&a")
     * @param emptyColor  color code for empty portion (e.g. "&7")
     */
    public static String bar(double fraction, int length, String filledColor, String emptyColor) {
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        int filled = (int) Math.round(fraction * length);
        StringBuilder sb = new StringBuilder();
        sb.append(filledColor);
        for (int i = 0; i < filled; i++) {
            sb.append('|');
        }
        sb.append(emptyColor);
        for (int i = filled; i < length; i++) {
            sb.append('|');
        }
        return sb.toString();
    }
}
