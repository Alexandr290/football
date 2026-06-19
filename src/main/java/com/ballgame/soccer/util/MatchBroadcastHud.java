package com.ballgame.soccer.util;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Fixed TV broadcast ticker rendered as a client-side boss bar overlay (top of
 * screen). Unlike {@link org.bukkit.entity.TextDisplay}, it never moves with
 * the world or the camera.
 */
public final class MatchBroadcastHud {

    private final BossBar bossBar;

    public MatchBroadcastHud(SoccerPlugin plugin) {
        bossBar = plugin.getServer().createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10);
        bossBar.setProgress(0);
        bossBar.setVisible(true);
    }

    public void attach(Player player) {
        bossBar.addPlayer(player);
    }

    public void detach(Player player) {
        bossBar.removePlayer(player);
    }

    public void destroy() {
        bossBar.removeAll();
    }

    public void setVisible(boolean visible) {
        bossBar.setVisible(visible);
    }

    public void update(String title, double progress, boolean extraTime) {
        bossBar.setTitle(Text.color(title));
        bossBar.setColor(extraTime ? BarColor.YELLOW : BarColor.GREEN);
        bossBar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
    }

    /**
     * FIFA-style single-line ticker: time pill, team codes, mint score boxes,
     * centre ball icon, possession dots.
     */
    public static String tvLine(String redColor, String redName, int redScore,
                                int blueScore, String blueName, String blueColor,
                                String timeText, boolean halftime, boolean extraTime,
                                TeamSide possession) {
        String clock = clockSegment(timeText, halftime, extraTime);
        String codeA = teamCode(redName);
        String codeB = teamCode(blueName);

        return clock
                + " &8▐ &r"
                + redColor + "&l" + codeA + "&r "
                + possessionDot(TeamSide.RED, possession) + " "
                + scoreBox(redScore) + " "
                + "&8│ &6&l⚽&r &8│ "
                + scoreBox(blueScore) + " "
                + possessionDot(TeamSide.BLUE, possession) + " "
                + blueColor + "&l" + codeB + "&r"
                + " &8▐";
    }

    private static String clockSegment(String time, boolean halftime, boolean extraTime) {
        if (halftime) {
            return "&f&l HT &r";
        }
        if (extraTime) {
            return "&e&l+" + time + "&r";
        }
        return "&f&l" + time + "&r";
    }

    private static String scoreBox(int score) {
        return "&#3d9970&l " + score + " &r";
    }

    private static String possessionDot(TeamSide team, TeamSide possession) {
        if (possession == team) {
            return "&f&l●&r";
        }
        return "&8●&r";
    }

    private static String teamCode(String name) {
        String plain = Text.strip(name).toUpperCase(Locale.ROOT).replaceAll("[^A-ZА-ЯЁ0-9]", "");
        if (plain.length() >= 3) {
            return plain.substring(0, 3);
        }
        if (plain.isEmpty()) {
            return "???";
        }
        StringBuilder padded = new StringBuilder(plain);
        while (padded.length() < 3) {
            padded.append('?');
        }
        return padded.toString();
    }
}
