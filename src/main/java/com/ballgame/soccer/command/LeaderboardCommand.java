package com.ballgame.soccer.command;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.stats.ChatLeaderboard;
import com.ballgame.soccer.stats.LeaderboardCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Standalone {@code /leaderboard} command (also reachable via {@code /soccer top}). */
public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private final SoccerPlugin plugin;

    public LeaderboardCommand(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("soccer.play")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("all")) {
            ChatLeaderboard.sendOverview(sender, plugin, plugin.settings().leaderboardOverviewEntries);
            return true;
        }
        LeaderboardCategory category = LeaderboardCategory.parse(args[0]);
        ChatLeaderboard.sendCategory(sender, plugin, category, plugin.settings().leaderboardEntries);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ChatLeaderboard.tabCompleteCategories(args[0]), args[0]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
