package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.stats.PlayerStats;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Lifetime player card. */
public final class PlayerProfileMenu extends AbstractMenu {

    private PlayerProfileMenu(SoccerPlugin plugin, Player viewer) {
        super(plugin, viewer, "&lПРОФИЛЬ", 5);
    }

    public static void open(SoccerPlugin plugin, Player viewer) {
        new PlayerProfileMenu(plugin, viewer).open();
    }

    @Override
    protected void render() {
        fillDashboard();
        PlayerStats s = plugin.stats().get(viewer.getUniqueId(), viewer.getName());

        uiButton(4, Material.PLAYER_HEAD, "&f&l" + viewer.getName(), null,
                "&7Карточка игрока AdvancedSoccer");
        uiButton(10, Material.BOOK, "&eМатчи: &f" + s.getGamesPlayed(), null);
        uiButton(12, Material.LIME_DYE, "&aПобеды: &f" + s.getWins(), null);
        uiButton(14, Material.GRAY_DYE, "&7Ничьи: &f" + s.getDraws(), null);
        uiButton(16, Material.RED_DYE, "&cПоражения: &f" + s.getLosses(), null);
        uiButton(20, Material.GOLD_NUGGET, "&6Голы: &f" + s.getGoals(), null);
        uiButton(22, Material.LAPIS_LAZULI, "&bГолевые передачи: &f" + s.getAssists(), null);
        uiButton(24, Material.SHIELD, "&dСейвы: &f" + s.getSaves(), null);
        uiButton(28, Material.IRON_BOOTS, "&fОтборы: &f" + s.getTackles(), null);
        uiButton(30, Material.WHITE_BANNER, "&fСухие матчи: &f" + s.getCleanSheets(), null);
        uiButton(32, Material.EXPERIENCE_BOTTLE, "&fПроцент побед: &a"
                + String.format(java.util.Locale.US, "%.1f%%", s.getWinRate()), null);

        backButton(40, () -> MainHubMenu.open(plugin, viewer));
    }
}
