package com.ballgame.soccer.scoreboard;

import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.Map;

/**
 * A flicker-free sidebar scoreboard shared by all participants of a match.
 *
 * <p>Each visible line is backed by a hidden, unique entry (a distinct sequence
 * of {@link ChatColor} codes) whose text is supplied by a team prefix. This
 * lets lines be long, fully colored and even duplicated without conflict.</p>
 */
public final class MatchScoreboard {

    private static final int MAX_LINES = 15;

    private final Scoreboard scoreboard;
    private final Objective objective;
    private int builtLines = -1;

    public MatchScoreboard(String title) {
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective(
                "soccer", Criteria.DUMMY, Text.color(title));
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    public void setTitle(String title) {
        objective.setDisplayName(Text.color(title));
    }

    /**
     * Update all lines. {@code rawLines} may contain {@code {placeholders}} which
     * are replaced using {@code placeholders}. Lines are rendered top-to-bottom.
     */
    public void update(String title, List<String> rawLines, Map<String, String> placeholders) {
        setTitle(title);
        int count = Math.min(rawLines.size(), MAX_LINES);

        if (builtLines != count) {
            rebuild(count);
        }

        for (int i = 0; i < count; i++) {
            String text = applyPlaceholders(rawLines.get(i), placeholders);
            Team team = scoreboard.getTeam("line" + i);
            if (team != null) {
                team.setPrefix(Text.color(text));
            }
        }
    }

    private void rebuild(int count) {
        for (Team team : scoreboard.getTeams()) {
            team.unregister();
        }
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }
        for (int i = 0; i < count; i++) {
            String entry = uniqueEntry(i);
            Team team = scoreboard.registerNewTeam("line" + i);
            team.addEntry(entry);
            objective.getScore(entry).setScore(count - i);
        }
        builtLines = count;
    }

    private String uniqueEntry(int index) {
        // A unique, effectively-invisible token per line.
        ChatColor color = ChatColor.values()[index % ChatColor.values().length];
        return color.toString() + ChatColor.RESET;
    }

    private String applyPlaceholders(String line, Map<String, String> placeholders) {
        String result = line;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public void apply(Player player) {
        player.setScoreboard(scoreboard);
    }

    public void clear(Player player) {
        if (player.getScoreboard().equals(scoreboard)) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }
}
