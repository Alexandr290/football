package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Live match browser and one-player stadium entry. */
public final class ActiveMatchesMenu extends AbstractMenu {

    private ActiveMatchesMenu(SoccerPlugin plugin, Player viewer) {
        super(plugin, viewer, "&lАКТИВНЫЕ МАТЧИ", 6);
    }

    public static void open(SoccerPlugin plugin, Player viewer) {
        new ActiveMatchesMenu(plugin, viewer).open();
    }

    @Override
    protected void render() {
        fillDashboard();
        int slot = 10;
        for (Game game : plugin.games().getGamesSnapshot()) {
            if (game.isEnded() || slot > 43) {
                continue;
            }
            String score = game.getRedScore() + " : " + game.getBlueScore();
            uiButton(slot++, Material.ENDER_EYE,
                    "&f&l" + game.getTeamDisplayName(TeamSide.RED)
                            + " &7" + score + " &f"
                            + game.getTeamDisplayName(TeamSide.BLUE),
                    () -> watch(game),
                    "&7Арена: &f" + game.getArena().getName(),
                    "&7Статус: &f" + plugin.messages().stateDisplay(game.getState()),
                    "",
                    "&aНажмите, чтобы смотреть");
        }
        if (slot == 10) {
            uiButton(22, Material.BARRIER, "&7Сейчас матчей нет", null,
                    "&7Вернитесь немного позже.");
        }
        backButton(49, () -> MainHubMenu.open(plugin, viewer));
    }

    private void watch(Game game) {
        if (game.getArena().getSpectator() == null && game.getArena().getLobby() == null
                && game.getArena().getBallSpawn() == null) {
            viewer.sendMessage("§cНа этой арене не настроено место зрителя.");
            return;
        }
        viewer.closeInventory();
        plugin.games().beginFreeWatch(viewer, game);
    }
}
