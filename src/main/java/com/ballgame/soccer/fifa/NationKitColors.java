package com.ballgame.soccer.fifa;

import org.bukkit.Color;
import org.bukkit.ChatColor;

/**
 * Resolves FIFA nation chat-color kit codes into leather armor {@link Color}s
 * and matching chat prefixes, with simple clash avoidance between two sides.
 */
public final class NationKitColors {

    public record KitPair(Color red, Color blue, String redChat, String blueChat) {
    }

    private NationKitColors() {
    }

    public static KitPair resolve(FifaNation redNation, FifaNation blueNation) {
        String redChat = nationHomeChatCode(redNation, TeamSlot.RED);
        String blueChat = nationHomeChatCode(blueNation, TeamSlot.BLUE);

        if (chatCodesEqual(redChat, blueChat)) {
            String alt = alternateChatCode(blueNation);
            if (!chatCodesEqual(redChat, alt)) {
                blueChat = alt;
            } else {
                alt = alternateChatCode(redNation);
                if (!chatCodesEqual(alt, blueChat)) {
                    redChat = alt;
                } else {
                    blueChat = defaultChatCode(TeamSlot.BLUE);
                }
            }
        }

        Color red = fromChatCode(redChat);
        Color blue = fromChatCode(blueChat);
        if (colorsTooSimilar(red, blue)) {
            blueChat = defaultChatCode(TeamSlot.BLUE);
            blue = fromChatCode(blueChat);
        }
        return new KitPair(red, blue, redChat, blueChat);
    }

    public static String nationHomeChatCode(FifaNation nation, TeamSlot slot) {
        if (nation == null) {
            return defaultChatCode(slot);
        }
        String primary = nation.getPrimaryColor();
        return normalizeChatCode(primary != null ? primary : "&f");
    }

    private static String alternateChatCode(FifaNation nation) {
        if (nation == null) {
            return "&7";
        }
        String secondary = nation.getSecondaryColor();
        if (secondary != null && !secondary.isBlank()) {
            return normalizeChatCode(secondary);
        }
        return normalizeChatCode(nation.getPrimaryColor());
    }

    private static String normalizeChatCode(String code) {
        if (code == null || code.isBlank()) {
            return "&f";
        }
        String trimmed = code.trim();
        if (trimmed.startsWith("§")) {
            trimmed = "&" + trimmed.charAt(trimmed.length() - 1);
        } else if (!trimmed.startsWith("&")) {
            trimmed = "&" + trimmed;
        }
        return trimmed;
    }

    private static boolean chatCodesEqual(String a, String b) {
        return normalizeChatCode(a).equalsIgnoreCase(normalizeChatCode(b));
    }

    public static Color fromChatCode(String code) {
        if (code == null || code.isBlank()) {
            return Color.fromRGB(200, 200, 200);
        }
        String trimmed = normalizeChatCode(code);
        ChatColor chat = ChatColor.getByChar(trimmed.charAt(trimmed.length() - 1));
        if (chat == null || !chat.isColor()) {
            return Color.fromRGB(200, 200, 200);
        }
        return switch (chat) {
            case BLACK -> Color.fromRGB(30, 30, 30);
            case DARK_BLUE -> Color.fromRGB(0, 0, 120);
            case DARK_GREEN -> Color.fromRGB(0, 90, 0);
            case DARK_AQUA -> Color.fromRGB(0, 110, 110);
            case DARK_RED -> Color.fromRGB(130, 0, 0);
            case DARK_PURPLE -> Color.fromRGB(100, 0, 100);
            case GOLD -> Color.fromRGB(210, 160, 0);
            case GRAY -> Color.fromRGB(130, 130, 130);
            case DARK_GRAY -> Color.fromRGB(70, 70, 70);
            case BLUE -> Color.fromRGB(55, 95, 210);
            case GREEN -> Color.fromRGB(70, 190, 70);
            case AQUA -> Color.fromRGB(90, 210, 220);
            case RED -> Color.fromRGB(210, 55, 55);
            case LIGHT_PURPLE -> Color.fromRGB(200, 90, 200);
            case YELLOW -> Color.fromRGB(240, 220, 60);
            case WHITE -> Color.fromRGB(245, 245, 245);
            default -> Color.fromRGB(200, 200, 200);
        };
    }

    private static boolean colorsTooSimilar(Color a, Color b) {
        if (a == null || b == null) {
            return false;
        }
        int dr = a.getRed() - b.getRed();
        int dg = a.getGreen() - b.getGreen();
        int db = a.getBlue() - b.getBlue();
        return (dr * dr + dg * dg + db * db) < 2800;
    }

    private static Color defaultColor(TeamSlot slot) {
        return fromChatCode(defaultChatCode(slot));
    }

    private static String defaultChatCode(TeamSlot slot) {
        return slot == TeamSlot.RED ? "&c" : "&9";
    }

    public enum TeamSlot {
        RED, BLUE
    }
}
