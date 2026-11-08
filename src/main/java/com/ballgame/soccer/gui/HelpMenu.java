package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Russian control reference reachable without commands. */
public final class HelpMenu extends AbstractMenu {

    private HelpMenu(SoccerPlugin plugin, Player viewer) {
        super(plugin, viewer, "&lКАК ИГРАТЬ", 5);
    }

    public static void open(SoccerPlugin plugin, Player viewer) {
        new HelpMenu(plugin, viewer).open();
    }

    @Override
    protected void render() {
        fillDashboard();
        uiButton(10, Material.IRON_SWORD, "&aЛКМ — удар", null,
                "&7Сила выбирается слотами 1–9:",
                "&710%–90%.");
        uiButton(12, Material.LEAD, "&bПКМ — пас", null,
                "&7Короткая передача низом.");
        uiButton(14, Material.FEATHER, "&eF — навес", null,
                "&7Высокая передача партнёру.");
        uiButton(16, Material.BOW, "&6Q — прострел", null,
                "&7Низкий сильный мяч в штрафную.");
        uiButton(20, Material.SHIELD, "&cБег + Shift — отбор", null,
                "&7Мяч нельзя забрать простым касанием.");
        uiButton(22, Material.GOLDEN_CHESTPLATE, "&dВратарь", null,
                "&7Shift — прыжок к штанге,",
                "&7ЛКМ — бросок к мячу.");
        uiButton(24, Material.COMPASS, "&fГлавное меню", null,
                "&7Нажмите компас в девятом слоте.");
        backButton(40, () -> MainHubMenu.open(plugin, viewer));
    }
}
