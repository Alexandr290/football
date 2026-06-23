package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Lets a player pick Red or Blue before joining the best available quick-join match.
 */
public final class QuickJoinMenu extends AbstractMenu {

    private final Arena arena;
    private final Game game;

    private QuickJoinMenu(SoccerPlugin plugin, Player viewer, Arena arena, Game game) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-gui-title"), 3);
        this.arena = arena;
        this.game = game;
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game) {
        new QuickJoinMenu(plugin, player, arena, game).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        SoccerSettings settings = plugin.settings();

        int redCount = game != null ? game.getTeamCount(TeamSide.RED) : 0;
        int blueCount = game != null ? game.getTeamCount(TeamSide.BLUE) : 0;
        String state = game != null ? game.getState().display() : msg.raw("quickjoin-gui-state-ready");

        inventory.setItem(4, Items.named(Material.COMPASS,
                msg.raw("quickjoin-gui-header"),
                msg.format("quickjoin-gui-arena", "arena", arena.getName()),
                msg.format("quickjoin-gui-status", "state", state),
                msg.format("quickjoin-gui-players", "count", String.valueOf(redCount + blueCount))));

        renderTeamSlot(11, TeamSide.RED, settings.redName, settings.redChatColor,
                redCount, settings.maxPlayersPerTeam);
        renderTeamSlot(15, TeamSide.BLUE, settings.blueName, settings.blueChatColor,
                blueCount, settings.maxPlayersPerTeam);
    }

    private void renderTeamSlot(int slot, TeamSide side, String teamName, String chatColor,
                                int count, int max) {
        Messages msg = plugin.messages();
        boolean full = game != null && game.isTeamFull(side);
        Material icon = side == TeamSide.RED ? Material.RED_WOOL : Material.BLUE_WOOL;
        if (full) {
            icon = Material.BARRIER;
        }

        inventory.setItem(slot, Items.named(icon,
                chatColor + "&l" + teamName,
                msg.format("quickjoin-gui-team-count",
                        "count", String.valueOf(count),
                        "max", String.valueOf(max)),
                full ? msg.raw("quickjoin-gui-team-full-lore") : msg.raw("quickjoin-gui-team-join-lore")));

        registerClick(slot, () -> tryJoin(side, teamName));
    }

    private void tryJoin(TeamSide side, String teamName) {
        if (game != null && game.isTeamFull(side)) {
            plugin.messages().send(viewer, "quickjoin-team-full", "team", teamName);
            clickSound();
            return;
        }
        if (plugin.games().join(viewer, arena, side)) {
            viewer.closeInventory();
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
