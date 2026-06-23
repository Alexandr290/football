package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Solo quick join — choose bot count, then team color.
 */
public final class QuickJoinSoloMenu extends AbstractMenu {

    private int botCount;

    private QuickJoinSoloMenu(SoccerPlugin plugin, Player viewer, int botCount) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-solo-title"), 3);
        this.botCount = clamp(plugin, botCount);
    }

    public static void open(SoccerPlugin plugin, Player player) {
        open(plugin, player, plugin.settings().botDefaultSoloCount);
    }

    public static void open(SoccerPlugin plugin, Player player, int botCount) {
        new QuickJoinSoloMenu(plugin, player, botCount).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.LIME_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        int max = plugin.settings().botMaxSoloCount;

        inventory.setItem(4, Items.named(Material.ZOMBIE_HEAD,
                msg.raw("quickjoin-solo-header"),
                msg.format("quickjoin-solo-count", "count", String.valueOf(botCount), "max", String.valueOf(max)),
                msg.raw("quickjoin-solo-header-lore")));

        inventory.setItem(11, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-less"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(11, () -> new QuickJoinSoloMenu(plugin, viewer, botCount - 1).open());

        inventory.setItem(13, Items.named(Material.PAPER,
                msg.raw("quickjoin-solo-confirm"),
                msg.format("quickjoin-solo-confirm-lore", "count", String.valueOf(botCount))));
        registerClick(13, () -> openTeamPick());

        inventory.setItem(15, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-more"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(15, () -> new QuickJoinSoloMenu(plugin, viewer, botCount + 1).open());

        backButton(22, () -> QuickJoinModeMenu.open(plugin, viewer));
    }

    private void openTeamPick() {
        var target = plugin.games().findQuickJoinTarget(QuickJoinMode.SOLO);
        if (target == null) {
            plugin.messages().send(viewer, "no-arenas-available");
            return;
        }
        QuickJoinMenu.open(plugin, viewer, target.arena(), target.game(), QuickJoinMode.SOLO, botCount);
    }

    private static int clamp(SoccerPlugin plugin, int count) {
        int max = plugin.settings().botMaxSoloCount;
        int min = plugin.settings().botMinSoloCount;
        return Math.max(min, Math.min(max, count));
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
