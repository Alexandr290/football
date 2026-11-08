package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Command-free front door for AdvancedSoccer. Every player reaches matches,
 * history, championships and club information from this dashboard.
 */
public final class MainHubMenu extends AbstractMenu {

    private MainHubMenu(SoccerPlugin plugin, Player viewer) {
        super(plugin, viewer, "&lADVANCED SOCCER", 6);
    }

    private static boolean isMatchParticipant(SoccerPlugin plugin, Player player) {
        com.ballgame.soccer.game.Game game = plugin.games().getGame(player);
        return game != null && game.contains(player.getUniqueId());
    }

    public static void open(SoccerPlugin plugin, Player player) {
        if (player == null || !player.isOnline() || isMatchParticipant(plugin, player)) {
            return;
        }
        new MainHubMenu(plugin, player).open();
    }

    @Override
    protected void render() {
        fillDashboard();

        uiButton(11, Material.EMERALD, "&a&lНАЧАТЬ МАТЧ",
                () -> MatchSetupMenu.open(plugin, viewer),
                "&7Создайте матч с реальными клубами",
                "&7и выберите состав, арену и правила.");

        uiButton(13, Material.BOOK, "&e&lМОИ МАТЧИ",
                () -> GamesMenu.open(plugin, viewer),
                "&7Последние 20 игр и полная статистика.");

        uiButton(15, Material.GOLDEN_HELMET, "&6&lЧЕМПИОНАТЫ",
                () -> ChampMenus.openMain(plugin, viewer),
                "&7Лиги, кубки, таблицы и сетки.");

        uiButton(29, Material.ENDER_EYE, "&b&lАКТИВНЫЕ МАТЧИ",
                () -> ActiveMatchesMenu.open(plugin, viewer),
                "&7Смотрите матчи, которые идут сейчас.");

        uiButton(31, Material.SHIELD, "&d&lКЛУБЫ",
                () -> ClubBrowserMenu.open(plugin, viewer),
                "&7Лиги, составы и клубные цвета.");

        uiButton(33, Material.PLAYER_HEAD, "&f&lПРОФИЛЬ",
                () -> PlayerProfileMenu.open(plugin, viewer),
                "&7Ваши результаты за всё время.");

        uiButton(49, Material.KNOWLEDGE_BOOK, "&7&lКАК ИГРАТЬ",
                () -> HelpMenu.open(plugin, viewer),
                "&7Управление, удары, пасы и отбор.");
    }
}
