package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * First step of quick join — pick solo (vs bots) or teams (real players).
 */
public final class QuickJoinModeMenu extends AbstractMenu {

    private QuickJoinModeMenu(SoccerPlugin plugin, Player viewer) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-mode-title"), 3);
    }

    public static void open(SoccerPlugin plugin, Player player) {
        if (!plugin.settings().botsEnabled) {
            openTeamsFlow(plugin, player);
            return;
        }
        new QuickJoinModeMenu(plugin, player).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();

        inventory.setItem(4, Items.named(Material.COMPASS,
                msg.raw("quickjoin-mode-header"),
                msg.raw("quickjoin-mode-header-lore")));

        inventory.setItem(11, Items.named(Material.IRON_SWORD,
                msg.raw("quickjoin-mode-solo"),
                msg.raw("quickjoin-mode-solo-lore")));
        registerClick(11, () -> QuickJoinSoloMenu.open(plugin, viewer));

        inventory.setItem(15, Items.named(Material.PLAYER_HEAD,
                msg.raw("quickjoin-mode-teams"),
                msg.raw("quickjoin-mode-teams-lore")));
        registerClick(15, () -> openTeamsFlow(plugin, viewer));
    }

    private static void openTeamsFlow(SoccerPlugin plugin, Player player) {
        var target = plugin.games().findQuickJoinTarget(QuickJoinMode.TEAMS);
        if (target == null) {
            plugin.messages().send(player, "no-arenas-available");
            return;
        }
        if (plugin.settings().quickjoinTeamPick) {
            QuickJoinMenu.open(plugin, player, target.arena(), target.game(), QuickJoinMode.TEAMS, 0);
        } else {
            plugin.games().join(player, target.arena(), null);
        }
    }

    @Override
    public void handleClick(int slot, ClickType type) {
        Runnable action = MenuClicks.get(Integer.toHexString(System.identityHashCode(inventory)), slot);
        if (action != null) {
            clickSound();
            action.run();
        }
    }
}
