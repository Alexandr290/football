package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.championship.ChampSchedulePrompt;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampTeam;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * Arena picker shown after teams are chosen when scheduling a Soccer Cup match.
 */
public final class ChampScheduleArenaMenu extends AbstractMenu {

    private static final int[] ARENA_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private final String championshipId;
    private final String teamAId;
    private final String teamBId;

    private ChampScheduleArenaMenu(SoccerPlugin plugin, Player viewer, String championshipId,
                                   String teamAId, String teamBId) {
        super(plugin, viewer, plugin.messages().raw("champ-schedule-arena-title"), 4);
        this.championshipId = championshipId;
        this.teamAId = teamAId;
        this.teamBId = teamBId;
    }

    public static void open(SoccerPlugin plugin, Player player, String championshipId,
                            String teamAId, String teamBId) {
        new ChampScheduleArenaMenu(plugin, player, championshipId, teamAId, teamBId).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.ORANGE_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        Championship championship = plugin.championships().get(championshipId);
        ChampTeam teamA = championship != null ? championship.getTeam(teamAId) : null;
        ChampTeam teamB = championship != null ? championship.getTeam(teamBId) : null;
        String matchLine = (teamA != null ? teamA.getName() : "?")
                + " vs "
                + (teamB != null ? teamB.getName() : "?");

        List<Arena> arenas = availableArenas();
        inventory.setItem(4, Items.named(Material.MAP,
                msg.raw("champ-schedule-arena-header"),
                "&7" + matchLine,
                msg.raw("champ-schedule-arena-lore"),
                msg.format("quickjoin-arena-count", "count", String.valueOf(arenas.size()))));

        if (arenas.isEmpty()) {
            inventory.setItem(22, Items.named(Material.BARRIER,
                    msg.raw("quickjoin-arena-empty"),
                    msg.raw("quickjoin-arena-empty-lore")));
        } else {
            for (int i = 0; i < arenas.size() && i < ARENA_SLOTS.length; i++) {
                Arena arena = arenas.get(i);
                inventory.setItem(ARENA_SLOTS[i], Items.named(Material.GRASS_BLOCK,
                        msg.format("quickjoin-arena-item", "arena", arena.getName()),
                        msg.raw("champ-schedule-arena-click")));
                registerClick(ARENA_SLOTS[i], () -> confirmSchedule(arena));
            }
        }

        backButton(31, () -> ChampMenus.openScheduleMenu(plugin, viewer, championshipId));
    }

    private List<Arena> availableArenas() {
        List<Arena> arenas = new ArrayList<>();
        for (Arena arena : plugin.arenas().getArenas()) {
            if (arena.isPlayable()) {
                arenas.add(arena);
            }
        }
        return arenas;
    }

    private void confirmSchedule(Arena arena) {
        viewer.closeInventory();
        ChampSchedulePrompt.completeSchedule(
                plugin, viewer, championshipId, teamAId, teamBId, arena.getName());
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
