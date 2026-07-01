package com.ballgame.soccer.stats;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats persistent player stats as multi-line chat leaderboards and can
 * broadcast them to the whole server.
 */
public final class ChatLeaderboard {

    private ChatLeaderboard() {
    }

    public static void sendCategory(CommandSender sender, SoccerPlugin plugin,
                                    LeaderboardCategory category, int limit) {
        for (String line : formatCategory(plugin, category, limit)) {
            sendLine(sender, plugin, line);
        }
    }

    public static void sendOverview(CommandSender sender, SoccerPlugin plugin, int limit) {
        for (String line : formatOverview(plugin, limit)) {
            sendLine(sender, plugin, line);
        }
    }

    public static void broadcastCategory(SoccerPlugin plugin, LeaderboardCategory category, int limit) {
        List<String> lines = formatCategory(plugin, category, limit);
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String line : lines) {
                sendLine(player, plugin, line);
            }
        }
    }

    public static List<String> formatCategory(SoccerPlugin plugin, LeaderboardCategory category, int limit) {
        List<String> lines = new ArrayList<>();
        lines.add(plugin.messages().format("leaderboard-header",
                "category", plugin.messages().raw(category.messageKey())));
        lines.add(plugin.messages().raw("leaderboard-divider"));

        List<PlayerStats> top = plugin.stats().leaderboard(category.value(), limit);
        if (top.isEmpty()) {
            lines.add(plugin.messages().raw("leaderboard-empty"));
        } else {
            int rank = 1;
            for (PlayerStats stats : top) {
                lines.add(formatEntry(plugin, rank, stats.getName(), category.value().applyAsInt(stats)));
                rank++;
            }
        }

        lines.add(plugin.messages().raw("leaderboard-divider"));
        lines.add(plugin.messages().raw("leaderboard-footer"));
        return lines;
    }

    public static List<String> formatOverview(SoccerPlugin plugin, int limitPerCategory) {
        List<String> lines = new ArrayList<>();
        lines.add(plugin.messages().raw("leaderboard-overview-header"));
        lines.add(plugin.messages().raw("leaderboard-divider"));

        boolean anyData = false;
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            List<PlayerStats> top = plugin.stats().leaderboard(category.value(), limitPerCategory);
            if (top.isEmpty()) {
                continue;
            }
            anyData = true;
            lines.add(plugin.messages().format("leaderboard-overview-section",
                    "category", plugin.messages().raw(category.messageKey())));
            int rank = 1;
            for (PlayerStats stats : top) {
                lines.add(formatEntry(plugin, rank, stats.getName(), category.value().applyAsInt(stats)));
                rank++;
            }
            lines.add("");
        }

        if (!anyData) {
            lines.add(plugin.messages().raw("leaderboard-empty"));
        }

        lines.add(plugin.messages().raw("leaderboard-divider"));
        lines.add(plugin.messages().raw("leaderboard-footer"));
        return lines;
    }

    private static String formatEntry(SoccerPlugin plugin, int rank, String name, int value) {
        String rankColor = switch (rank) {
            case 1 -> "&6";
            case 2 -> "&7";
            case 3 -> "&c";
            default -> "&e";
        };
        return plugin.messages().format("leaderboard-entry",
                "rank_color", rankColor,
                "rank", String.valueOf(rank),
                "player", name,
                "value", String.valueOf(value));
    }

    private static void sendLine(CommandSender sender, SoccerPlugin plugin, String line) {
        if (line == null || line.isEmpty()) {
            sender.sendMessage("");
            return;
        }
        sender.sendMessage(Text.color(plugin.messages().prefix() + line));
    }

    public static List<String> tabCompleteCategories(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        matches.add("all");
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            if (category.id().startsWith(lower)) {
                matches.add(category.id());
            }
        }
        return matches;
    }
}
